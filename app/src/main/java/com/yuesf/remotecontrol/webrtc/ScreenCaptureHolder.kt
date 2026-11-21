package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.min

/**
 * 常驻的屏幕采集器：授权一次，进程存活期间一直有效。
 *
 * 为什么必须这样：Android 14 起 MediaProjection 的授权凭证**只能使用一次**，
 * 拿同一个 Intent 第二次调用 getMediaProjection 会抛 SecurityException。
 * 早先每次会话都新建 ScreenCapturerAndroid(projectionData)，
 * 结果就是第一次能连、之后每次都被要求重新手动授权。
 *
 * 正确做法是授权后立刻建好 capturer / source / track 并持有，
 * 后续每次会话只复用同一个 [VideoTrack]，开关采集即可。
 */
class ScreenCaptureHolder {
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null

    private var captureWidth = 720
    private var captureHeight = 1280
    private var capturing = false

    /** 用户在系统层（或系统策略）终止了投屏，需要通知调用方结束会话。 */
    @Volatile
    var onProjectionStopped: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "projection stopped by system")
            release()
            onProjectionStopped?.invoke()
        }
    }

    /**
     * 用一次授权凭证建立常驻采集管线。重复调用是安全的（已就绪时直接返回）。
     * 注意：同一个 [projectionData] 只能成功调用一次，之后必须复用已建好的实例。
     */
    @Synchronized
    fun prepare(context: Context, projectionData: Intent): Boolean {
        if (track != null) {
            return true
        }
        return runCatching {
            WebRtcEngine.initialize(context)
            val dimensions = captureSize(context)
            captureWidth = dimensions.first
            captureHeight = dimensions.second

            val newCapturer = ScreenCapturerAndroid(projectionData, projectionCallback)
            val newTextureHelper = SurfaceTextureHelper.create(
                "ScreenCapture",
                WebRtcEngine.eglBase.eglBaseContext,
            )
            val newSource = WebRtcEngine.createVideoSource(true)
            val newTrack = WebRtcEngine.createVideoTrack(SCREEN_TRACK_ID, newSource)

            newCapturer.initialize(newTextureHelper, context, newSource.capturerObserver)

            capturer = newCapturer
            textureHelper = newTextureHelper
            source = newSource
            track = newTrack
            Log.i(TAG, "capture prepared at ${captureWidth}x$captureHeight")
            true
        }.onFailure {
            Log.e(TAG, "unable to prepare screen capture", it)
            release()
        }.getOrDefault(false)
    }

    @Synchronized
    fun isReady(): Boolean = track != null

    @Synchronized
    fun videoTrack(): VideoTrack? = track

    @Synchronized
    fun dimensions(): Pair<Int, Int> = captureWidth to captureHeight

    @Synchronized
    fun startCapture() {
        val active = capturer ?: return
        if (capturing) {
            return
        }
        runCatching {
            active.startCapture(captureWidth, captureHeight, CAPTURE_FRAMES_PER_SECOND)
            capturing = true
            Log.i(TAG, "capture started")
        }.onFailure { Log.e(TAG, "startCapture failed", it) }
    }

    @Synchronized
    fun stopCapture() {
        val active = capturer ?: return
        if (!capturing) {
            return
        }
        runCatching { active.stopCapture() }
        capturing = false
        Log.i(TAG, "capture stopped")
    }

    @Synchronized
    fun release() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        capturing = false

        runCatching { track?.dispose() }
        track = null

        runCatching { source?.dispose() }
        source = null

        runCatching { textureHelper?.dispose() }
        textureHelper = null
    }

    private fun captureSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val scale = min(1f, MAX_CAPTURE_DIMENSION / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = ((metrics.widthPixels * scale).toInt() and -2).coerceAtLeast(2)
        val height = ((metrics.heightPixels * scale).toInt() and -2).coerceAtLeast(2)
        return width to height
    }

    companion object {
        private const val TAG = "ScreenCaptureHolder"
        private const val SCREEN_TRACK_ID = "screen0"
        private const val CAPTURE_FRAMES_PER_SECOND = 15
        private const val MAX_CAPTURE_DIMENSION = 1280f
    }
}
