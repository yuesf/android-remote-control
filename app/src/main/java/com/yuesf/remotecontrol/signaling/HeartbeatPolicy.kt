package com.yuesf.remotecontrol.signaling

import java.util.Calendar

/**
 * 心跳节拍策略：早 8:00–9:00 每 2 分钟一次，其余时间每 60 分钟一次。
 *
 * 为什么把节拍放在客户端按**本地时间**算，而不是服务端统一拍：
 * 服务端的时区、客户端的时区未必一致，而「早上 8 点」对用户来说是手机上的 8 点。
 * 三端（服务端、被控端、控制端）用同一组常量、同一个时区口径，才不会出现
 * 「B 按 2 分钟发、服务端按 1 小时判」这种错位。
 *
 * 这个类的存在意义就是**单一事实来源**：任何一处要判断「现在该多久心跳一次」
 * 都必须调这里，不允许各模块自己写小时数。
 */
object HeartbeatPolicy {

    /** 峰值窗口起点小时（含）。 */
    const val PEAK_START_HOUR = 8

    /** 峰值窗口终点小时（不含），即 8:00:00–8:59:59。 */
    const val PEAK_END_HOUR = 9

    /** 峰值期心跳间隔。 */
    const val PEAK_INTERVAL_MS = 2 * 60 * 1000L

    /** 非峰值期心跳间隔。 */
    const val OFFPEAK_INTERVAL_MS = 60 * 60 * 1000L

    /** 连续多少次没有回应判定失联。 */
    const val MAX_MISSED_HEARTBEATS = 3

    fun isPeak(window: Calendar = Calendar.getInstance()): Boolean {
        val hour = window.get(Calendar.HOUR_OF_DAY)
        return hour in PEAK_START_HOUR until PEAK_END_HOUR
    }

    /** 当前时刻应有的心跳间隔。 */
    fun intervalMs(window: Calendar = Calendar.getInstance()): Long =
        if (isPeak(window)) PEAK_INTERVAL_MS else OFFPEAK_INTERVAL_MS

    /**
     * 多久收不到服务端任何字节，就认为这条连接已经死了。
     *
     * 等于 3 个心跳周期再加 1 分钟余量：周期本身保证正常情况下一定会收到东西，
     * 3 倍是为了容忍漏掉一两轮（弱网、射频休眠）。多给 1 分钟是为了避免
     * 在「心跳刚好卡在边界上」时自己把自己判死。
     */
    fun staleInboundTimeoutMs(window: Calendar = Calendar.getInstance()): Long =
        intervalMs(window) * MAX_MISSED_HEARTBEATS + 60_000L

    /**
     * 距下一个峰值窗口起点的毫秒数；当前已经在窗口内时返回 [Long.MAX_VALUE]。
     *
     * 用途：非峰值期排一个 60 分钟的定时器，如果这个定时器会跨过 8:00，
     * 就必须把它截断到 8:00，否则 7:50 排的闹钟 8:50 才响，整个峰值期全废。
     */
    fun msUntilNextPeakStart(window: Calendar = Calendar.getInstance()): Long {
        if (isPeak(window)) {
            return Long.MAX_VALUE
        }
        val hour = window.get(Calendar.HOUR_OF_DAY)
        if (hour >= PEAK_END_HOUR) {
            // 今天窗口已过，等下一天 8:00。
            val remainingToday = (24 - hour) * 60L - window.get(Calendar.MINUTE)
            return (remainingToday + PEAK_START_HOUR * 60L) * 60_000L -
                window.get(Calendar.SECOND) * 1000L
        }
        // 今天窗口还没开始。
        val deltaMinutes = (PEAK_START_HOUR - hour) * 60L - window.get(Calendar.MINUTE)
        return deltaMinutes * 60_000L - window.get(Calendar.SECOND) * 1000L
    }

    /** 下一次定时器应该等多久：正常间隔，但不跨过峰值窗口起点。 */
    fun nextDelayMs(window: Calendar = Calendar.getInstance()): Long {
        val interval = intervalMs(window)
        val untilPeak = msUntilNextPeakStart(window)
        return minOf(interval, untilPeak).coerceAtLeast(1_000L)
    }
}
