import assert from "node:assert/strict";
import test from "node:test";
import {
  intervalMs,
  isPeak,
  KEEP_ALIVE_INTERVAL_MS,
  msUntilNextPeakStart,
  nextDelayMs,
  OFFPEAK_INTERVAL_MS,
  PEAK_INTERVAL_MS,
} from "./heartbeat-policy.js";

/** 北京时间转成 Date：北京 = UTC+8，直接减掉 8 小时即可。 */
function beijing(hour: number, minute: number, second = 0): Date {
  return new Date(Date.UTC(2026, 10, 21, hour - 8, minute, second));
}

test("8:00 到 8:59 走 2 分钟节拍", () => {
  for (const minute of [0, 1, 30, 58, 59]) {
    assert.equal(isPeak(beijing(8, minute)), true, `8:${minute} 应属于峰值`);
    assert.equal(intervalMs(beijing(8, minute)), PEAK_INTERVAL_MS);
  }
});

test("9:00 整开始回到 60 分钟节拍", () => {
  assert.equal(isPeak(beijing(9, 0)), false);
  assert.equal(intervalMs(beijing(9, 0)), OFFPEAK_INTERVAL_MS);
});

test("7:59 仍是非峰值", () => {
  assert.equal(isPeak(beijing(7, 59)), false);
  assert.equal(intervalMs(beijing(7, 59)), OFFPEAK_INTERVAL_MS);
});

test("排在 7:50 的保活定时器恰好落在 8:00", () => {
  const delay = nextDelayMs(beijing(7, 50));
  // 保活周期本身是 10 分钟，7:50 + 10 = 8:00，正好接上峰值窗口。
  assert.equal(delay, 10 * 60 * 1000);
});

test("峰值期内不截断，按 2 分钟走", () => {
  assert.equal(nextDelayMs(beijing(8, 30)), PEAK_INTERVAL_MS);
  assert.equal(msUntilNextPeakStart(beijing(8, 30)), Number.MAX_SAFE_INTEGER);
});

test("非峰值期按保活周期 10 分钟走，不是业务心跳的 60 分钟", () => {
  assert.equal(nextDelayMs(beijing(12, 0)), KEEP_ALIVE_INTERVAL_MS);
  // 这条断言守的是一个真实踩过的坑：曾经拿业务心跳（60 分钟）当保活周期，
  // 结果一条死连接要挂 3 小时才被回收，控制端整段时间都连不上。
  assert.ok(
    KEEP_ALIVE_INTERVAL_MS < OFFPEAK_INTERVAL_MS,
    "保活周期必须小于业务心跳，否则离线窗口等于一整个业务心跳周期",
  );
  assert.equal(isPeak(beijing(12, 0)), false);
});

test("23:50 按保活周期走，距窗口起点仍算得对", () => {
  assert.equal(nextDelayMs(beijing(23, 50)), KEEP_ALIVE_INTERVAL_MS);
  // 「距下一个窗口起点」本身要算对：23:50 -> 次日 8:00 = 8 小时 10 分钟。
  assert.equal(msUntilNextPeakStart(beijing(23, 50)), (8 * 60 + 10) * 60 * 1000);
});

test("午夜过后同样按保活周期走，不被窗口干扰", () => {
  assert.equal(nextDelayMs(beijing(0, 30)), KEEP_ALIVE_INTERVAL_MS);
  assert.equal(msUntilNextPeakStart(beijing(0, 30)), (7 * 60 + 30) * 60 * 1000);
});

test("7:30 距窗口还有 30 分钟，保活周期更短所以按 10 分钟走", () => {
  // 保活周期（10 分钟）比「到窗口的距离」（30 分钟）短，不构成截断条件。
  assert.equal(nextDelayMs(beijing(7, 30)), KEEP_ALIVE_INTERVAL_MS);
  assert.equal(msUntilNextPeakStart(beijing(7, 30)), 30 * 60 * 1000);
});
