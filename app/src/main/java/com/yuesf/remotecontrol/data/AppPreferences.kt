package com.yuesf.remotecontrol.data

import android.content.Context
import android.os.Build
import com.yuesf.remotecontrol.BuildConfig
import java.util.UUID

class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        applyPresetIfOutdated()
    }

    /**
     * 预置配置只在全新安装时生效，装过旧版本的机器会一直沿用 SharedPreferences 里的
     * 旧地址与旧令牌，表现为“地址填对了却连不上”或 401。
     *
     * 这里按 [BuildConfig.PRESET_VERSION] 做一次性同步：预置内容变更后升级首启，
     * 把地址与令牌改成新的预置值；设备 ID、设备名、角色保持不动。
     * 用户手动保存配置后也会写入同一个版本号，之后不再被覆盖。
     */
    private fun applyPresetIfOutdated() {
        if (BuildConfig.DEFAULT_SERVER_URL.isBlank() || BuildConfig.DEFAULT_AUTH_TOKEN.isBlank()) {
            return
        }
        if (preferences.getInt(KEY_PRESET_VERSION, 0) >= BuildConfig.PRESET_VERSION) {
            return
        }
        preferences.edit()
            .putString(KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL.trimEnd('/'))
            .putString(KEY_AUTH_TOKEN, BuildConfig.DEFAULT_AUTH_TOKEN)
            .putInt(KEY_PRESET_VERSION, BuildConfig.PRESET_VERSION)
            .apply()
    }

    val deviceId: String
        get() = synchronized(this) {
            preferences.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(KEY_DEVICE_ID, it).apply()
                }
        }

    fun savedServerUrl(): String =
        preferences.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL

    fun savedAuthToken(): String =
        preferences.getString(KEY_AUTH_TOKEN, null) ?: BuildConfig.DEFAULT_AUTH_TOKEN

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
        val parsedUrl = normalizeServerUrl(serverUrl) ?: return null

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
            .putInt(KEY_PRESET_VERSION, BuildConfig.PRESET_VERSION)
            .apply()

        return loadConfig()
    }

    fun loadConfig(): SignalingConfig? {
        val role = savedRoleOrNull() ?: return null
        val serverUrl = normalizeServerUrl(savedServerUrl()) ?: return null

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
        private const val KEY_PRESET_VERSION = "preset_version"
    }
}
