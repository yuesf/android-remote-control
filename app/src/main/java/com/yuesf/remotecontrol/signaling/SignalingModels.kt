package com.yuesf.remotecontrol.signaling

import com.yuesf.remotecontrol.data.DeviceRole
import org.json.JSONObject

data class DeviceInfo(
    val deviceId: String,
    val role: DeviceRole,
    val deviceName: String,
    /**
     * 服务端最后一次收到该设备存活证据的时间（ISO-8601）。
     *
     * 空值表示服务端版本较旧、还没下发这个字段，此时只能按「连接存在」判断在线，
     * 显示上退回旧行为，不能因为拿不到时间就把设备标成离线。
     */
    val lastSeenAt: String? = null,
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

/**
 * 服务端返回的业务级错误，例如目标设备离线。
 *
 * 这类错误**不代表信令连接本身有问题**，必须与 [SignalingState] 分开：
 * 早先把它们直接写成 phase = ERROR，结果一次点错目标就把全局状态永久锁死
 * （只有 ready 能把 phase 拉回 ONLINE，而 ready 只在注册时发一次），
 * 后续所有会话请求都因为 phase != ONLINE 而发不出去。
 */
data class ServerError(
    val code: String,
    val message: String,
)
