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

        /** 服务端分配的本机四位配对码。断线后依然保留，重连也不会变。 */
        fun onPairCodeChanged(pairCode: String?) {}
        /** 按码查询到目标设备（含「码有效但设备离线」）。 */
        fun onCodeResolved(resolution: CodeResolution) {}
    }

    /**
     * 客户端**不再主动 ping**，保活完全交给服务端每 10 分钟一次的 ping。
     *
     * 每次 ping 都会把 Wi-Fi 芯片从省电态拽起来、射频按高功率发一小段。
     * 60 秒一次是一天 1440 次唤醒，25 秒一次是 3456 次——这是待机耗电里
     * 数得着的一项，而它换来的只是「更早一点发现断线」。
     *
     * 现在的心跳契约是（节拍统一由 [HeartbeatPolicy] 决定）：
     * - 早 8:00–9:00 每 2 分钟一轮，其余时间每 60 分钟一轮，服务端发起 ping，
     *   客户端库自动回 pong（不动应用代码）；
     * - 连续 3 次不回，服务端 terminate，本端收到关闭回调后重连；
     * - 本端另有 `lastInboundAt` 兜底：超过 `HeartbeatPolicy.staleInboundTimeoutMs()`
     *   没收到任何数据，即使标记为 online 也主动重建连接（黑洞 TCP 只能这么破）。
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
            if (silence < HeartbeatPolicy.staleInboundTimeoutMs()) {
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

    /**
     * 用四位配对码查目标设备。
     * 返回 false 只代表「这条消息没发出去」（离线/没连上），不代表码无效；
     * 码无效会走服务端错误 CODE_NOT_FOUND。
     */
    fun resolveCode(code: String): Boolean {
        if (!online) {
            FileLog.append("resolve skipped: offline code=$code")
            return false
        }
        return sendJson(
            JSONObject()
                .put("type", "resolve_code")
                .put("code", code.trim()),
        )
    }

    /**
     * 无条件重建连接。
     *
     * 和 [ensureConnected] 的区别：后者尊重"online 且没沉默太久"就不动，
     * 这里则直接丢掉现有 socket 重开。用在「保活上报明确失败」之后——
     * 连一条 ping 都发不出去，说明这条连接已经没救了，等沉默超时纯属浪费时间。
     */
    @Synchronized
    fun forceReconnect() {
        val activeConfig = config ?: return
        if (manuallyClosed) {
            return
        }
        FileLog.append("forceReconnect: rebuild from scratch")
        connect(activeConfig, force = true)
    }

    /**
     * 主动上报一次「我还活着」。
     *
     * 为什么需要它，而不是等服务端 ping：
     * 服务端的业务心跳按用户规则走（峰值 2 分钟 / 平时 60 分钟），非峰值期意味着
     * 服务端要 60 分钟才碰这条连接一次。控制端的在线判定读的是 `lastSeenAt`，
     * 如果只靠服务端 ping 刷新，平时 lastSeenAt 会长时间停在 60 分钟前，
     * 控制端就会把一台好好的设备显示成「已失联」。
     *
     * 所以被控端的保活闹钟（10 分钟一轮）醒来时，顺手发一条 ping：
     * - 服务端收到任何消息都会 touch，lastSeenAt 立刻刷新
     * - 顺带验证这条连接确实还能双向通，比"连接对象还在"可靠得多
     *
     * 返回 false 说明这条连接已经发不出去了，调用方应把它当作失效信号处理。
     */
    fun reportPresence(): Boolean {
        if (!online) {
            return false
        }
        val ok = sendJson(
            JSONObject()
                .put("type", "ping")
                .put("nonce", SystemClock.elapsedRealtime().toString()),
        )
        if (!ok) {
            FileLog.append("presence report failed: socket=${socket == null}")
        }
        return ok
    }

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
                    pairCode = item.optString("pairCode").takeIf { it.isNotBlank() },
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
                    val pairCode = message.optString("pairCode").takeIf { it.isNotBlank() }
                    val ownId = message.optString("deviceId")
                    Log.i(TAG, "ready: registered as $ownId code=$pairCode")
                    mainHandler.post { listener.onPairCodeChanged(pairCode) }
                    notifyState(ConnectionPhase.ONLINE, ownId)
                }

                "code-resolved" -> {
                    val resolution = CodeResolution(
                        code = message.optString("code"),
                        deviceId = message.optString("deviceId"),
                        deviceName = message.optString("deviceName"),
                        online = message.optBoolean("online"),
                    )
                    FileLog.append(
                        "code-resolved code=${resolution.code} online=${resolution.online}",
                    )
                    mainHandler.post { listener.onCodeResolved(resolution) }
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
    }
}
