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

    /** 峰值期业务心跳间隔。 */
    const val PEAK_INTERVAL_MS = 2 * 60 * 1000L

    /** 非峰值期业务心跳间隔。 */
    const val OFFPEAK_INTERVAL_MS = 60 * 60 * 1000L

    /**
     * 保活闹钟间隔——**和业务心跳不是一回事**，这里必须分开看。
     *
     * 业务心跳是"多久向用户/服务端证明一次我在线"（用户要的 2 分钟 / 60 分钟）。
     * 保活闹钟是"多久把自己从 Doze 里拽出来一次，确认长连接还活着"。
     *
     * 两者混用就会出大问题：非峰值按 60 分钟排闹钟，一旦连接在 Doze 里死了，
     * 最长要 60 分钟才重连——这 60 分钟里 B 就是离线的，用户感受到的正是
     * 「不充电就不在线」。
     *
     * 为什么是 10 分钟而不是更短：
     * - Doze 对 `setExactAndAllowWhileIdle` 的限流下界是 9 分钟（白名单应用），
     *   设得比 9 分钟短没有意义，系统会顺延。
     * - 10 分钟留 1 分钟余量，避免被判超频而进一步顺延。
     *
     * 代价不是白拿的：一天 144 次短唤醒（每次约 1-2 秒射频活跃）。但真正的大头
     * 是 Wi-Fi 高性能锁常驻，那个去掉就会掉线，换不得。相比之下这 144 次唤醒
     * 对续航的影响在 1% 以内，而它换来的是「离线窗口不超过 10 分钟」。
     */
    const val KEEP_ALIVE_INTERVAL_MS = 10 * 60 * 1000L

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
     * **不能再用业务心跳间隔去乘**：非峰值按 60 分钟算，3 倍就是 181 分钟——
     * 意味着一条已经黑洞的连接可以被容忍三个小时，用户看到的就是「一直离线」。
     *
     * 基准改成保活周期：只要连接活着，保活闹钟每 10 分钟都会带来一轮收发，
     * 线上服务端自己也在按 30 秒的节奏 ping。所以「1 个保活周期 + 5 分钟余量」
     * ＝ 15 分钟没有任何字节进来，这条连接一定已经死了，必须重建。
     *
     * 余量给 5 分钟是为了不误伤：保活闹钟本身可能被 Doze 顺延到 9 分钟，
     * 加上一次 TCP 重传的抖动，卡在 10 分钟整容易自己把自己判死。
     */
    fun staleInboundTimeoutMs(): Long =
        KEEP_ALIVE_INTERVAL_MS + 5 * 60_000L

    /**
     * 距下一个峰值窗口起点的毫秒数；当前已经在窗口内时返回 [Long.MAX_VALUE]。
     *
     * 用途：非峰值期排一个 10 分钟的定时器，如果这个定时器会跨过 8:00，
     * 就必须把它截断到 8:00，否则 7:55 排的闹钟 8:05 才响，峰值开局就漏了。
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

    /**
     * 闹钟该等多久。
     *
     * 峰值期跟着业务心跳走（2 分钟），非峰值期走保活周期（10 分钟），
     * 两者都不允许跨过峰值窗口起点。
     */
    fun nextDelayMs(window: Calendar = Calendar.getInstance()): Long {
        val keepAlive = if (isPeak(window)) PEAK_INTERVAL_MS else KEEP_ALIVE_INTERVAL_MS
        return minOf(keepAlive, msUntilNextPeakStart(window)).coerceAtLeast(1_000L)
    }
}
