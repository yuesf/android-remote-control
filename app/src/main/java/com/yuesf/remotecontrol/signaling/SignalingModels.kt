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

/**
 * 服务端通过 ready 消息下发的 ICE 配置。
 * 没有它，跨公网只能等 ICE 直连失败，严格 NAT 下永远不会连通。
 */
data class IceServerSpec(
    val urls: List<String>,
    val username: String?,
    val credential: String?,
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
