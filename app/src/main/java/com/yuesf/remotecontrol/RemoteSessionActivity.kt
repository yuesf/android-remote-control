package com.yuesf.remotecontrol

import android.annotation.SuppressLint
import android.os.Bundle
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
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

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
                        if (state.phase == ConnectionPhase.ONLINE) {
                            requestSessionIfNeeded()
                        } else if (!requestSent) {
                            binding.sessionStatus.setText(R.string.remote_session_connecting)
                        }
                    }
                }
                launch {
                    SignalingRepository.signals.collect { signal ->
                        if (signal.fromDeviceId != targetDeviceId) {
                            return@collect
                        }
                        session.handleSignal(signal.payload)
                        if (signal.payload.optString("kind") == "webrtc-offer") {
                            binding.sessionStatus.setText(R.string.remote_session_connected)
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
    }
}
