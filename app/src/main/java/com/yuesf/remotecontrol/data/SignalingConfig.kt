package com.yuesf.remotecontrol.data

import okhttp3.HttpUrl

data class SignalingConfig(
    val serverUrl: HttpUrl,
    val authToken: String,
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole,
) {
    val webSocketUrl: HttpUrl
        get() = serverUrl.newBuilder()
            .scheme(if (serverUrl.isHttps) "wss" else "ws")
            .encodedPath("/ws")
            .query(null)
            .fragment(null)
            .build()
}
