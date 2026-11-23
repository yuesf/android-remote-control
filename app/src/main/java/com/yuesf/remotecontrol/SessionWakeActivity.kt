package com.yuesf.remotecontrol

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.yuesf.remotecontrol.debug.FileLog

/**
 * 会话期间把屏幕点亮，并尽量越过锁屏。
 *
 * 熄屏后 SurfaceFlinger 不再合成画面，VirtualDisplay 收不到任何帧——
 * 表现为 A 能连上、指示灯也绿了，但画面全是黑的，很容易被误判成「没连上」。
 *
 * 所以只要会话开始就拉起这个全透明 Activity：`setTurnScreenOn` 点亮屏幕、
 * `setShowWhenLocked` 让内容压过锁屏、`FLAG_KEEP_SCREEN_ON` 让屏幕在会话期间
 * 保持常亮。会话结束后自己 finish，屏幕交还给系统按超时熄灭。
 *
 * 非安全锁屏（无密码、滑动解锁）顺手划开；有密码的锁屏不尝试绕过，
 * 留给用户在 B 上解锁——这是 Android 的安全边界，绕不过去。
 */
class SessionWakeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 本 Activity 只是用来「点亮屏幕 + 保持常亮」，不该参与任何交互。
        //
        // 不加这两个 flag 的后果实测过：它虽然透明，但窗口仍然接得住触摸，
        // 于是所有注入的滑动/点击都落在它身上——锁屏纹丝不动，看起来就是
        // 「B 加了密码之后就滑不开了」。会话结束后本 Activity 释放，
        // 同一个 swipe 立刻就能把密码键盘拉出来，这就是铁证。
        //
        // FLAG_NOT_TOUCHABLE 让事件穿透到下一层（锁屏，或已解锁时的桌面 App），
        // FLAG_NOT_FOCUSABLE 让它不抢焦点，免得把锁屏的输入法焦点顶掉。
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )

        instance = this
        dismissLockIfPossible()
        FileLog.append("session wake: screen on")
    }

    private fun dismissLockIfPossible() {
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardLocked) {
            return
        }
        if (keyguard.isDeviceSecure) {
            // 有密码：只能停在锁屏页，等人在 B 上解锁。A 端此时应当看到锁屏画面，
            // 看到内容就说明采集是正常的，剩下的只是解锁这一步。
            FileLog.append("session wake: secure keyguard, wait for unlock")
            return
        }
        runCatching { keyguard.requestDismissKeyguard(this, null) }
            .onFailure { FileLog.append("session wake: dismiss keyguard failed") }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: SessionWakeActivity? = null

        fun isHolding(): Boolean = instance != null

        /**
         * 拉起亮屏页。后台启动 Activity 依赖悬浮窗权限（Android 10+ 的豁免项），
         * 没有权限时会静默失败——所以失败只记日志，不抛异常，别把会话带崩。
         */
        fun hold(context: Context) {
            if (instance != null) {
                return
            }
            runCatching {
                context.startActivity(
                    Intent(context, SessionWakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }.onFailure { FileLog.append("session wake: start failed ${it.message}") }
        }

        fun release() {
            val current = instance ?: return
            current.runOnUiThread { current.finish() }
        }
    }
}
