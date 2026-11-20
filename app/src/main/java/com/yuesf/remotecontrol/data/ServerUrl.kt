package com.yuesf.remotecontrol.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 统一信令服务器地址的解析口径。
 *
 * 用户经常只填 "8.152.3.131:8787"，缺协议头时直接判非法会给出
 * “地址格式不正确”，但地址本身其实是对的。这里缺 scheme 时补 http://，
 * 其它协议（ws、ftp 等）仍然拒绝，避免把不受支持的地址放进配置。
 */
fun normalizeServerUrl(raw: String): HttpUrl? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    val parsed = withScheme.toHttpUrlOrNull() ?: return null
    return if (parsed.scheme == "http" || parsed.scheme == "https") parsed else null
}
