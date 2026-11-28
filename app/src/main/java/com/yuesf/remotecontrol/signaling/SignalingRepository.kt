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

    /** 本机四位配对码；未连上服务端时为 null。 */
    private val _pairCode = MutableStateFlow<String?>(null)
    val pairCode: StateFlow<String?> = _pairCode.asStateFlow()

    /** 按码查询的结果（码有效即回，含设备离线的情况）。 */
    private val _codeResolutions = MutableSharedFlow<CodeResolution>(extraBufferCapacity = 4)
    val codeResolutions = _codeResolutions.asSharedFlow()

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

            override fun onPairCodeChanged(pairCode: String?) {
                _pairCode.value = pairCode
            }

            override fun onCodeResolved(resolution: CodeResolution) {
                _codeResolutions.tryEmit(resolution)
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

    /**
     * 保活闹钟醒来时主动上报一次存活，顺带当探针用。
     *
     * 返回 false 表示这条连接已经发不出去了（socket 没了 / online 已落），
     * 调用方应立刻走一次强制重建，而不是干等 25 分钟的沉默超时。
     */
    fun reportPresence(): Boolean = client.reportPresence()

    /** 屏幕采集状态变化时由被控服务调用，随 register/ping 上报给服务端。 */
    fun setCaptureReady(ready: Boolean) {
        client.captureReady = ready
    }

    /** 上报失败后的强制重建，跳过所有"看起来还正常"的判断。 */
    fun forceReconnect() {
        client.forceReconnect()
    }

    fun refreshDevices(): Boolean = client.refreshDevices()

    /** 按四位配对码查设备。命中与否都通过 [codeResolutions] 或 [errors] 回来。 */
    fun resolveCode(code: String): Boolean = client.resolveCode(code)

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
