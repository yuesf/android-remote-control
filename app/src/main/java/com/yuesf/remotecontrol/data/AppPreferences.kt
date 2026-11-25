package com.yuesf.remotecontrol.data

import android.content.Context
import android.os.Build
import com.yuesf.remotecontrol.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
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
     * 角色选择已废弃：现在每台设备同时具备「可被控」与「可控制」两种能力，
     * 谁是控制端由「谁输入了对方的配对码」决定，不再需要用户申报。
     *
     * 这里恒返回 [DeviceRole.AGENT]，仅为了让 register 消息仍带一个 role 字段
     * 兼容旧服务端——新服务端把它当作可选字段忽略。
     */
    fun savedRoleOrNull(): DeviceRole = DeviceRole.AGENT

    fun save(serverUrl: String, authToken: String, deviceName: String): SignalingConfig? {
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
            .putInt(KEY_PRESET_VERSION, BuildConfig.PRESET_VERSION)
            .apply()

        return loadConfig()
    }

    fun loadConfig(): SignalingConfig? {
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
            role = savedRoleOrNull(),
        )
    }

    /**
     * 记录一次连接过的设备，最新的排最前，按 deviceId 去重，最多保留 [MAX_CONNECTED_DEVICES] 条。
     *
     * 配对码会随服务端重连而变（旧码失效），所以每次都覆盖成最新的值，
     * 卡片上点连接仍走 deviceId，码只用于展示，避免历史里的旧码把用户带偏。
     */
    fun saveConnectedDevice(deviceId: String, deviceName: String, pairCode: String?) {
        if (deviceId.isBlank() || deviceId == this.deviceId) return
        synchronized(this) {
            val existing = loadConnectedDevicesUnsafe()
                .filter { it.deviceId != deviceId }
                .toMutableList()
            existing.add(
                0,
                ConnectedDevice(
                    deviceId = deviceId,
                    deviceName = deviceName.trim().ifEmpty { "未命名设备" },
                    pairCode = pairCode?.trim()?.takeIf { it.isNotEmpty() },
                    lastConnectedAt = System.currentTimeMillis(),
                ),
            )
            val array = JSONArray()
            for (item in existing.take(MAX_CONNECTED_DEVICES)) {
                val obj = JSONObject()
                    .put("deviceId", item.deviceId)
                    .put("deviceName", item.deviceName)
                    .put("lastConnectedAt", item.lastConnectedAt)
                if (item.pairCode != null) obj.put("pairCode", item.pairCode)
                array.put(obj)
            }
            preferences.edit().putString(KEY_CONNECTED_DEVICES, array.toString()).apply()
        }
    }

    fun loadConnectedDevices(): List<ConnectedDevice> = synchronized(this) {
        loadConnectedDevicesUnsafe()
    }

    /** 调用方必须持有 synchronized(this)。解析全部走容错：坏数据宁可丢掉也不能让首页崩。 */
    private fun loadConnectedDevicesUnsafe(): List<ConnectedDevice> {
        val raw = preferences.getString(KEY_CONNECTED_DEVICES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("deviceId", "")
                if (id.isEmpty()) return@mapNotNull null
                ConnectedDevice(
                    deviceId = id,
                    deviceName = obj.optString("deviceName", "").ifEmpty { "未命名设备" },
                    pairCode = obj.optString("pairCode", "").ifEmpty { null },
                    lastConnectedAt = obj.optLong("lastConnectedAt", 0L),
                )
            }
        }.getOrElse { emptyList() }
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
        private const val KEY_CONNECTED_DEVICES = "connected_devices"
        private const val MAX_CONNECTED_DEVICES = 10
    }
}
