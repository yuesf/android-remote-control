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
                _state.value = SignalingState(ConnectionPhase.ERROR, "$code: $message")
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

    fun refreshDevices(): Boolean = client.refreshDevices()

    fun sendControlRequest(targetDeviceId: String): Boolean =
        client.sendControlRequest(targetDeviceId)

    fun sendSignal(targetDeviceId: String, payload: org.json.JSONObject): Boolean =
        client.sendSignal(targetDeviceId, payload)
}
