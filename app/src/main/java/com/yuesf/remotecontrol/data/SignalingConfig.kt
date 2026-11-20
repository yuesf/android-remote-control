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
     *
     * 用 addPathSegment 而不是 encodedPath("/ws")：反向代理把服务挂在子路径下时
     * （例如 https://host/rc），必须保留前缀，否则请求会跑到 /ws 导致 404。
     */
    val webSocketUrl: HttpUrl
        get() = serverUrl.newBuilder()
            .addPathSegment("ws")
            .query(null)
            .fragment(null)
            .build()

    val displayWebSocketUrl: String
        get() = webSocketUrl.toString().replaceFirst("http", "ws")
}
