package com.yuesf.remotecontrol.signaling

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.data.SignalingConfig
import com.yuesf.remotecontrol.debug.FileLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(private val listener: Listener) {
    interface Listener {
        fun onStateChanged(phase: ConnectionPhase, detail: String?)
        fun onDevicesChanged(devices: List<DeviceInfo>)
        fun onSignalReceived(signal: RemoteSignal)
        fun onServerError(code: String, message: String)
        fun onIceServersChanged(servers: List<IceServerSpec>)
    }

    /**
     * 客户端**不再主动 ping**，保活完全交给服务端每 10 分钟一次的 ping。
     *
     * 每次 ping 都会把 Wi-Fi 芯片从省电态拽起来、射频按高功率发一小段。
     * 60 秒一次是一天 1440 次唤醒，25 秒一次是 3456 次——这是待机耗电里
     * 数得着的一项，而它换来的只是「更早一点发现断线」。
     *
     * 现在的心跳契约是：
     * - 服务端每 10 分钟 ping 一次，客户端库自动回 pong（不动应用代码）；
     * - 连续 3 次不回，服务端 terminate，本端收到关闭回调后重连；
     * - 本端另有 `lastInboundAt` 兜底：超过 [STALE_INBOUND_TIMEOUT_MS] 没收到
     *   任何数据，即使标记为 online 也主动重建连接（黑洞 TCP 只能这么破）。
     */
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最后一次收到服务端字节的时间（elapsedRealtime），用于识别「连接挂着但已死」。 */
    @Volatile
    private var lastInboundAt: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var config: SignalingConfig? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var manuallyClosed = true

    @Volatile
    private var online = false

    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectRunnable: Runnable? = null

    @Synchronized
    fun connect(newConfig: SignalingConfig, force: Boolean = false) {
        // 幂等：界面和服务都会调用 connect，但它们是同一个单例。
        // 以前每次都 cancel + 重开，导致「刚注册好就被自己踢掉」的抖动，
        // 旧 socket 的失败回调还可能把新连接的 online/socket 一起清掉。
        // force=true 用于「连接僵死但状态还写着在线」的场景，比如 ensureConnected 判超时。
        if (!force && !manuallyClosed && online && config == newConfig) {
            return
        }
        FileLog.append("connect: ${newConfig.deviceName} (online=$online force=$force)")
        config = newConfig
        manuallyClosed = false
        online = false
        lastInboundAt = SystemClock.elapsedRealtime()
        reconnectAttempt = 0
        reconnectScheduled = false
        generation += 1
        cancelReconnect()
        socket?.cancel()
        socket = null
        open(generation)
    }

    @Synchronized
    fun disconnect() {
        manuallyClosed = true
        online = false
        generation += 1
        reconnectScheduled = false
        config = null
        cancelReconnect()
        socket?.close(1000, "Client requested disconnect")
        socket = null
        notifyState(ConnectionPhase.DISCONNECTED, null)
    }

    /** 供保活机制判断「现在到底连上没有」，不要用 UI 层的 phase，那个会被各种事件染色。 */
    val isOnline: Boolean
        get() = online && !manuallyClosed

    /**
     * 兜底重连：不管当前处于什么状态，只要不是用户手动断开，就确保在连。
     *
     * 为什么需要它：重连是靠 [mainHandler] 的 `postDelayed` 排的，而 Doze 会挂起 CPU，
     * 回调根本不执行。拔掉充电线、锁屏、静置几分钟后，socket 被系统回收，
     * 这个 Handler 里的重试永远等不到——于是 B 端「不在线」，而且自己永远不会恢复。
     * 现在由 `AlarmManager.setExactAndAllowWhileIdle` 定时把服务叫醒，调这里续上。
     */
    @Synchronized
    fun ensureConnected() {
        val activeConfig = config ?: return
        if (manuallyClosed) {
            return
        }
        if (online) {
            val silence = SystemClock.elapsedRealtime() - lastInboundAt
            if (silence < STALE_INBOUND_TIMEOUT_MS) {
                return
            }
            // 「标记在线」不等于「真的通」：黑洞 TCP 下 socket 不会报错，
            // 只会一直安静。这种连接只能主动丢弃重建。
            FileLog.append("ensureConnected: silent ${silence / 60_000}min, rebuild")
        } else {
            FileLog.append("ensureConnected: reconnect (attempt=$reconnectAttempt)")
        }
        connect(activeConfig, force = true)
    }

    fun refreshDevices(): Boolean = sendJson(JSONObject().put("type", "list_devices"))

    fun sendControlRequest(targetDeviceId: String): Boolean =
        sendSignal(
            targetDeviceId,
            JSONObject()
                .put("kind", "control-request")
                .put("protocolVersion", 1),
        )

    fun sendSignal(targetDeviceId: String, payload: JSONObject): Boolean {
        if (!online) {
            FileLog.append("send skipped: offline kind=${payload.optString("kind")}")
            return false
        }
        val ok = sendJson(
            JSONObject()
                .put("type", "signal")
                .put("targetDeviceId", targetDeviceId)
                .put("payload", payload),
        )
        if (!ok) {
            FileLog.append(
                "send failed: kind=${payload.optString("kind")} socketNull=${socket == null}",
            )
        }
        return ok
    }

    @Synchronized
    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun sendJson(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    private fun open(expectedGeneration: Int) {
        val activeConfig = config
        if (manuallyClosed || expectedGeneration != generation || activeConfig == null) {
            return
        }

        val attempt = reconnectAttempt
        notifyState(
            if (attempt == 0) ConnectionPhase.CONNECTING else ConnectionPhase.RECONNECTING,
            if (attempt == 0) null else "第 $attempt 次重试",
        )

        val request = Request.Builder()
            .url(activeConfig.webSocketUrl)
            .header("Authorization", "Bearer ${activeConfig.authToken}")
            .build()

        val newSocket = httpClient.newWebSocket(request, WebSocketSession(expectedGeneration))
        synchronized(this) {
            if (expectedGeneration == generation && !manuallyClosed) {
                socket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    private fun handleDisconnect(expectedGeneration: Int, reason: String?) {
        synchronized(this) {
            // 先判 generation 再改状态。被替换掉的旧连接同样会回调失败，
            // 如果先执行 online=false / socket=null，就会把新连接刚建立的状态清掉，
            // 之后所有 send 都返回 false——表现是「在线但永远不回消息」。
            if (expectedGeneration != generation) {
                FileLog.append("stale disconnect ignored gen=$expectedGeneration/$generation")
                return
            }
            online = false
            socket = null
            if (manuallyClosed || reconnectScheduled) {
                return
            }

            reconnectScheduled = true
            val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
            reconnectAttempt += 1
            notifyState(ConnectionPhase.RECONNECTING, reason)

            val runnable = Runnable {
                synchronized(this) {
                    if (expectedGeneration != generation || manuallyClosed) {
                        return@synchronized
                    }
                    reconnectScheduled = false
                    reconnectRunnable = null
                }
                open(expectedGeneration)
            }
            reconnectRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun notifyState(phase: ConnectionPhase, detail: String?) {
        mainHandler.post { listener.onStateChanged(phase, detail) }
    }

    private fun dispatchDevices(message: JSONObject) {
        val devices = parseDevices(message.optJSONArray("devices") ?: JSONArray())
        mainHandler.post { listener.onDevicesChanged(devices) }
    }

    private fun dispatchSignal(message: JSONObject) {
        val from = message.optString("fromDeviceId")
        if (from.isBlank()) {
            return
        }
        val payload = message.optJSONObject("payload") ?: JSONObject()
        mainHandler.post { listener.onSignalReceived(RemoteSignal(from, payload)) }
    }

    private fun dispatchIceServers(array: JSONArray?) {
        val specs = mutableListOf<IceServerSpec>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val urlArray = item.optJSONArray("urls") ?: continue
                val urls = buildList {
                    for (urlIndex in 0 until urlArray.length()) {
                        val url = urlArray.optString(urlIndex).trim()
                        if (url.isNotEmpty()) {
                            add(url)
                        }
                    }
                }
                if (urls.isEmpty()) {
                    continue
                }
                specs.add(
                    IceServerSpec(
                        urls = urls,
                        username = item.optString("username").ifBlank { null },
                        credential = item.optString("credential").ifBlank { null },
                    ),
                )
            }
        }
        mainHandler.post { listener.onIceServersChanged(specs) }
    }

    private fun parseDevices(array: JSONArray): List<DeviceInfo> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val deviceId = item.optString("deviceId")
            val deviceName = item.optString("deviceName")
            if (deviceId.isBlank() || deviceName.isBlank()) {
                continue
            }
            add(
                DeviceInfo(
                    deviceId = deviceId,
                    role = DeviceRole.fromWire(item.optString("role")),
                    deviceName = deviceName,
                    lastSeenAt = item.optString("lastSeenAt").takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    private inner class WebSocketSession(
        private val expectedGeneration: Int,
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // 任何一帧都算「连接还活着」的证据，供 ensureConnected 判断黑洞 TCP。
            lastInboundAt = SystemClock.elapsedRealtime()
            if (expectedGeneration != generation) {
                Log.d(TAG, "drop stale message, gen=$expectedGeneration current=$generation")
                return
            }

            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            val type = message.optString("type")
            FileLog.append(
                "in type=$type kind=${message.optJSONObject("payload")?.optString("kind")}",
            )
            when (type) {
                "ready" -> {
                    online = true
                    reconnectAttempt = 0
                    dispatchIceServers(message.optJSONArray("iceServers"))
                    Log.i(TAG, "ready: registered as ${message.optString("deviceId")}")
                    notifyState(ConnectionPhase.ONLINE, message.optString("deviceId"))
                }

                "devices" -> {
                    Log.d(TAG, "devices: ${message.optJSONArray("devices")?.length() ?: 0} 台")
                    dispatchDevices(message)
                }

                "signal" -> {
                    val payloadObj = message.optJSONObject("payload")
                    Log.i(
                        TAG,
                        "signal in: kind=${payloadObj?.optString("kind")} from=${message.optString("fromDeviceId")}",
                    )
                    dispatchSignal(message)
                }

                "error" -> {
                    val code = message.optString("code", "SERVER_ERROR")
                    val detail = message.optString("message", "Signaling server rejected the request")
                    Log.w(TAG, "server error: $code $detail")
                    mainHandler.post { listener.onServerError(code, detail) }
                }

                else -> Log.d(TAG, "ignored message type=$type")
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (expectedGeneration != generation || manuallyClosed) {
                webSocket.close(1000, "Stale connection")
                return
            }

            val activeConfig = config ?: return
            FileLog.append("ws open, register as ${activeConfig.deviceName}")
            Log.i(TAG, "ws open, register as ${activeConfig.role.wireValue}/${activeConfig.deviceName}")
            val register = JSONObject()
                .put("type", "register")
                .put("deviceId", activeConfig.deviceId)
                .put("role", activeConfig.role.wireValue)
                .put("deviceName", activeConfig.deviceName)
            webSocket.send(register.toString())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "ws closed: code=$code reason=$reason gen=$expectedGeneration/$generation")
            FileLog.append("ws closed code=$code reason=$reason")
            // 必须先校验 generation：被替换掉的那条旧连接同样会收到 4001，
            // 如果不判断就会把当前这条新连接也标记成「手动关闭」，从此不再重连。
            if (expectedGeneration != generation) {
                return
            }
            if (code == CLOSE_DEVICE_REPLACED) {
                manuallyClosed = true
                notifyState(ConnectionPhase.ERROR, "此设备已被另一个连接替换")
                return
            }
            handleDisconnect(expectedGeneration, reason.ifBlank { "连接已关闭" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = when {
                response?.code == 401 -> "访问令牌无效"
                !t.message.isNullOrBlank() -> t.message
                else -> "无法连接信令服务器"
            }
            Log.w(TAG, "ws failure: $detail gen=$expectedGeneration/$generation", t)
            FileLog.append("ws failure: $detail")
            handleDisconnect(expectedGeneration, detail)
        }
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val CLOSE_DEVICE_REPLACED = 4001

        /**
         * 多久没收到服务端任何字节就认为这条连接已经死了。
         *
         * 服务端 10 分钟一次 ping，正常情况下本端每 10 分钟至少收到一帧。
         * 取 35 分钟 = 3 个心跳周期再多一点，和服务端「3 次没回就失联」对齐：
         * 服务端判死它会关连接，服务端自己出问题时则靠这个超时兜底。
         */
        private const val STALE_INBOUND_TIMEOUT_MS = 35 * 60 * 1000L
    }
}
