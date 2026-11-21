import assert from "node:assert/strict";
import test from "node:test";
import {
  intervalMs,
  isPeak,
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

test("排在 7:50 的 60 分钟定时器会被截断到 8:00", () => {
  const delay = nextDelayMs(beijing(7, 50));
  // 距 8:00 只有 10 分钟，必须截断，否则整个峰值窗口会被跳过。
  assert.equal(delay, 10 * 60 * 1000);
  assert.ok(delay < OFFPEAK_INTERVAL_MS);
});

test("峰值期内不截断，按 2 分钟走", () => {
  assert.equal(nextDelayMs(beijing(8, 30)), PEAK_INTERVAL_MS);
  assert.equal(msUntilNextPeakStart(beijing(8, 30)), Number.MAX_SAFE_INTEGER);
});

test("非峰值期不跨窗口时按 60 分钟走", () => {
  assert.equal(nextDelayMs(beijing(12, 0)), OFFPEAK_INTERVAL_MS);
});

test("23:50 排 60 分钟只到次日 00:50，不跨窗口所以不截断", () => {
  assert.equal(nextDelayMs(beijing(23, 50)), OFFPEAK_INTERVAL_MS);
  // 但「距下一个窗口起点」本身要算对：23:50 -> 次日 8:00 = 8 小时 10 分钟。
  assert.equal(msUntilNextPeakStart(beijing(23, 50)), (8 * 60 + 10) * 60 * 1000);
});

test("午夜过后同样按 60 分钟走，不被窗口干扰", () => {
  assert.equal(nextDelayMs(beijing(0, 30)), OFFPEAK_INTERVAL_MS);
  assert.equal(msUntilNextPeakStart(beijing(0, 30)), (7 * 60 + 30) * 60 * 1000);
});

test("7:30 的定时器会跨过 8:00，必须截断成 30 分钟", () => {
  assert.equal(nextDelayMs(beijing(7, 30)), 30 * 60 * 1000);
});
