package com.yuesf.remotecontrol.agent

import java.util.Calendar

/**
 * 唤醒锁时段策略：只在用户约定的三个「常用时段」持有 PARTIAL_WAKE_LOCK，其余时间释放。
 *
 * 为什么要分时段：
 * - 常驻唤醒锁会让 CPU 全程无法深度睡眠，系统电池页直接显示「应用耗电量高」。
 *   实测 B 机：CPU 计算只有 23 秒 / 近 2 小时，但唤醒锁持有 1 天 4 小时——
 *   耗电不是算得多，是锁着不让睡。
 * - 但锁屏后 CPU 挂起时，WebSocket 下行数据堆在内核队列没人读，A 端发起连接
 *   B 端没有响应——所以「用到的时候」必须持锁。
 * - Doze 下系统本来就会忽略应用唤醒锁，真正保在线的是电池白名单 + 保活闹钟；
 *   因此释放锁**不会掉线**，只是锁屏那一刻的响应要等闹钟醒来（最多 10 分钟）。
 *
 * 用户指定的秒连时段（本地时间）：
 * - 早 8 点左右：07:30–09:00
 * - 晚 6 点左右：17:30–19:00
 * - 晚 9 点左右：20:30–22:00
 *
 * 窗口两端各留缓冲，避免闹钟在边界前唤醒时判定错误。
 */
object WakeLockPolicy {
    /** 秒连窗口（含起点、不含终点），单位为一天中的分钟数。 */
    private data class Window(val startMinute: Int, val endMinute: Int)

    private val WINDOWS = listOf(
        Window(7 * 60 + 30, 9 * 60),       // 07:30 - 09:00 早 8 点
        Window(17 * 60 + 30, 19 * 60),     // 17:30 - 19:00 晚 6 点
        Window(20 * 60 + 30, 22 * 60),     // 20:30 - 22:00 晚 9 点
    )

    /** 是否应持有唤醒锁。 */
    fun shouldHold(now: Calendar = Calendar.getInstance()): Boolean {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return WINDOWS.any { minute in it.startMinute until it.endMinute }
    }

    /**
     * 距离下一次窗口切换（开始或结束）的毫秒数，用来排精确闹钟。
     *
     * 返回 null 表示拿不到当前时间（理论上不会发生）。
     * 每次切换后重新调用即可得到下一个边界，形成一条自滚动的闹钟链。
     */
    fun nextBoundaryDelayMs(now: Calendar = Calendar.getInstance()): Long? {
        val current = now.get(Calendar.HOUR_OF_DAY) * 60L + now.get(Calendar.MINUTE)
        var best: Long? = null
        for (window in WINDOWS) {
            for (boundary in listOf(window.startMinute, window.endMinute)) {
                var delta = boundary - current
                if (delta <= 0) delta += 24 * 60L
                if (best == null || delta < best) best = delta
            }
        }
        return best?.let { it * 60_000L - now.get(Calendar.SECOND) * 1000L }
    }

    /** 诊断文案。 */
    fun describe(now: Calendar = Calendar.getInstance()): String =
        if (shouldHold(now)) "秒连时段" else "省电时段"
}
