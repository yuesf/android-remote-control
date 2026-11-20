package com.yuesf.remotecontrol.data

import okhttp3.HttpUrl

data class SignalingConfig(
    val serverUrl: HttpUrl,
    val authToken: String,
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole,
) {
    /**
     * OkHttp 的 HttpUrl 只接受 http/https；newWebSocket 会自动映射为 ws/wss。
     * 这里不能把 scheme 改成 "ws"，否则会抛 IllegalArgumentException。
     */
    val webSocketUrl: HttpUrl
        get() = serverUrl.newBuilder()
            .encodedPath("/ws")
            .query(null)
            .fragment(null)
            .build()

    val displayWebSocketUrl: String
        get() = webSocketUrl.toString().replaceFirst("http", "ws")
}
