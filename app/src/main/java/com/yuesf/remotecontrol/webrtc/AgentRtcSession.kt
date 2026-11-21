package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
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
            // 采集管线是常驻的，这里只是开始出帧；停会话时只停采集，不销毁管线。
            capture.startCapture()

            val channel = peer.createDataChannel("control", DataChannel.Init().apply { ordered = true })
            channel?.registerObserver(dataChannelObserver)
            controlChannel = channel

            createAndSendOffer(peer)
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

    fun close(reason: String) {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
        }

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
        pendingRemoteCandidates.clear()
        offerPayload = null
        onStatus("closed:$reason")
        onSessionClosed(reason)
    }

    /**
     * 限制发送码率并指定降级策略。
     *
     * 屏幕共享的默认码率上限很高，1080 宽的静态界面也能跑到好几 Mbps，
     * 编码器与射频都被白白占着，机身发烫、电量掉得快。1.5 Mbps 对手机界面
     * 足够清晰；带宽不足时优先保住帧率、牺牲分辨率，操作手感不会变卡。
     */
    private fun applyVideoSenderLimits(peer: PeerConnection) {
        runCatching {
            val sender = peer.senders.firstOrNull() ?: return
            val params = sender.parameters ?: return
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
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

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            onStatus("peer-state:${newState.name}")
        }
    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            onStatus("data-channel:${controlChannel?.state()?.name.orEmpty()}")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
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
            else -> false
        }
        sendControlAck(success, if (success) null else "gesture-failed")
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
        /** 1080 宽的手机界面在 1.5 Mbps 下已经足够清晰，高于此只会换来电量与发热。 */
        const val MAX_VIDEO_BITRATE_BPS = 1_500_000

        /** 与采集帧率保持一致，避免编码器按更高的目标帧率空转。 */
        const val MAX_VIDEO_FRAMERATE = 12
    }
}
