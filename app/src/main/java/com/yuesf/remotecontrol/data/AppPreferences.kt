package com.yuesf.remotecontrol.data

import android.content.Context
import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    val deviceId: String
        get() = synchronized(this) {
            preferences.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(KEY_DEVICE_ID, it).apply()
                }
        }

    fun savedServerUrl(): String = preferences.getString(KEY_SERVER_URL, "").orEmpty()

    fun savedAuthToken(): String = preferences.getString(KEY_AUTH_TOKEN, "").orEmpty()

    fun savedDeviceName(): String =
        preferences.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName()

    /**
     * 角色只由界面选择决定，不设默认值。首次安装返回 null，必须用户显式选择。
     */
    fun savedRoleOrNull(): DeviceRole? =
        preferences.getString(KEY_ROLE, null)?.let(DeviceRole::fromWire)

    fun saveRole(role: DeviceRole) {
        preferences.edit().putString(KEY_ROLE, role.wireValue).apply()
    }

    fun save(serverUrl: String, authToken: String, deviceName: String, role: DeviceRole): SignalingConfig? {
        val parsedUrl = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") {
            return null
        }

        val normalizedName = deviceName.trim()
        val normalizedToken = authToken.trim()
        if (normalizedName.isEmpty() || normalizedToken.length < 16) {
            return null
        }

        preferences.edit()
            .putString(KEY_SERVER_URL, parsedUrl.toString().trimEnd('/'))
            .putString(KEY_AUTH_TOKEN, normalizedToken)
            .putString(KEY_DEVICE_NAME, normalizedName)
            .putString(KEY_ROLE, role.wireValue)
            .apply()

        return loadConfig()
    }

    fun loadConfig(): SignalingConfig? {
        val role = savedRoleOrNull() ?: return null
        val serverUrl = savedServerUrl().toHttpUrlOrNull() ?: return null
        if (serverUrl.scheme != "http" && serverUrl.scheme != "https") {
            return null
        }

        val token = savedAuthToken()
        val deviceName = savedDeviceName().trim()
        if (token.length < 16 || deviceName.isEmpty()) {
            return null
        }

        return SignalingConfig(
            serverUrl = serverUrl,
            authToken = token,
            deviceId = deviceId,
            deviceName = deviceName,
            role = role,
        )
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "Android 设备" else model
    }

    companion object {
        private const val PREFERENCES_NAME = "remote_control"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ROLE = "role"
    }
}
