package com.yuesf.remotecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class RemoteAccessibilityService : AccessibilityService() {
    /** 坐标推算的重试次数。点错位置可能落到「拒绝」上，不能无限试。 */
    private var geometryAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 系统授权框的「允许」按钮被标记为 importantForAccessibility=no，
        // 默认视图树里根本看不到它（只能看到「拒绝」和标题）。
        // uiautomator dump 之所以能看到，是因为它默认包含不重要视图。
        // 这里补上同一个 flag，否则永远找不到确认按钮。
        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            Log.i(TAG, "a11y flags=0x${serviceInfo?.flags?.toString(16)}")
        }.onFailure { Log.w(TAG, "unable to update service info", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !CaptureAutoApprove.isArmed()) {
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> autoApproveCaptureDialog()

            else -> Unit
        }
    }

    /**
     * 自动点掉系统屏幕采集授权框。
     *
     * 顺序不能反：**先确认这是授权框**（窗口里必须出现「投屏 / 屏幕共享」这类字样），
     * 再去找确认按钮。直接按文案点「开始」「允许」会误伤别的应用弹窗。
     *
     * Android 14 上授权分两步：先选共享范围（单个应用 / 整个屏幕），再确认。
     * 所以若确认按钮点不到，就先把范围切到「整个屏幕」，等下一次窗口事件再点确认。
     */
    private fun autoApproveCaptureDialog() {
        val root = rootInActiveWindow ?: return
        // 第二道闸：授权框属于系统（package 是 android 或 systemui）。
        // 我们自己 App 的界面即使文案撞上了也不该被代点，直接挡掉。
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && !pkg.contains("systemui") && pkg != "android") {
            return
        }
        val labels = collectLabels(root)
        if (labels.isEmpty()) {
            return
        }
        val looksLikeCaptureDialog = labels.any { label ->
            CaptureAutoApprove.dialogHints.any { hint -> label.contains(hint, ignoreCase = true) }
        }
        if (!looksLikeCaptureDialog) {
            return
        }
        // 第三道闸：必须能找到「禁止/拒绝」这个锚点，否则宁可不点。
        if (findDenyNode(root) == null) {
            Log.w(TAG, "capture dialog detected but no deny anchor: $labels")
            return
        }
        Log.i(TAG, "capture consent detected: $labels")

        if (clickLabels(root, CaptureAutoApprove.approveLabels)) {
            Log.i(TAG, "consent clicked via node")
            CaptureAutoApprove.disarm()
            return
        }
        if (clickLabels(root, CaptureAutoApprove.wholeScreenLabels)) {
            Log.i(TAG, "switched to whole-screen mode")
            return
        }
        // 走到这里说明确认按钮确实不在视图树里（系统把它标成 importantForAccessibility=no），
        // 只能用「拒绝」按钮的几何位置反推确认按钮落点。
        if (tapApprovalByGeometry(root)) {
            return
        }
        Log.w(TAG, "consent button not found among: $labels")
    }

    /**
     * 确认按钮被系统从视图树里摘掉了，但按钮栏是标准的右对齐 AlertDialog：
     * 「拒绝」在左、确认在右，两者等高、同一条基线。
     * 拿到「拒绝」的矩形与按钮栏的矩形，就能把确认按钮的中心算出来再用手势点。
     *
     * 之所以能这么干：无障碍手势是「注入触摸事件」，不依赖节点可见性。
     * 这是绕过 importantForAccessibility 过滤的唯一途径。
     */
    private fun tapApprovalByGeometry(root: AccessibilityNodeInfo): Boolean {
        if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
            Log.w(TAG, "geometry attempts exhausted")
            CaptureAutoApprove.disarm()
            return false
        }
        val denyNode = findDenyNode(root) ?: return false
        val denyRect = Rect().also(denyNode::getBoundsInScreen)
        if (denyRect.isEmpty) {
            return false
        }

        // 按钮栏：从「拒绝」往上找第一个明显更宽的祖先，拿不到就退化成拒绝按钮本身。
        val barRect = Rect()
        var parent = denyNode.parent
        var depth = 0
        while (parent != null && depth < MAX_PARENT_DEPTH) {
            val candidate = Rect()
            parent.getBoundsInScreen(candidate)
            if (candidate.width() > denyRect.width() * 1.5f) {
                barRect.set(candidate)
                break
            }
            parent = parent.parent
            depth += 1
        }
        if (barRect.isEmpty) {
            barRect.set(denyRect)
        }

        // 左右内边距通常对称，用左侧留白推出右侧留白。
        val inset = (denyRect.left - barRect.left).coerceAtLeast(0)
        val left = denyRect.right
        val right = (barRect.right - inset).coerceAtLeast(denyRect.right)
        val width = if (right - left > 0) right - left else denyRect.width()
        val cx = left + width / 2f
        val cy = denyRect.centerY().toFloat()

        geometryAttempts += 1
        Log.i(
            TAG,
            "geometry tap ($cx,$cy) deny=$denyRect bar=$barRect attempt=$geometryAttempts",
        )
        val ok = performTap(cx, cy)
        if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
            CaptureAutoApprove.disarm()
        }
        return ok
    }

    /**
     * 找「拒绝/禁止」按钮，作为反推确认按钮的锚点。
     *
     * 优先按系统的按钮 id：标准 AlertDialog 里 button1 是确认、button2/button3 是取消。
     * 这条路径比文案稳，不受 ROM 改词影响；文案匹配只作为兜底。
     */
    private fun findDenyNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in DENY_VIEW_IDS) {
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId(id)
            }.getOrNull().orEmpty()
            val hit = nodes.firstOrNull { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                !rect.isEmpty
            }
            if (hit != null) {
                Log.i(TAG, "deny anchor by id $id")
                return hit
            }
        }
        return findSmallestLabeledNode(root, CaptureAutoApprove.denyLabels)
    }

    /** 取命中文案的节点中宽度最小的那个，避免落到包住整行的容器上。 */
    private fun findSmallestLabeledNode(
        root: AccessibilityNodeInfo,
        labels: List<String>,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestWidth = Int.MAX_VALUE
        walk(root) { node ->
            val label = labelOf(node)
            if (label.isNotEmpty() && labels.any { label.equals(it, ignoreCase = true) }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.width() < bestWidth) {
                    bestWidth = rect.width()
                    best = node
                }
            }
        }
        return best
    }

    fun resetGeometryAttempts() {
        geometryAttempts = 0
    }

    /**
     * 按文案点按钮。
     *
     * 不能只遍历节点找 `text == "允许" 且 isClickable`：系统对话框的按钮
     * 普遍是「不可点的 TextView + 可点的父容器」两层结构，直接找文本节点会漏。
     * 所以先用系统的文本索引拿到候选节点，再向上找第一个可点的祖先。
     */
    private fun clickLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        // 第一优先：标准 AlertDialog 的确认按钮固定是 android:id/button1。
        // 文本索引在这类系统对话框上并不可靠——实测 findAccessibilityNodeInfosByText("允许")
        // 只返回标题 TextView，按钮本身没有进索引。
        val byId = runCatching {
            root.findAccessibilityNodeInfosByViewId("android:id/button1")
        }.getOrNull().orEmpty()
        Log.i(
            TAG,
            "byViewId button1 -> ${byId.size}: " +
                byId.joinToString { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    "${node.text}|click=${node.isClickable}|$rect"
                },
        )
        for (node in byId) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        for (label in labels) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            Log.i(
                TAG,
                "find '$label' -> ${matches?.size ?: -1}: " +
                    matches?.joinToString { "${it.className}|${it.text}|click=${it.isClickable}" },
            )
            if (matches.isNullOrEmpty()) {
                continue
            }
            for (node in matches) {
                if (!labelOf(node).equals(label, ignoreCase = true)) {
                    continue
                }
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < MAX_PARENT_DEPTH) {
                    if (parent.isClickable &&
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        return true
                    }
                    parent = parent.parent
                    depth += 1
                }
            }
        }
        return clickFirstNode(root) { labelOf(it) in labels }
    }

    private fun collectLabels(root: AccessibilityNodeInfo): List<String> = buildList {
        walk(root) { node ->
            labelOf(node).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private inline fun clickFirstNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        var clicked = false
        walk(root) { node ->
            if (!clicked && node.isClickable && predicate(node)) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return clicked
    }

    /**
     * 广度优先遍历，带数量上限。
     * 用 BFS 而不是 DFS：按钮一般在浅层，深度优先容易在深子树里耗尽预算，
     * 实测系统对话框就出现过「拒绝」找得到、「允许」找不到的情况。
     */
    private inline fun walk(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_WALK_NODES) {
            visited += 1
            val node = queue.removeFirst()
            action(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        return text.ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
    }

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
        private const val TAG = "A11yCapture"
        private const val MAX_WALK_NODES = 1500
        private const val MAX_PARENT_DEPTH = 6
        private const val MAX_GEOMETRY_ATTEMPTS = 3

        /** button1 是确认；取消/拒绝在部分 ROM 是 button2，三按钮布局里是 button3。 */
        private val DENY_VIEW_IDS = listOf("android:id/button3", "android:id/button2")

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
