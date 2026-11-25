package com.yuesf.remotecontrol

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.abs
import kotlin.math.max

class RemoteSessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRemoteSessionBinding
    private lateinit var targetDeviceId: String
    private lateinit var session: ControllerRtcSession

    private var requestSent = false
    private var ackReceived = false
    private var offerReceived = false
    private var targetSeenOnline = false

    /**
     * B 端是否设了锁屏密码。设了的话锁屏页面是安全窗口，采集不到，A 上看到的是黑的，
     * 只能等对方在本机解开。这个标记只用来把状态栏文案说清楚，不再提供解锁入口。
     */
    private var remoteSecure = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** 判定「轻点」的位移阈值，超过就算滑动，直接转发给远端。 */
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val timeoutHandler = Handler(Looper.getMainLooper())

    /** 控制栏当前是否展开。连通后自动收起，只留一个把手。 */
    private var controlsVisible = true

    /**
     * 用户手动切过的屏幕方向。App 默认跟随系统（自动旋转通常关着，恒为竖屏），
     * 这里记住按钮选的方向，旋转配置变化时用它恢复，避免转屏后又被拉回竖屏。
     */
    private var manualOrientation: Int? = null

    /** 连通后延时收起控制栏：画面本身就够看了，工具栏一直压着反而碍事。 */
    private val autoHideControls = Runnable { setControlsVisible(false) }

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
        enterImmersive()

        targetDeviceId = intent.getStringExtra(EXTRA_TARGET_DEVICE_ID).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_TARGET_DEVICE_NAME).orEmpty()
        if (targetDeviceId.isBlank()) {
            finish()
            return
        }

        binding.sessionTitle.text = getString(R.string.remote_session_title, targetName)
        binding.closeSessionButton.setOnClickListener { finish() }
        binding.rotateScreenButton.setOnClickListener { toggleOrientation() }
        updateRotateButtonLabel()
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
            onControlMessage = { /* 目前没有需要 A 端处理的异步回执 */ },
        )
        session.start()

        setupControls()
        observeSignaling()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(ackTimeout)
        timeoutHandler.removeCallbacks(requestTimeout)
        timeoutHandler.removeCallbacks(autoHideControls)
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

    /**
     * 沉浸式全屏：隐藏本机（控制端）的状态栏和导航条。
     *
     * 远程画面是按对方整块物理屏 1:1 缩放过来的，对方屏幕底部的导航图标
     * 落在本机屏幕最底下那一截。本机系统栏（状态栏 + 手势条区域）会压在
     * 画面上面，把对方底部的图标盖住——看起来就是「有的看不到、有的看到一半」。
     * 隐藏系统栏后画面独占整块屏，从屏幕边缘往里滑可临时呼出系统栏。
     */
    private fun enterImmersive() {
        // 关键：让窗口 frame 真正铺满整块物理屏。实测（PZ261A / Android 16）
        // hide 系统栏和 setDecorFitsSystemWindows(false) 都只是把栏的「可见性」
        // 关了，窗口 frame 仍然避开状态栏（mAppBounds=(0,124-1200,2553)），
        // 视频视图只有 1200x2546，按宽铺满后视频上下各被裁 ~60px，对方屏幕
        // 最底部的内容（导航图标下面的文字标签）正好落进被裁的那条里。
        // fitInsetsTypes=0 = 窗口不避开任何 inset（含状态栏、刘海）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.fitInsetsTypes = 0
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 实测光 hide() 在 PZ261A（Android 16）上只是让位、不真正隐藏状态栏，
        // 补一刀旧版 FLAG_FULLSCREEN（targetSdk<35 仍生效）。
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // 兜底：insets 在根布局就地消费，保证视图按整窗布局。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        logLayoutOnce()
    }

    /**
     * 手动切换横竖屏。清单里声明了 configChanges（orientation|screenSize），
     * 旋转不会重建 Activity，WebRTC 会话和已建立的画面不会断——只重新布局。
     *
     * 用 SENSOR 版而不是固定 SCREEN_ORIENTATION_LANDSCAPE：横屏时允许 90°/270°
     * 两个方向，用户倒着拿手机也能正确看到画面；竖屏同理。
     */
    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val target = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        manualOrientation = target
        requestedOrientation = target
        Log.i(TAG, "toggleOrientation: from=${if (landscape) "landscape" else "portrait"} target=$target")
        // 旋转后按钮文字要跟着变：当前是横屏时按钮显示「竖屏」（下一步动作）。
        updateRotateButtonLabel()
    }

    private fun updateRotateButtonLabel() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.rotateScreenButton.setText(
            if (landscape) R.string.switch_to_portrait else R.string.switch_to_landscape,
        )
    }

    /**
     * 转屏回调。configChanges 接管后系统不会重建 Activity，也不会自动重置沉浸式，
     * 所以这里要主动重新压一次系统栏——否则横屏后状态栏/手势条会重新露出来盖住画面。
     * 按钮文字也在这里同步（覆盖系统侧旋转、折叠屏展开等非按钮触发的方向变化）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersive()
        updateRotateButtonLabel()
    }

    /** 布局探针：确认视频视图铺满整块物理屏（debug 排查用，最多记 3 次）。 */
    private fun logLayoutOnce() {
        var logged = 0
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (logged >= 3) {
                        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        return
                    }
                    logged++
                    Log.i(
                        TAG,
                        "layout probe #" + logged +
                            " root=" + binding.root.width + "x" + binding.root.height +
                            " video=" + binding.remoteVideoView.width + "x" + binding.remoteVideoView.height +
                            " decor=" + window.decorView.width + "x" + window.decorView.height,
                    )
                }
            },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 对话框、通知栏收起后焦点回来时，系统栏可能被系统重新展示，再压一次。
        if (hasFocus && ::binding.isInitialized) {
            enterImmersive()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.backButton.setOnClickListener { sendGlobalAction("back") }
        binding.homeButton.setOnClickListener { sendGlobalAction("home") }
        binding.recentsButton.setOnClickListener { sendGlobalAction("recents") }

        // 隐藏时唯一的操作入口。收起用 GONE 而不是 alpha=0，
        // 否则透明控件仍会吃掉远端画面的触摸。
        binding.controlsHandle.setOnClickListener {
            timeoutHandler.removeCallbacks(autoHideControls)
            setControlsVisible(!controlsVisible)
        }

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
                    val moved = abs(event.x - downX) + abs(event.y - downY) > touchSlop
                    // 控制栏收起时，轻点画面先把控制栏叫回来，这一次点击不转发。
                    // 否则用户连「返回 / 主页 / 关闭」都点不到——之前只留了个小把手，
                    // 实际根本没人能发现，等于连上之后就退不出去了。
                    // 只拦「短按 + 没移动」，滑动和长按照常送到远端，不影响正常操作。
                    if (!controlsVisible && !moved && duration < TAP_TO_REVEAL_MS) {
                        showControls()
                    } else {
                        sendGesture(downX, downY, event.x, event.y, duration)
                    }
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
                                // B 设了密码的话，密码键盘属于安全窗口，媒体投影采不到，
                                // A 上看到的就是黑屏。不提前讲清楚，用户只会以为画面坏了。
                                remoteSecure = signal.payload.optBoolean("secure", false)
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
        sendNormalizedGesture(start.first, start.second, end.first, end.second, duration)
    }

    /** 归一化坐标直接下发：调用方只关心远端屏幕比例，不经过画面视图的坐标换算。 */
    private fun sendNormalizedGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ): Boolean {
        val sent = session.sendControl(
            JSONObject()
                .put("type", "gesture")
                .put("startX", startX.toDouble())
                .put("startY", startY.toDouble())
                .put("endX", endX.toDouble())
                .put("endY", endY.toDouble())
                .put("durationMs", duration.coerceIn(0L, 5_000L)),
        )
        if (!sent) {
            binding.sessionStatus.setText(R.string.remote_session_connecting)
        }
        return sent
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
                showConnectedStatus()
                scheduleControlsAutoHide()
            }

            status == "video-track" -> {
                showConnectedStatus()
                scheduleControlsAutoHide()
            }

            status.startsWith("error:") -> {
                // 出错了必须让控制栏回来，否则用户连「关闭」都点不到。
                showControls()
                val code = status.removePrefix("error:")
                val message = if (code == "CAPTURE_AUTH_REQUIRED") {
                    getString(R.string.screen_capture_required)
                } else {
                    getString(R.string.remote_session_error, code)
                }
                binding.sessionStatus.text = message
            }

            status == "closed:remote-ended" || status == "peer-state:CLOSED" -> {
                showControls()
                binding.sessionStatus.setText(R.string.remote_session_closed)
            }

            status == "peer-state:FAILED" -> {
                showControls()
                binding.sessionStatus.text = getString(
                    R.string.remote_session_error,
                    "peer connection failed",
                )
            }
        }
    }

    private fun showConnectedStatus() {
        binding.sessionStatus.setText(
            if (remoteSecure) R.string.remote_session_connected_secure
            else R.string.remote_session_connected,
        )
    }

    private fun scheduleControlsAutoHide() {
        timeoutHandler.removeCallbacks(autoHideControls)
        timeoutHandler.postDelayed(autoHideControls, AUTO_HIDE_DELAY_MS)
    }

    /** 出错或断线时把控制栏拉回来，并取消尚未触发的自动收起。 */
    private fun showControls() {
        timeoutHandler.removeCallbacks(autoHideControls)
        setControlsVisible(true)
    }

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) {
            return
        }
        controlsVisible = visible
        val bars = listOf(binding.topBar, binding.controlBar)
        if (visible) {
            bars.forEach { bar ->
                bar.visibility = View.VISIBLE
                bar.alpha = 0f
                bar.animate().alpha(1f).setDuration(160).start()
            }
            binding.controlsHandle.visibility = View.GONE
        } else {
            // 直接 GONE：alpha=0 的控件依然会拦截远端画面的触摸事件。
            bars.forEach { bar ->
                bar.animate().cancel()
                bar.alpha = 1f
                bar.visibility = View.GONE
            }
            binding.controlsHandle.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val TAG = "RemoteSession"
        const val EXTRA_TARGET_DEVICE_ID = "target_device_id"
        const val EXTRA_TARGET_DEVICE_NAME = "target_device_name"

        /** 等待 B 返回 offer 的上限。超过这个时间基本可以判定对方没有在处理请求。 */
        private const val REQUEST_TIMEOUT_MS = 20_000L

        /** 握手回执的上限。超过它基本可以判定 B 端的应用层根本没在处理消息。 */
        private const val ACK_TIMEOUT_MS = 6_000L

        /**
         * 连上之后控制栏停留多久再收起。
         * 2.5 秒太赶，用户还没看清有哪些按钮就没了；5 秒够确认一眼，也不至于长时间挡画面。
         */
        private const val AUTO_HIDE_DELAY_MS = 5_000L

        /** 超过这个时长的按压算长按，不做「唤出控制栏」的拦截，照常转发给远端。 */
        private const val TAP_TO_REVEAL_MS = 300L
    }
}
