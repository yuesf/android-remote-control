package com.yuesf.remotecontrol.signaling

import com.yuesf.remotecontrol.data.DeviceRole
import org.json.JSONObject

data class DeviceInfo(
    val deviceId: String,
    val role: DeviceRole,
    val deviceName: String,
)

data class RemoteSignal(
    val fromDeviceId: String,
    val payload: JSONObject,
)

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    ONLINE,
    RECONNECTING,
    ERROR,
}

data class SignalingState(
    val phase: ConnectionPhase,
    val detail: String? = null,
)
