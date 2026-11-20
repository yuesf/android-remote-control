package com.yuesf.remotecontrol.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.DeviceRole

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val preferences = AppPreferences(context)
        if (preferences.savedRoleOrNull() != DeviceRole.AGENT || preferences.loadConfig() == null) {
            return
        }

        runCatching {
            val serviceIntent = Intent(context, RemoteAgentService::class.java)
                .setAction(RemoteAgentService.ACTION_START)
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure { error ->
            Log.e(TAG, "Unable to restart the remote agent after boot", error)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
