package com.yuesf.remotecontrol.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.SpaceGuard

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // 双开空间里不应出现第二个被控实例，否则两个身份会互相抢采集。
        if (!SpaceGuard.isPrimarySpace(context)) {
            Log.w(TAG, "skip agent restart in secondary user space")
            return
        }

        val preferences = AppPreferences(context)
        // 不再判断角色：每台装了本 App 的设备都必须能被人按配对码连上，
        // 否则「谁都能控别人」这套设计在对方重启后就断了一半。
        if (preferences.loadConfig() == null) {
            return
        }

        runCatching {
            // 开机只恢复轻量信令保活服务（在线待控）；被控服务由它收到
            // control-request 时按需拉起。
            val serviceIntent = Intent(context, SignalingKeepAliveService::class.java)
                .setAction(SignalingKeepAliveService.ACTION_START)
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure { error ->
            Log.e(TAG, "Unable to restart the signaling service after boot", error)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
