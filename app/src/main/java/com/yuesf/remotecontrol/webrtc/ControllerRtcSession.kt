package com.yuesf.remotecontrol.webrtc

import android.os.Handler
import android.os.Looper
import com.yuesf.remotecontrol.signaling.SignalingRepository
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class ControllerRtcSession(
    private val renderer: SurfaceViewRenderer,
    private val onLocalSignal: (JSONObject) -> Unit,
    private val onStatusChanged: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onStatus: (String) -> Unit = { status ->
        mainHandler.post { onStatusChanged(status) }
    }
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var controlChannel: DataChannel? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    var remoteScreenWidth: Int = 0
        private set

    var remoteScreenHeight: Int = 0
        private set

    @Volatile
    private var closed = false

    fun start() {
        WebRtcEngine.initialize(renderer.context)
        WebRtcEngine.setIceServers(SignalingRepository.iceServers)
        peerConnection = WebRtcEngine.createPeerConnection(peerObserver)
    }

    fun handleSignal(payload: JSONObject) {
        if (closed) {
            return
        }

        when (payload.optString("kind")) {
            "webrtc-offer" -> {
                remoteScreenWidth = payload.optInt("screenWidth")
                remoteScreenHeight = payload.optInt("screenHeight")
                applyRemoteOffer(payload.optString("sdp"))
            }

            "webrtc-ice" -> {
                val candidate = IceCandidate(
                    payload.optString("sdpMid").ifBlank { null },
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate"),
                )
                val peer = peerConnection ?: return
                if (remoteDescriptionSet) {
                    peer.addIceCandidate(candidate)
                } else {
                    pendingRemoteCandidates += candidate
                }
            }

            "session-error" -> {
                onStatus("error:${payload.optString("code", "SESSION_ERROR")}")
            }

            "session-ended" -> {
                onStatus("closed:remote-ended")
            }
        }
    }

    fun sendControl(message: JSONObject): Boolean {
        val channel = controlChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) {
            return false
        }
        val data = ByteBuffer.wrap(message.toString().toByteArray(Charsets.UTF_8))
        return channel.send(DataChannel.Buffer(data, false))
    }

    fun close() {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
        }

        val track = remoteVideoTrack
        if (track != null) {
            runCatching { track.removeSink(renderer) }
        }
        remoteVideoTrack = null

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
        pendingRemoteCandidates.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun applyRemoteOffer(sdp: String) {
        if (sdp.isBlank()) {
            onStatus("error:EMPTY_OFFER")
            return
        }

        val peer = peerConnection ?: return
        peer.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    flushPendingCandidates(peer)
                    createAnswer(peer)
                }

                override fun onSetFailure(error: String?) {
                    onStatus("error:${error.orEmpty()}")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswer(peer: PeerConnection) {
        peer.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    val localDescription = description ?: return
                    peer.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onLocalSignal(
                                    JSONObject()
                                        .put("kind", "webrtc-answer")
                                        .put("sdp", localDescription.description),
                                )
                                onStatus("answer-sent")
                            }

                            override fun onSetFailure(error: String?) {
                                onStatus("error:${error.orEmpty()}")
                            }
                        },
                        localDescription,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    onStatus("error:${error.orEmpty()}")
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
            controlChannel = channel
            channel.registerObserver(dataChannelObserver)
            mainHandler.post { onStatus("data-channel:${channel.state().name}") }
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            attachVideoTrack(receiver.track() as? VideoTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            attachVideoTrack(transceiver.receiver.track() as? VideoTrack)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            mainHandler.post { onStatus("peer-state:${newState.name}") }
        }
    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            val state = controlChannel?.state()?.name.orEmpty()
            mainHandler.post {
                if (state == DataChannel.State.OPEN.name) {
                    onStatus("connected")
                } else {
                    onStatus("data-channel:$state")
                }
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) = Unit
    }

    private fun attachVideoTrack(track: VideoTrack?) {
        if (track == null || remoteVideoTrack === track) {
            return
        }
        remoteVideoTrack?.let { runCatching { it.removeSink(renderer) } }
        remoteVideoTrack = track
        mainHandler.post {
            track.addSink(renderer)
            onStatus("video-track")
        }
    }
}
