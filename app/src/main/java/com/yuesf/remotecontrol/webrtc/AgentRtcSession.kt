package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

class AgentRtcSession(
    context: Context,
    private val projectionData: Intent,
    private val onLocalSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onSessionClosed: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var controlChannel: DataChannel? = null

    private var captureWidth = 720
    private var captureHeight = 1280
    private var offerPayload: JSONObject? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var closed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                if (!closed) {
                    close("screen-capture-stopped")
                }
            }
        }
    }

    fun start() {
        try {
            WebRtcEngine.initialize(appContext)
            val dimensions = captureSize()
            captureWidth = dimensions.first
            captureHeight = dimensions.second

            val peer = WebRtcEngine.createPeerConnection(peerObserver).also { peerConnection = it }

            val capturer = ScreenCapturerAndroid(projectionData, projectionCallback)
            val textureHelper = SurfaceTextureHelper.create(
                "ScreenCapture",
                WebRtcEngine.eglBase.eglBaseContext,
            )
            val source = WebRtcEngine.createVideoSource(true)
            val track = WebRtcEngine.createVideoTrack("screen0", source)

            capturer.initialize(textureHelper, appContext, source.capturerObserver)
            peer.addTrack(track, listOf("screen"))

            videoCapturer = capturer
            surfaceTextureHelper = textureHelper
            videoSource = source
            videoTrack = track

            capturer.startCapture(captureWidth, captureHeight, CAPTURE_FRAMES_PER_SECOND)

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

        runCatching { videoTrack?.dispose() }
        videoTrack = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        offerPayload = null
        onStatus("closed:$reason")
        onSessionClosed(reason)
    }

    private fun createAndSendOffer(peer: PeerConnection) {
        peer.createOffer(
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

    private fun captureSize(): Pair<Int, Int> {
        val (displayWidth, displayHeight) = displaySize()
        val scale = min(1f, MAX_CAPTURE_DIMENSION / maxOf(displayWidth, displayHeight).toFloat())
        val width = ((displayWidth * scale).toInt() and -2).coerceAtLeast(2)
        val height = ((displayHeight * scale).toInt() and -2).coerceAtLeast(2)
        return width to height
    }

    companion object {
        private const val CAPTURE_FRAMES_PER_SECOND = 15
        private const val MAX_CAPTURE_DIMENSION = 1280f
    }
}
