package com.yuesf.remotecontrol.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

object WebRtcEngine {
    private var initialized = false

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

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection {
        check(initialized) { "WebRtcEngine must be initialized before creating a peer connection" }

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
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
