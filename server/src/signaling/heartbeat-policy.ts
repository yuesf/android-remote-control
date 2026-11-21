/**
 * 心跳节拍策略：早 8:00–9:00（北京时间）每 2 分钟一轮，其余时间每 60 分钟一轮。
 *
 * 为什么服务端也要实现一份：心跳是服务端发起的（WebSocket ping），设备只回 pong，
 * 这样设备端不需要为「按时心跳」养一个定时器 —— 而定时器在 Doze 里本来就跑不起来。
 * 但控制端要判断「这台设备多久没动静了」，它必须知道当前节拍，所以 Android 侧
 * `HeartbeatPolicy` 里有同一组常量，两边改动必须同步。
 *
 * 时区口径：固定 UTC+8（中国大陆无夏令时，比引 Intl 更可控，也便于测试）。
 */

/** 峰值窗口起点小时（含）。 */
export const PEAK_START_HOUR = 8;

/** 峰值窗口终点小时（不含）。 */
export const PEAK_END_HOUR = 9;

/** 峰值期心跳间隔。 */
export const PEAK_INTERVAL_MS = 2 * 60 * 1000;

/** 非峰值期心跳间隔。 */
export const OFFPEAK_INTERVAL_MS = 60 * 60 * 1000;

/** 连续多少轮没有 pong 才判定失联。 */
export const MAX_MISSED_HEARTBEATS = 3;

const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const DAY_MINUTES = 24 * 60;

interface BeijingTime {
  hour: number;
  minute: number;
  second: number;
}

export function beijingTime(now: Date = new Date()): BeijingTime {
  const shifted = new Date(now.getTime() + BEIJING_OFFSET_MS);
  return {
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    second: shifted.getUTCSeconds(),
  };
}

export function isPeak(now: Date = new Date()): boolean {
  const { hour } = beijingTime(now);
  return hour >= PEAK_START_HOUR && hour < PEAK_END_HOUR;
}

export function intervalMs(now: Date = new Date()): number {
  return isPeak(now) ? PEAK_INTERVAL_MS : OFFPEAK_INTERVAL_MS;
}

/**
 * 距下一个峰值窗口起点的毫秒数；已在窗口内时返回 [Number.MAX_SAFE_INTEGER]。
 *
 * 这个函数存在的唯一目的：非峰值期排一个 60 分钟的定时器，如果它会跨过 8:00，
 * 必须截断到 8:00。否则 7:50 排的定时器 8:50 才触发，整个峰值窗口全被跳过，
 * 「早 8 点每 2 分钟心跳」这条规则形同虚设。
 */
export function msUntilNextPeakStart(now: Date = new Date()): number {
  if (isPeak(now)) {
    return Number.MAX_SAFE_INTEGER;
  }
  const { hour, minute, second } = beijingTime(now);
  const minutesToday = hour * 60 + minute;
  const peakStartMinutes = PEAK_START_HOUR * 60;
  const deltaMinutes =
    hour >= PEAK_END_HOUR
      ? DAY_MINUTES - minutesToday + peakStartMinutes
      : peakStartMinutes - minutesToday;
  return deltaMinutes * MINUTE_MS - second * 1000;
}

/** 下一次定时器应该等多久：正常间隔，但不跨过峰值窗口起点。 */
export function nextDelayMs(now: Date = new Date()): number {
  return Math.max(1000, Math.min(intervalMs(now), msUntilNextPeakStart(now)));
}
