package com.yuesf.remotecontrol.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.webrtc.CapturerObserver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
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
 *
 * ## 省电：两档采集规格
 *
 * 常驻不等于满负荷。`ScreenCapturerAndroid.stopCapture()` 会
 * `virtualDisplay.release()` 并且 `mediaProjection.stop()`（已反编译确认），
 * 一旦停采集授权就废了，下次连接又得让用户重新点系统框。所以**不能停**，
 * 只能在「继续出帧」的前提下把规格压到最低：
 *
 * - 空闲档 [IDLE_FPS] / [IDLE_MAX_DIMENSION]：没有会话时，屏幕内容变了也
 *   只按每秒 1 帧往 WebRTC 送，虚拟屏缩到 360 宽。彻底避免「手机亮着、
 *   用户在刷视频，后台 60fps 满分辨率在合成+拷贝却没人看」。
 * - 会话档 [SESSION_FPS] / 全尺寸：真正连接时才恢复，出帧质量不受影响。
 *
 * 限帧是自己做的：webrtc 的 `changeCaptureFormat(w,h,fps)` 只 resize 虚拟屏，
 * fps 参数被忽略（同样反编译确认），所以必须在 [CapturerObserver] 上包一层丢帧。
 */
class ScreenCaptureHolder {
    private var capturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null

    /** 屏幕原始尺寸，会话档用这个。 */
    private var fullWidth = 720
    private var fullHeight = 1280

    /** 虚拟屏当前尺寸，空闲档会缩到 [IDLE_MAX_DIMENSION]。 */
    private var captureWidth = 720
    private var captureHeight = 1280

    private var capturing = false

    /** 目标出帧率。空闲 1fps，会话 12fps。 */
    @Volatile
    private var targetFps = IDLE_FPS

    /** 上一次真正送给 WebRTC 的帧时间戳，用于限帧。 */
    private var lastDeliveredAt = 0L

    @Volatile
    private var idle = true

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
     * 限帧闸门。
     *
     * 虚拟屏的帧是 SurfaceFlinger 按合成结果推上来的，屏幕变化越频繁帧越多
     * （刷短视频时能到 60fps），`startCapture` 的 fps 参数管不住它。
     * 这里在进 WebRTC 之前按目标帧率丢弃多余帧，把解码/编码/网络三端完全摘掉。
     *
     * **丢弃时绝对不能自己 release**：webrtc 的 `SurfaceTextureHelper.tryDeliverTextureFrame`
     * 在 `onFrameCaptured` 返回后会自己对同一个 VideoFrame 调 `release()`
     * （SurfaceTextureHelper.java:371），谁先释放谁就把进程带走——
     * 现象是 `IllegalStateException: release() called on an object with refcount < 1`
     * 直接闪退。所以这里只做「不转发」，所有权仍然留在 webrtc 手里。
     */
    private val throttlingObserver = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) {
            source?.capturerObserver?.onCapturerStarted(success)
        }

        override fun onCapturerStopped() {
            source?.capturerObserver?.onCapturerStopped()
        }

        override fun onFrameCaptured(frame: VideoFrame) {
            val fps = targetFps.coerceAtLeast(1)
            val now = SystemClock.elapsedRealtime()
            if (now - lastDeliveredAt < 1000L / fps) {
                return
            }
            lastDeliveredAt = now
            source?.capturerObserver?.onFrameCaptured(frame)
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
            fullWidth = dimensions.first
            fullHeight = dimensions.second
            captureWidth = fullWidth
            captureHeight = fullHeight

            val newCapturer = ScreenCapturerAndroid(projectionData, projectionCallback)
            val newTextureHelper = SurfaceTextureHelper.create(
                "ScreenCapture",
                WebRtcEngine.eglBase.eglBaseContext,
            )
            val newSource = WebRtcEngine.createVideoSource(true)
            val newTrack = WebRtcEngine.createVideoTrack(SCREEN_TRACK_ID, newSource)

            // 先落 source，throttlingObserver 要往里转发。
            source = newSource
            newCapturer.initialize(newTextureHelper, context, throttlingObserver)

            capturer = newCapturer
            textureHelper = newTextureHelper
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

    /** 对端只关心画面宽高比，尺寸始终报屏幕原始尺寸，不受空闲档影响。 */
    @Synchronized
    fun dimensions(): Pair<Int, Int> = fullWidth to fullHeight

    /** 诊断面板用：当前采集档位。 */
    @Synchronized
    fun profileLabel(): String =
        "${if (idle) "空闲" else "会话"} ${captureWidth}x$captureHeight@${targetFps}fps"

    @Synchronized
    fun startCapture() {
        val active = capturer ?: return
        if (capturing) {
            return
        }
        runCatching {
            active.startCapture(captureWidth, captureHeight, targetFps)
            capturing = true
            Log.i(TAG, "capture started at ${captureWidth}x$captureHeight@${targetFps}fps")
        }.onFailure { Log.e(TAG, "startCapture failed", it) }
    }

    /**
     * 切到会话档：全尺寸 + [SESSION_FPS]。
     *
     * 必须在建 PeerConnection、发 offer 之前调用。`changeCaptureFormat` 内部是
     * `invokeAtFrontUninterruptibly`，返回时虚拟屏已经 resize 完，所以紧接着
     * `startCapture` / 出帧拿到的一定是新尺寸，不会有「第一帧还是小尺寸」的问题。
     */
    @Synchronized
    fun enterSessionMode() {
        idle = false
        targetFps = SESSION_FPS
        applyFormat(fullWidth, fullHeight)
    }

    /**
     * 切到空闲档：360 宽 + 1fps。会话结束、没有任何会话时调用。
     *
     * 只 resize、不停采集：一旦 stopCapture，MediaProjection 就被系统收回，
     * 下次连接要用户重新授权，代价远大于省下的电。
     */
    @Synchronized
    fun enterIdleMode() {
        idle = true
        targetFps = IDLE_FPS
        val scale = min(1f, IDLE_MAX_DIMENSION / maxOf(fullWidth, fullHeight).toFloat())
        val width = ((fullWidth * scale).toInt() and -2).coerceAtLeast(2)
        val height = ((fullHeight * scale).toInt() and -2).coerceAtLeast(2)
        applyFormat(width, height)
    }

    private fun applyFormat(width: Int, height: Int) {
        if (width == captureWidth && height == captureHeight) {
            return
        }
        val active = capturer ?: return
        if (!capturing) {
            // 还没开始出帧：直接改记录值，随后的 startCapture 会用新尺寸。
            captureWidth = width
            captureHeight = height
            return
        }
        runCatching { active.changeCaptureFormat(width, height, targetFps) }
            .onSuccess {
                captureWidth = width
                captureHeight = height
                Log.i(TAG, "capture format -> ${width}x$height (idle=$idle)")
            }
            .onFailure {
                // 个别 ROM 的 resize 会失败：此时只丢帧降功耗，尺寸维持原样，不影响连接。
                Log.w(TAG, "changeCaptureFormat failed, keep ${captureWidth}x$captureHeight", it)
            }
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
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        // 清晰度的关键在**短边**：竖屏手机的文字沿宽度方向排布，
        // 短边缩到 486（旧策略 1080 上限对 1080x2400 的结果）文字必然糊。
        // 保证短边 ≥ [MIN_SHORT_EDGE]，同时长边不超过 [MAX_LONG_EDGE] 控住编码负载。
        val scale = min(
            MAX_LONG_EDGE / maxOf(w, h).toFloat(),
            MIN_SHORT_EDGE / minOf(w, h).toFloat(),
        ).coerceAtMost(1f)
        val width = ((w * scale).toInt() and -2).coerceAtLeast(2)
        val height = ((h * scale).toInt() and -2).coerceAtLeast(2)
        return width to height
    }

    companion object {
        private const val TAG = "ScreenCaptureHolder"
        private const val SCREEN_TRACK_ID = "screen0"

        /**
         * 会话档帧率：20fps 下滑动/滚动基本无拖影感，编码功耗仍在可控范围
         * （屏幕内容变化才真正出帧，静态界面编码器负载远低于标称值）。
         */
        private const val SESSION_FPS = 20

        /** 会话档长边上限：1080x2400 → 720x1600，1200x2670 → 720x1602。 */
        private const val MAX_LONG_EDGE = 1600f

        /** 会话档短边下限：保证竖屏宽度不低于 720，文字才清晰。 */
        private const val MIN_SHORT_EDGE = 720f

        /**
         * 空闲档：1fps 只是为了让投影「看起来还在投」，够它不被系统回收；
         * 真有人连进来时立刻切成会话档。
         */
        private const val IDLE_FPS = 1

        /** 空闲档最长边。360x800 左右，足够让投影保持「真在投屏」，肉眼几乎看不出来。 */
        private const val IDLE_MAX_DIMENSION = 360f
    }
}
