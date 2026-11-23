package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.yuesf.remotecontrol.ScreenAwake
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.signaling.SignalingRepository
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpParameters
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.math.abs

class AgentRtcSession(
    context: Context,
    private val capture: ScreenCaptureHolder,
    private val onLocalSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSessionClosed: (String) -> Unit,
    /** WebRTC 真正连上（首次 CONNECTED）时回调，用于点亮被控端的状态角标。 */
    private val onPeerConnected: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var controlChannel: DataChannel? = null

    private var captureWidth = 720
    private var captureHeight = 1280
    private var offerPayload: JSONObject? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var closed = false

    /** 建连成功的时刻。用于判断「会话是否还值得保留」。 */
    @Volatile
    private var connectedOnce = false

    @Volatile
    private var lastPeerState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW

    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private var answerTimeout: Runnable? = null
    private var dropTimeout: Runnable? = null

    fun start() {
        try {
            WebRtcEngine.initialize(appContext)
            WebRtcEngine.setIceServers(SignalingRepository.iceServers)
            val track = capture.videoTrack()
                ?: error("screen capture is not prepared")
            val dimensions = capture.dimensions()
            captureWidth = dimensions.first
            captureHeight = dimensions.second

            val peer = WebRtcEngine.createPeerConnection(peerObserver).also { peerConnection = it }
            peer.addTrack(track, listOf("screen"))
            applyVideoSenderLimits(peer)
            // 先把采集规格提到会话档（全尺寸 + 12fps）再出帧：
            // 空闲时为了省电是 360 宽 1fps，不切回来的话 A 看到的是糊的幻灯片。
            capture.enterSessionMode()
            capture.startCapture()

            val channel = peer.createDataChannel("control", DataChannel.Init().apply { ordered = true })
            channel?.registerObserver(dataChannelObserver)
            controlChannel = channel
            FileLog.append("agent channel created label=${channel?.label()} state=${channel?.state()}")

            // 熄屏时 SurfaceFlinger 不出帧，先把屏幕点亮，否则 A 连上也是黑屏。
            ScreenAwake.hold(appContext)
            createAndSendOffer(peer)
            armAnswerTimeout()
            onStatus("capturing")
        } catch (error: Throwable) {
            close("start-failed")
            throw error
        }
    }

    fun setRemoteAnswer(sdp: String) {
        val peer = peerConnection ?: return
        peer.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingCandidates(peer)
                    onStatus("answer-applied")
                }

                override fun onSetFailure(error: String?) {
                    onStatus("answer-failed:${error.orEmpty()}")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        val peer = peerConnection ?: return
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates += iceCandidate
            return
        }
        peer.addIceCandidate(iceCandidate)
    }

    fun resendOffer() {
        offerPayload?.let(onLocalSignal)
    }

    /**
     * 会话是否还该占着这个位置。
     *
     * 曾经的问题：A 端异常断开（切网、锁屏、进程被杀）后 B 既没收到 session-end，
     * PeerConnection 也不会主动结束，于是 B 永远认为「忙」，后续每次连接都被
     * DEVICE_BUSY 顶掉——从 A 看就是「怎么都连不上」，而 B 一切正常。
     *
     * 判据：已连成功过就还活着；或者刚开始不久（还在等 answer）。
     */
    fun isAlive(): Boolean {
        if (closed) {
            return false
        }
        if (connectedOnce) {
            return lastPeerState != PeerConnection.PeerConnectionState.FAILED &&
                lastPeerState != PeerConnection.PeerConnectionState.CLOSED
        }
        return SystemClock.elapsedRealtime() - startedAt < ANSWER_TIMEOUT_MS
    }

    /** 对端迟迟不应答就直接收掉，别让它把后续连接挡在门外。 */
    private fun armAnswerTimeout() {
        val timeout = Runnable {
            if (!closed && !connectedOnce) {
                onStatus("answer-timeout")
                close("answer-timeout")
            }
        }
        answerTimeout = timeout
        mainHandler.postDelayed(timeout, ANSWER_TIMEOUT_MS)
    }

    private fun cancelTimers() {
        answerTimeout?.let(mainHandler::removeCallbacks)
        dropTimeout?.let(mainHandler::removeCallbacks)
        answerTimeout = null
        dropTimeout = null
    }

    fun close(reason: String) {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
        }

        cancelTimers()
        runCatching {
            controlChannel?.unregisterObserver()
            controlChannel?.close()
        }
        controlChannel = null

        runCatching {
            peerConnection?.close()
            peerConnection?.dispose()
        }
        peerConnection = null

        // 采集管线常驻：这里不停采集，也不销毁投影。
        // Android 14 一旦释放全部虚拟屏就会终止投影，下次连接又得让用户重新授权。
        ScreenAwake.release()
        pendingRemoteCandidates.clear()
        offerPayload = null
        onStatus("closed:$reason")
        onSessionClosed(reason)
    }

    /**
     * 限制发送码率并指定降级策略。
     *
     * MAINTAIN_RESOLUTION 是远控的正确取舍：带宽抖动时**保住分辨率、掉帧率**
     * —— 文字糊了就没法操作，卡一点只是慢。之前的 MAINTAIN_FRAMERATE 正好
     * 反过来，弱网时画面先糊，用户看到的就是「不清晰」。
     *
     * 3.5 Mbps 配 720x1600@20 的屏幕内容：静态界面用不到，滑动/滚动时会顶到；
     * WiFi 下绰绰有余，4G 偶尔顶格也远比 1.5M 清楚。
     */
    private fun applyVideoSenderLimits(peer: PeerConnection) {
        runCatching {
            val sender = peer.senders.firstOrNull() ?: return
            val params = sender.parameters ?: return
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
                encoding.maxFramerate = MAX_VIDEO_FRAMERATE
            }
            sender.parameters = params
            onStatus("sender-limits:${MAX_VIDEO_BITRATE_BPS / 1000}kbps")
        }.onFailure { onStatus("sender-limits-failed:${it.message.orEmpty()}") }
    }

    private fun createAndSendOffer(peer: PeerConnection) {        peer.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    val localDescription = description ?: return
                    peer.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                val payload = JSONObject()
                                    .put("kind", "webrtc-offer")
                                    .put("sdp", localDescription.description)
                                    .put("screenWidth", captureWidth)
                                    .put("screenHeight", captureHeight)
                                offerPayload = payload
                                onLocalSignal(payload)
                                onStatus("offer-sent")
                            }

                            override fun onSetFailure(error: String?) {
                                onStatus("local-description-failed:${error.orEmpty()}")
                            }
                        },
                        localDescription,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    onStatus("offer-failed:${error.orEmpty()}")
                }
            },
            MediaConstraints(),
        )
    }

    private fun flushPendingCandidates(peer: PeerConnection) {
        val candidates = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        candidates.forEach(peer::addIceCandidate)
    }

    private val peerObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            if (candidate.sdp.isBlank()) {
                return
            }
            onLocalSignal(
                JSONObject()
                    .put("kind", "webrtc-ice")
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid.orEmpty())
                    .put("sdpMLineIndex", candidate.sdpMLineIndex),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) {
            // 本端是 offer 方，自己建 channel；正常情况下这里不该被回调。
            // 真被回调说明对端也建了一条，两条不互通，必须留痕。
            FileLog.append("agent unexpected remote channel label=${channel.label()}")
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            lastPeerState = newState
            onStatus("peer-state:${newState.name}")
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                connectedOnce = true
                onPeerConnected()
                answerTimeout?.let(mainHandler::removeCallbacks)
                answerTimeout = null
                return
            }
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                close("peer-failed")
                return
            }
            // DISCONNECTED 可能是短时抖动（切网、熄屏），给 10 秒自愈窗口再收会话。
            if (newState == PeerConnection.PeerConnectionState.DISCONNECTED && connectedOnce) {
                dropTimeout?.let(mainHandler::removeCallbacks)
                val drop = Runnable {
                    if (!closed && lastPeerState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        close("peer-lost")
                    }
                }
                dropTimeout = drop
                mainHandler.postDelayed(drop, PEER_DROP_TIMEOUT_MS)
            }
        }
    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = controlChannel?.state()?.name.orEmpty()
            FileLog.append("agent dc state=$state")
            onStatus("data-channel:$state")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            // 控制消息走 DataChannel，不走信令，所以「A 点了没反应」时
            // 服务端日志里什么都看不到。这条记录是唯一的到达证明。
            FileLog.append("agent dc <- ${buffer.data.remaining()}B binary=${buffer.binary}")
            if (buffer.binary) {
                return
            }
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val message = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
            mainHandler.post { executeControlMessage(message) }
        }
    }

    private fun executeControlMessage(message: JSONObject) {
        val service = RemoteAccessibilityService.instance
        val keyguard = appContext.getSystemService(android.app.KeyguardManager::class.java)
        // 锁屏状态必须一起记：A 端「滑了没反应」有两种完全不同的原因——
        // 事件没到（这里会缺行），和事件到了但落在错的地方（坐标/锁屏态问题）。
        FileLog.append(
            "control type=${message.optString("type")} accessibility=${service != null} " +
                "locked=${keyguard?.isKeyguardLocked} secure=${keyguard?.isDeviceSecure}",
        )
        if (service == null) {
            sendControlAck(false, "accessibility-not-enabled")
            return
        }

        val success = when (message.optString("type")) {
            "gesture" -> executeGesture(service, message)
            "global" -> when (message.optString("action")) {
                "back" -> service.performGlobalBack()
                "home" -> service.performGlobalHome()
                "recents" -> service.performGlobalRecents()
                else -> false
            }

            "text" -> service.setFocusedText(message.optString("value"))
            "pin" -> executePin(service, message)
            else -> false
        }
        sendControlAck(success, if (success) null else "gesture-failed")
    }

    /**
     * 远程输入锁屏密码。
     *
     * 锁屏上的密码键盘是 **secure window**：无障碍读不到它的节点
     * （`rootInActiveWindow` 拿不到、`findFocus` 返回 null），所以
     * `ACTION_SET_TEXT` 这条路是死的。唯一可行的办法是按坐标点数字键。
     *
     * 键盘是系统标准的 3 列 × 4 行：
     * ```
     *   1  2  3
     *   4  5  6
     *   7  8  9
     *      0  ⌫
     * ```
     * 列中心固定在 1/6、1/2、5/6 屏宽（各家 ROM 一致）；行位置随屏幕高度和
     * ROM 版本变化，所以起止行用比例传进来，A 端可以上下微调。
     */
    private fun executePin(
        service: RemoteAccessibilityService,
        message: JSONObject,
    ): Boolean {
        val value = message.optString("value").filter { it.isDigit() }
        if (value.isEmpty()) {
            FileLog.append("pin: empty")
            return false
        }
        val display = displaySize()
        val width = display.first.toFloat()
        val height = display.second.toFloat()
        val topRatio = message.optDouble("rowTop", DEFAULT_ROW_TOP.toDouble()).toFloat()
        val pitchRatio = message.optDouble("rowPitch", DEFAULT_ROW_PITCH.toDouble()).toFloat()

        val points = value.mapNotNull { digit ->
            val key = PIN_KEYS[digit] ?: return@mapNotNull null
            val x = width * (key.first * 2 + 1) / 6f
            val y = height * (topRatio + key.second * pitchRatio)
            x to y
        }
        FileLog.append(
            "pin: len=${points.size} top=$topRatio pitch=$pitchRatio " +
                "first=(${points.firstOrNull()?.first?.toInt()},${points.firstOrNull()?.second?.toInt()})",
        )
        val total = points.size
        service.tapSequence(points) { completed ->
            // 点完最后一位到 keyguard 消失之间有零点几秒延迟，立刻查会误报「仍锁定」。
            android.os.Handler(appContext.mainLooper).postDelayed(
                {
                    val locked =
                        appContext.getSystemService(android.app.KeyguardManager::class.java)
                            ?.isKeyguardLocked == true
                    FileLog.append("pin result: tapped=$completed/$total locked=$locked")
                    sendPinResult(completed, total, locked)
                },
                PIN_RESULT_DELAY_MS,
            )
        }
        return true
    }

    /** 把盲点结果推回 A：黑屏下用户唯一的判断依据，否则只能反复瞎试。 */
    private fun sendPinResult(
        tapped: Int,
        total: Int,
        locked: Boolean,
    ) {
        val channel = controlChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            return
        }
        val payload = JSONObject()
            .put("type", "pin-result")
            .put("tapped", tapped)
            .put("total", total)
            .put("locked", locked)
        val data = ByteBuffer.wrap(payload.toString().toByteArray(Charsets.UTF_8))
        runCatching { channel.send(DataChannel.Buffer(data, false)) }
    }

    private fun executeGesture(
        service: RemoteAccessibilityService,
        message: JSONObject,
    ): Boolean {
        val display = displaySize()
        val startX = (message.optDouble("startX", 0.0) * display.first).toFloat()
        val startY = (message.optDouble("startY", 0.0) * display.second).toFloat()
        val endX = (message.optDouble("endX", 0.0) * display.first).toFloat()
        val endY = (message.optDouble("endY", 0.0) * display.second).toFloat()
        val durationMs = message.optLong("durationMs", 0L).coerceIn(0L, 5_000L)
        val distance = abs(endX - startX) + abs(endY - startY)
        val touchSlop = 16f * appContext.resources.displayMetrics.density

        return when {
            distance <= touchSlop && durationMs >= 500L -> service.performLongPress(startX, startY, durationMs)
            distance <= touchSlop -> service.performTap(startX, startY)
            else -> service.performSwipe(startX, startY, endX, endY, durationMs.coerceAtLeast(50L))
        }
    }

    private fun sendControlAck(success: Boolean, errorCode: String?) {
        val channel = controlChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            return
        }
        val payload = JSONObject()
            .put("type", "control-ack")
            .put("ok", success)
        if (errorCode != null) {
            payload.put("error", errorCode)
        }
        val data = ByteBuffer.wrap(payload.toString().toByteArray(Charsets.UTF_8))
        channel.send(DataChannel.Buffer(data, false))
    }

    private fun displaySize(): Pair<Int, Int> {
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private companion object {
        /**
         * 720x1600 级别的屏幕内容，静态界面码率很低，滑动时会顶到 2~3M。
         * 1.5M 时代的实际观感是「文字发糊」，这是用户直接报的画质问题。
         */
        const val MAX_VIDEO_BITRATE_BPS = 3_500_000

        /** 与采集帧率保持一致，避免编码器按更高的目标帧率空转。 */
        const val MAX_VIDEO_FRAMERATE = 20

        /** 发出 offer 后等 answer 的上限。 */
        const val ANSWER_TIMEOUT_MS = 20_000L

        /** 已连通的会话掉线后，保留多久再收掉。 */
        const val PEER_DROP_TIMEOUT_MS = 10_000L

        /** 末位密码点完到 keyguard 消失之间的延迟，判定解锁前先等这么久。 */
        const val PIN_RESULT_DELAY_MS = 1_500L

        /**
         * 数字键盘网格：列 0..2、行 0..3。0 在第 4 行中间。
         * 行列都按屏幕比例算，换分辨率不用改。
         */
        val PIN_KEYS: Map<Char, Pair<Int, Int>> = mapOf(
            '1' to (0 to 0), '2' to (1 to 0), '3' to (2 to 0),
            '4' to (0 to 1), '5' to (1 to 1), '6' to (2 to 1),
            '7' to (0 to 2), '8' to (1 to 2), '9' to (2 to 2),
            '0' to (1 to 3),
        )

        /** 第一行数字键的中心，按屏高比例。荣耀 2400 高屏实测约 0.61。 */
        const val DEFAULT_ROW_TOP = 0.59f

        /** 行间距，按屏高比例。 */
        const val DEFAULT_ROW_PITCH = 0.09f
    }
}
