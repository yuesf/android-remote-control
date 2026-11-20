package com.yuesf.remotecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class RemoteAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, 50L)
    }

    fun performLongPress(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 0L, durationMs.coerceIn(300L, 2_000L))
    }

    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(path, 0L, durationMs.coerceIn(50L, 5_000L))
    }

    fun performGlobalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performGlobalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performGlobalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun setFocusedText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun requestScreenshot(executor: Executor, callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                    }
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            },
        )
    }

    private fun dispatch(path: Path, startTimeMs: Long, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, startTimeMs, durationMs),
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 读取系统实际下发的无障碍能力，并在需要时真实派发一次手势做实测。
     * 部分厂商 ROM 会忽略 canPerformGestures，仅凭 manifest 无法判断，只能靠实派验证。
     */
    fun evaluateCapabilities(testGesture: Boolean): CapabilityReport {
        val capabilities = serviceInfo?.capabilities ?: 0
        val canPerformGestures =
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
        val canTakeScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0

        val gestureDispatch = if (!testGesture) {
            null
        } else {
            val metrics = resources.displayMetrics
            // 屏幕左边缘 1ms 轻点，副作用最小，仅用于确认手势通道是否放行。
            performTap(2f, metrics.heightPixels / 2f)
        }

        return CapabilityReport(
            connected = true,
            capabilitiesRaw = "0x%X (%d)".format(capabilities, capabilities),
            canRetrieveWindowContent =
                capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
            canPerformGestures = canPerformGestures,
            canTakeScreenshot = canTakeScreenshot,
            gestureDispatchSucceeded = gestureDispatch,
        )
    }

    data class CapabilityReport(
        val connected: Boolean,
        val capabilitiesRaw: String,
        val canRetrieveWindowContent: Boolean,
        val canPerformGestures: Boolean,
        val canTakeScreenshot: Boolean,
        val gestureDispatchSucceeded: Boolean?,
    ) {
        val gesturesUsable: Boolean
            get() = gestureDispatchSucceeded == true || canPerformGestures
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
