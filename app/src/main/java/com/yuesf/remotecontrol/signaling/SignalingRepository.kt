package com.yuesf.remotecontrol.signaling

import com.yuesf.remotecontrol.data.SignalingConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object SignalingRepository {
    private val _state = MutableStateFlow(SignalingState(ConnectionPhase.DISCONNECTED))
    val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _signals = MutableSharedFlow<RemoteSignal>(extraBufferCapacity = 32)
    val signals = _signals.asSharedFlow()

    /**
     * 业务级错误（目标离线、目标不可达等），与连接状态解耦。
     * 只广播给当前关注它的界面，不改变 [state]，连接本身仍然可用。
     */
    private val _errors = MutableSharedFlow<ServerError>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    /**
     * 服务端下发的 ICE 配置，建连瞬间才用得到，不需要 Flow。
     */
    @Volatile
    var iceServers: List<IceServerSpec> = emptyList()
        private set

    private val client = SignalingClient(
        object : SignalingClient.Listener {
            override fun onStateChanged(phase: ConnectionPhase, detail: String?) {
                _state.value = SignalingState(phase, detail)
            }

            override fun onDevicesChanged(devices: List<DeviceInfo>) {
                _devices.value = devices
            }

            override fun onSignalReceived(signal: RemoteSignal) {
                _signals.tryEmit(signal)
            }

            override fun onServerError(code: String, message: String) {
                if (code in CONNECTION_LEVEL_ERRORS) {
                    _state.value = SignalingState(ConnectionPhase.ERROR, "$code: $message")
                } else {
                    // 目标离线这类错误只影响本次会话，不能污染连接状态：
                    // phase 一旦被打成 ERROR 就再也回不到 ONLINE（ready 只在注册时下发一次）。
                    _errors.tryEmit(ServerError(code, message))
                }
            }

            override fun onIceServersChanged(servers: List<IceServerSpec>) {
                iceServers = servers
            }
        },
    )

    fun connect(config: SignalingConfig) {
        _devices.value = emptyList()
        client.connect(config)
    }

    fun disconnect() {
        client.disconnect()
        _devices.value = emptyList()
    }

    val isOnline: Boolean
        get() = client.isOnline

    /** 保活闹钟与网络恢复回调用的兜底重连。 */
    fun ensureConnected() {
        client.ensureConnected()
    }

    fun refreshDevices(): Boolean = client.refreshDevices()

    fun sendControlRequest(targetDeviceId: String): Boolean =
        client.sendControlRequest(targetDeviceId)

    fun sendSignal(targetDeviceId: String, payload: org.json.JSONObject): Boolean =
        client.sendSignal(targetDeviceId, payload)

    /**
     * 这些错误意味着当前连接的协议状态本身不可用（未注册、重复注册、消息非法），
     * 只有它们才应该把 phase 打成 ERROR。
     */
    private val CONNECTION_LEVEL_ERRORS = setOf(
        "NOT_REGISTERED",
        "ALREADY_REGISTERED",
        "INVALID_MESSAGE",
        "UNSUPPORTED_MESSAGE",
    )
}
