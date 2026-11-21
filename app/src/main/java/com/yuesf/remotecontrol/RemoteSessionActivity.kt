package com.yuesf.remotecontrol

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yuesf.remotecontrol.databinding.ActivityRemoteSessionBinding
import com.yuesf.remotecontrol.signaling.ConnectionPhase
import com.yuesf.remotecontrol.signaling.SignalingRepository
import com.yuesf.remotecontrol.webrtc.ControllerRtcSession
import com.yuesf.remotecontrol.webrtc.WebRtcEngine
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.RendererCommon
import kotlin.math.max

class RemoteSessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemoteSessionBinding
    private lateinit var targetDeviceId: String
    private lateinit var session: ControllerRtcSession

    private var requestSent = false
    private var ackReceived = false
    private var offerReceived = false
    private var targetSeenOnline = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val timeoutHandler = Handler(Looper.getMainLooper())

    /**
     * 请求发出后对方可能一直不响应（B 端服务在、但无障碍没开或屏幕采集未授权，
     * 甚至 B 端进程被系统冻结）。没有这个超时，界面会永远停在“等待中”。
     *
     * 有没有收到 [control-ack] 是两条完全不同的故障线，提示必须分开：
     * 没收到握手 = B 应用层没处理（没运行 / 被冻结 / 版本不对）；
     * 收到握手却没 offer = B 在处理，但建立画面的前置条件不满足。
     */
    private val requestTimeout = Runnable {
        if (offerReceived || isFinishing) {
            return@Runnable
        }
        binding.sessionStatus.setText(
            if (ackReceived) R.string.remote_session_no_response else R.string.remote_session_no_ack,
        )
    }

    /** 握手 6 秒还没回来，就先把「B 没收到」这个判断摆出来，不用干等 20 秒。 */
    private val ackTimeout = Runnable {
        if (!ackReceived && !offerReceived && !isFinishing) {
            binding.sessionStatus.setText(R.string.remote_session_no_ack)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        targetDeviceId = intent.getStringExtra(EXTRA_TARGET_DEVICE_ID).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_TARGET_DEVICE_NAME).orEmpty()
        if (targetDeviceId.isBlank()) {
            finish()
            return
        }

        binding.sessionTitle.text = getString(R.string.remote_session_title, targetName)
        binding.closeSessionButton.setOnClickListener { finish() }
        binding.remoteVideoView.setEnableHardwareScaler(true)
        binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        WebRtcEngine.initialize(this)
        binding.remoteVideoView.init(WebRtcEngine.eglBase.eglBaseContext, null)

        session = ControllerRtcSession(
            renderer = binding.remoteVideoView,
            onLocalSignal = { payload ->
                SignalingRepository.sendSignal(targetDeviceId, payload)
            },
            onStatusChanged = ::renderRtcStatus,
        )
        session.start()

        setupControls()
        observeSignaling()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(ackTimeout)
        timeoutHandler.removeCallbacks(requestTimeout)
        if (::session.isInitialized) {
            if (requestSent) {
                SignalingRepository.sendSignal(
                    targetDeviceId,
                    JSONObject().put("kind", "session-end"),
                )
            }
            session.close()
        }
        if (::binding.isInitialized) {
            binding.remoteVideoView.release()
        }
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.backButton.setOnClickListener { sendGlobalAction("back") }
        binding.homeButton.setOnClickListener { sendGlobalAction("home") }
        binding.recentsButton.setOnClickListener { sendGlobalAction("recents") }

        binding.remoteVideoView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.elapsedRealtime()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.elapsedRealtime() - downAt
                    sendGesture(downX, downY, event.x, event.y, duration)
                    true
                }

                MotionEvent.ACTION_CANCEL -> true

                else -> true
            }
        }
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SignalingRepository.state.collect { state ->
                        when (state.phase) {
                            ConnectionPhase.ONLINE -> requestSessionIfNeeded()
                            // 连接本身不可用，直接把原因摊开，不再停在初始文案。
                            ConnectionPhase.ERROR -> if (!requestSent) {
                                binding.sessionStatus.text = getString(
                                    R.string.remote_session_error,
                                    state.detail.orEmpty(),
                                )
                            }

                            else -> if (!requestSent) {
                                binding.sessionStatus.setText(R.string.remote_session_waiting_signaling)
                            }
                        }
                    }
                }
                launch {
                    SignalingRepository.errors.collect { error ->
                        timeoutHandler.removeCallbacks(requestTimeout)
                        val message = when (error.code) {
                            "TARGET_OFFLINE", "TARGET_UNAVAILABLE" ->
                                getString(R.string.remote_session_target_offline)

                            else -> getString(R.string.remote_session_error, error.code)
                        }
                        binding.sessionStatus.text = message
                    }
                }
                launch {
                    SignalingRepository.devices.collect { devices ->
                        // 只在“确认过对方在线、之后又消失”时才判离线，
                        // 避免刚进界面时列表为空导致误报。
                        if (!targetSeenOnline) {
                            if (devices.any { it.deviceId == targetDeviceId }) {
                                targetSeenOnline = true
                            }
                            return@collect
                        }
                        if (offerReceived) {
                            return@collect
                        }
                        if (devices.none { it.deviceId == targetDeviceId }) {
                            timeoutHandler.removeCallbacks(requestTimeout)
                            binding.sessionStatus.setText(R.string.remote_session_target_offline)
                        }
                    }
                }
                launch {
                    SignalingRepository.signals.collect { signal ->
                        if (signal.fromDeviceId != targetDeviceId) {
                            return@collect
                        }
                        // 单条信号解析失败不能终止整条订阅，否则会话界面会永久失联。
                        runCatching { session.handleSignal(signal.payload) }
                        when (signal.payload.optString("kind")) {
                            "control-ack" -> {
                                ackReceived = true
                                timeoutHandler.removeCallbacks(ackTimeout)
                                if (!offerReceived) {
                                    binding.sessionStatus.setText(R.string.remote_session_acked)
                                }
                            }

                            "webrtc-offer" -> {
                                offerReceived = true
                                timeoutHandler.removeCallbacks(ackTimeout)
                                timeoutHandler.removeCallbacks(requestTimeout)
                                // 收到 SDP 只代表对方应答了，不代表媒体通道通了；
                                // 真正「已连接」要等 ICE 完成或首帧到达。
                                binding.sessionStatus.setText(R.string.remote_session_waiting_media)
                            }

                            "session-error" -> {
                                timeoutHandler.removeCallbacks(ackTimeout)
                                timeoutHandler.removeCallbacks(requestTimeout)
                                val code = signal.payload.optString("code")
                                binding.sessionStatus.text = getString(
                                    when (code) {
                                        "ACCESSIBILITY_NOT_ENABLED" -> R.string.remote_session_err_accessibility
                                        "CAPTURE_AUTH_REQUIRED" -> R.string.remote_session_err_capture
                                        "DEVICE_BUSY" -> R.string.remote_session_err_busy
                                        else -> R.string.remote_session_error
                                    },
                                    code,
                                )
                            }

                            "session-ended" -> {
                                timeoutHandler.removeCallbacks(ackTimeout)
                                timeoutHandler.removeCallbacks(requestTimeout)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestSessionIfNeeded() {
        if (requestSent) {
            return
        }
        requestSent = SignalingRepository.sendControlRequest(targetDeviceId)
        if (requestSent) {
            binding.sessionStatus.setText(R.string.remote_session_waiting_offer)
            timeoutHandler.postDelayed(ackTimeout, ACK_TIMEOUT_MS)
            timeoutHandler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS)
        } else {
            binding.sessionStatus.setText(R.string.remote_session_waiting_signaling)
        }
    }

    private fun sendGlobalAction(action: String) {
        val sent = session.sendControl(
            JSONObject()
                .put("type", "global")
                .put("action", action),
        )
        if (!sent) {
            Toast.makeText(this, R.string.remote_session_connecting, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendGesture(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val start = mapToRemote(startX, startY)
        val end = mapToRemote(endX, endY)
        val sent = session.sendControl(
            JSONObject()
                .put("type", "gesture")
                .put("startX", start.first.toDouble())
                .put("startY", start.second.toDouble())
                .put("endX", end.first.toDouble())
                .put("endY", end.second.toDouble())
                .put("durationMs", duration.coerceIn(0L, 5_000L)),
        )
        if (!sent) {
            binding.sessionStatus.setText(R.string.remote_session_connecting)
        }
    }

    private fun mapToRemote(x: Float, y: Float): Pair<Float, Float> {
        val viewWidth = binding.remoteVideoView.width.toFloat().coerceAtLeast(1f)
        val viewHeight = binding.remoteVideoView.height.toFloat().coerceAtLeast(1f)
        val remoteWidth = session.remoteScreenWidth.toFloat().takeIf { it > 0f } ?: viewWidth
        val remoteHeight = session.remoteScreenHeight.toFloat().takeIf { it > 0f } ?: viewHeight

        val videoAspect = remoteWidth / max(1f, remoteHeight)
        val viewAspect = viewWidth / max(1f, viewHeight)
        val contentWidth: Float
        val contentHeight: Float
        val left: Float
        val top: Float

        if (viewAspect > videoAspect) {
            contentHeight = viewHeight
            contentWidth = viewHeight * videoAspect
            left = (viewWidth - contentWidth) / 2f
            top = 0f
        } else {
            contentWidth = viewWidth
            contentHeight = viewWidth / videoAspect
            left = 0f
            top = (viewHeight - contentHeight) / 2f
        }

        val normalizedX = ((x - left) / contentWidth).coerceIn(0f, 1f)
        val normalizedY = ((y - top) / contentHeight).coerceIn(0f, 1f)
        return normalizedX to normalizedY
    }

    private fun renderRtcStatus(status: String) {
        when {
            status == "connected" || status == "peer-state:CONNECTED" -> {
                binding.sessionStatus.setText(R.string.remote_session_connected)
            }

            status == "video-track" -> {
                binding.sessionStatus.setText(R.string.remote_session_connected)
            }

            status.startsWith("error:") -> {
                val code = status.removePrefix("error:")
                val message = if (code == "CAPTURE_AUTH_REQUIRED") {
                    getString(R.string.screen_capture_required)
                } else {
                    getString(R.string.remote_session_error, code)
                }
                binding.sessionStatus.text = message
            }

            status == "closed:remote-ended" || status == "peer-state:CLOSED" -> {
                binding.sessionStatus.setText(R.string.remote_session_closed)
            }

            status == "peer-state:FAILED" -> {
                binding.sessionStatus.text = getString(
                    R.string.remote_session_error,
                    "peer connection failed",
                )
            }
        }
    }

    companion object {
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_TARGET_DEVICE_NAME = "target_device_name"

        /** 等待 B 返回 offer 的上限。超过这个时间基本可以判定对方没有在处理请求。 */
        private const val REQUEST_TIMEOUT_MS = 20_000L

        /** 握手回执的上限。超过它基本可以判定 B 端的应用层根本没在处理消息。 */
        private const val ACK_TIMEOUT_MS = 6_000L
    }
}
