package com.yuesf.remotecontrol

import android.content.Context
import android.os.PowerManager
import com.yuesf.remotecontrol.debug.FileLog

/**
 * 会话期间点亮屏幕并保持常亮。
 *
 * 之前这里是一个「全透明 Activity」+ `setShowWhenLocked`，结果在荣耀 ROM 上
 * 被渲染成**实心黑屏**：B 本机截图全黑，锁屏被整块盖住，A 上看到的也是黑的，
 * 注入的触摸虽然已经用 `FLAG_NOT_TOUCHABLE` 放行了，但视觉遮挡还在。
 *
 * 佐证很干脆：会话一结束（Activity 被 finish），同一台机器立刻恢复正常锁屏壁纸。
 * 也就是说主题里的 `windowIsTranslucent` + 透明 windowBackground 在这个 ROM 上
 * 并不生效——`setShowWhenLocked` 的窗口会被套上一层不透明背景。
 *
 * 所以改成完全不建窗口：用 WakeLock 的 `ACQUIRE_CAUSES_WAKEUP` 把屏幕点亮，
 * `FULL_WAKE_LOCK` 保持常亮。不占窗口 = 锁屏原样呈现 = 采集能拍到、触摸能到达。
 *
 * `FULL_WAKE_LOCK` 从 API 17 起就被标记为废弃，但功能一直在，是普通应用唯一
 * 能把屏幕从熄灭状态点亮的办法（`PowerManager.wakeUp` 需要系统权限）。
 */
object ScreenAwake {
    private const val TAG = "rc:screen-awake"

    /** 兜底超时：万一 release 没被调用（进程被杀前），不要让屏幕无限亮着。 */
    private const val TIMEOUT_MS = 60 * 60 * 1000L

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /** 点亮屏幕并保持常亮。重复调用无副作用。 */
    fun hold(context: Context) {
        if (wakeLock?.isHeld == true) {
            return
        }
        val manager = context.getSystemService(PowerManager::class.java)
        if (manager == null) {
            FileLog.append("screen awake: no power manager")
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            val flags = PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            @Suppress("DEPRECATION")
            val lock = manager.newWakeLock(flags, TAG).apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MS)
            }
            wakeLock = lock
            // 这里一定要同时记锁屏态：屏幕亮了但锁屏还在，是「A 上滑没反应」的
            // 最常见现场，只看 interactive=true 会误判成「已经能操作了」。
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            FileLog.append(
                "screen awake: acquired interactive=${manager.isInteractive} " +
                    "locked=${keyguard?.isKeyguardLocked} secure=${keyguard?.isDeviceSecure} " +
                    "doze=${manager.isDeviceIdleMode}",
            )
        }.onFailure { FileLog.append("screen awake: acquire failed ${it.message}") }
    }

    /** 会话结束，把屏幕交还给系统按超时熄屏。 */
    fun release() {
        val current = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (current.isHeld) {
                current.release()
                FileLog.append("screen awake: released")
            }
        }.onFailure { FileLog.append("screen awake: release failed ${it.message}") }
    }

    fun isHolding(): Boolean = wakeLock?.isHeld == true
}
