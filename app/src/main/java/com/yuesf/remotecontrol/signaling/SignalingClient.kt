package com.yuesf.remotecontrol.signaling

import android.os.Handler
import android.os.Looper
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.data.SignalingConfig
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
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

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
    fun connect(newConfig: SignalingConfig) {
        config = newConfig
        manuallyClosed = false
        online = false
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
            return false
        }
        return sendJson(
            JSONObject()
                .put("type", "signal")
                .put("targetDeviceId", targetDeviceId)
                .put("payload", payload),
        )
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
            online = false
            socket = null
            if (manuallyClosed || expectedGeneration != generation || reconnectScheduled) {
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
                ),
            )
        }
    }

    private inner class WebSocketSession(
        private val expectedGeneration: Int,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (expectedGeneration != generation || manuallyClosed) {
                webSocket.close(1000, "Stale connection")
                return
            }

            val activeConfig = config ?: return
            val register = JSONObject()
                .put("type", "register")
                .put("deviceId", activeConfig.deviceId)
                .put("role", activeConfig.role.wireValue)
                .put("deviceName", activeConfig.deviceName)
            webSocket.send(register.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (expectedGeneration != generation) {
                return
            }

            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "ready" -> {
                    online = true
                    reconnectAttempt = 0
                    notifyState(ConnectionPhase.ONLINE, message.optString("deviceId"))
                }

                "devices" -> dispatchDevices(message)

                "signal" -> dispatchSignal(message)

                "error" -> {
                    val code = message.optString("code", "SERVER_ERROR")
                    val detail = message.optString("message", "Signaling server rejected the request")
                    mainHandler.post { listener.onServerError(code, detail) }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
            handleDisconnect(expectedGeneration, detail)
        }
    }

    companion object {
        private const val CLOSE_DEVICE_REPLACED = 4001
    }
}
