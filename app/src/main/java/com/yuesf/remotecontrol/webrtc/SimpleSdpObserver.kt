package com.yuesf.remotecontrol.webrtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit

    override fun onSetSuccess() = Unit

    override fun onCreateFailure(error: String?) = Unit

    override fun onSetFailure(error: String?) = Unit
}
