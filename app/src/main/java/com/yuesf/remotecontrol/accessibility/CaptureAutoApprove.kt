package com.yuesf.remotecontrol.accessibility

import android.os.SystemClock

/**
 * 屏幕采集授权框的「代点」开关。
 *
 * 两种模式，区别只有一个：**弹窗要不要交给无障碍代点**。
 *
 * - [armManual]：用户在设置页手动点了「授权屏幕采集」。此时人就在手机前，
 *   弹窗应该原样留着让用户自己按「允许」。**无障碍一律不碰。**
 *   之前的实现在这里也让无障碍代点，结果无障碍算错了坐标、点到了对话框外面，
 *   系统把授权框当成「用户取消」直接关掉 → 回执 RESULT_CANCELED →
 *   Toast「未授予屏幕采集权限」。用户看到的就是「弹窗自己关掉了」。
 * - [armBackground]：后台被控服务自己拉起授权框（比如进程重启后采集凭证失效）。
 *   这时没人守在手机前，必须由无障碍代点「允许」，否则无人值守就卡死了。
 *
 * 无论哪种模式，代点都只在 [WINDOW_MS] 时间窗内生效。这一点不能省：
 * 无障碍服务能读所有窗口、能点所有按钮，如果常开自动点击，
 * 别的应用弹出的「允许」「确定」也会被它点头，那就成了危险品。
 *
 * 时间窗之外一律不响应，用户正常操作完全不受影响。
 */
object CaptureAutoApprove {
    private const val WINDOW_MS = 25_000L

    @Volatile
    private var armedAt = 0L

    /** 是否允许无障碍代点。只有后台自动路径才为 true。 */
    @Volatile
    private var autoClickEnabled = false

    /**
     * 手动路径：弹窗留给用户自己点「允许」。
     * 同时把可能还在生效的后台代点关掉，避免两套逻辑抢同一个弹窗。
     */
    fun armManual() {
        armedAt = 0L
        autoClickEnabled = false
        RemoteAccessibilityService.instance?.resetGeometryAttempts()
    }

    /** 后台路径：无人值守，允许无障碍代点确认。 */
    fun armBackground() {
        armedAt = SystemClock.elapsedRealtime()
        autoClickEnabled = true
        // 每次重新布防都是一次新的授权尝试，重试计数要跟着清零。
        RemoteAccessibilityService.instance?.resetGeometryAttempts()
    }

    fun disarm() {
        armedAt = 0L
        autoClickEnabled = false
    }

    /** 是否处于「可以让无障碍代点」的状态。手动模式下恒为 false。 */
    fun isArmed(): Boolean {
        val start = armedAt
        return start > 0L &&
            autoClickEnabled &&
            SystemClock.elapsedRealtime() - start <= WINDOW_MS
    }

    /** 授权框里的确认按钮文案（荣耀 MagicOS / 原生 / 英文都覆盖）。 */
    val approveLabels = listOf(
        "立即开始", "开始共享", "开始录制", "开始投放", "共享", "开始", "允许",
        "Start now", "Start sharing", "Start", "Share", "Allow",
    )

    /**
     * 用来确认「这确实是屏幕采集授权框」而不是别的对话框。
     * 必须先认出对话框，再去找确认按钮，否则「开始」这类通用文案极易误点。
     *
     * 这里只留「录制 / 投射 / 投放 / 投屏」这类**只会出现在采集授权框**的词。
     * 不要加「屏幕」「share」这种泛词：我们自己的主界面就有「授权屏幕采集」
     * 「屏幕采集授权已就绪」，泛词会让它把自己的界面当成授权框。
     *
     * 各家文案并不统一，实测例子：
     *   MagicOS / Android 14：是否允许"X"录制/投射您的屏幕？
     *   原生 Android 14：开始录制或投放屏幕
     */
    val dialogHints = listOf(
        "录制或投放", "录制/投射", "录制或投射", "开始录制", "屏幕录制", "屏幕共享", "共享屏幕",
        "cast", "screen capture", "share your screen",
    )

    /**
     * Android 14 会先让用户选共享范围（单个应用 / 整个屏幕）。
     * 远程协助要的是整个屏幕，若默认停在「单个应用」就切过去。
     */
    val wholeScreenLabels = listOf("整个屏幕", "全屏", "Entire screen", "Whole screen")

    /**
     * 取消/拒绝按钮的文案。它在视图树里是**可见**的（系统只隐藏了确认按钮），
     * 所以可以用它反推确认按钮的坐标。
     *
     * 实测各机型不统一：荣耀 FNE-AN00 用的是「禁止」，PZ261A 用的是「拒绝」，
     * 原生 Android 14 是「取消」。三种都要覆盖，否则几何兜底找不到锚点。
     */
    val denyLabels = listOf(
        "禁止", "拒绝", "取消", "不允许", "拒绝共享",
        "Deny", "Cancel", "Don't allow", "Don’t allow",
    )
}
