package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.util.Log
import com.yuesf.remotecontrol.signaling.IceServerSpec
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

object WebRtcEngine {
    private const val TAG = "WebRtcEngine"
    private var initialized = false

    @Volatile
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    lateinit var eglBase: EglBase
        private set

    private lateinit var factory: PeerConnectionFactory

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) {
            return
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        eglBase = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        initialized = true
    }

    fun createVideoSource(isScreencast: Boolean): VideoSource {
        check(initialized) { "WebRtcEngine must be initialized before creating a video source" }
        return factory.createVideoSource(isScreencast)
    }

    fun createVideoTrack(id: String, source: VideoSource): VideoTrack {
        check(initialized) { "WebRtcEngine must be initialized before creating a video track" }
        return factory.createVideoTrack(id, source)
    }

    /**
     * 服务端 ready 消息下发的 STUN/TURN。为空时退化为局域网 host candidate，行为与之前一致。
     */
    fun setIceServers(specs: List<IceServerSpec>) {
        iceServers = specs.flatMap { spec ->
            spec.urls.mapNotNull { url ->
                runCatching {
                    PeerConnection.IceServer.builder(url)
                        .apply {
                            spec.username?.takeIf { it.isNotBlank() }?.let(::setUsername)
                            spec.credential?.takeIf { it.isNotBlank() }?.let(::setPassword)
                        }
                        .createIceServer()
                }.onFailure {
                    Log.w(TAG, "Ignoring invalid ICE server url: $url", it)
                }.getOrNull()
            }
        }
        Log.i(TAG, "ICE servers configured: ${iceServers.size}")
    }

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection {
        check(initialized) { "WebRtcEngine must be initialized before creating a peer connection" }

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return checkNotNull(factory.createPeerConnection(configuration, observer)) {
            "Unable to create a peer connection"
        }
    }
}
