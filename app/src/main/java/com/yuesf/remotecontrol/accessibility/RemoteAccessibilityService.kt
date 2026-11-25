package com.yuesf.remotecontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.R
import com.yuesf.remotecontrol.debug.FileLog
import java.util.concurrent.Executor
import kotlin.math.roundToInt

class RemoteAccessibilityService : AccessibilityService() {
    /** 坐标推算的重试次数。点错位置可能落到「拒绝」上，不能无限试。 */
    private var geometryAttempts = 0

    /** 上一次记录手势注入失败的时间，用于日志节流。 */
    private var lastGestureFailureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // flagIncludeNotImportantViews 已经在 res/xml/accessibility_service_config.xml 里声明，
        // 这里**不要**再去动态改 serviceInfo.flags：
        //
        // 1. 运行时能塞进去，但系统不认。Java 侧的
        //    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 常量是 0x100，
        //    而 XML 里 `flagIncludeNotImportantViews` 这个名字对应的编码是 **0x02**
        //    （见 SDK 的 attrs.xml）。两套编码不同，动态塞 0x100 等于塞了个没人认的位。
        //    实测 PZ261A / Android 16：改完 dumpsys 出来仍是 0x43（缺 0x02）。
        // 2. 就算编码对了，官方也不建议运行时改 —— 这个 flag 决定视图树是否包含
        //    「不重要」的节点，而视图树是服务连接时就建好的。
        //
        // 效果：系统授权框的「允许」按钮（importantForAccessibility=no）
        // 现在能进视图树了，可以按 android:id/button1 直接点击，不用猜坐标。
        Log.i(TAG, "a11y connected, flags=0x${serviceInfo?.flags?.toString(16)}")

        // 通知被控服务：无障碍现在能代点了，如果采集凭证失效可以让它补发一次授权请求。
        //
        // 为什么需要这个回调：服务启动时 onStartCommand 里会调 maybeAutoRequestCapture()，
        // 但那一刻无障碍往往还没绑定（实测 PZ261A 上服务比无障碍早 17ms），
        // 于是自动请求被 isConnected() 挡掉，只能等下一个 10 分钟保活闹钟。
        // 那意味着「被控端刚重启、没人管」时，要等 10 分钟才拿得到画面。
        //
        // 方向只能是「无障碍主动找服务」：服务先启动时它还没连上，
        // 由服务去注册回调等于注册到空气上。所以回调槽放在 companion 里，
        // 被控服务随时可以注册，已连上的话这里会立刻补调一次。
        onReady?.invoke()
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
        // 授权框可能在 systemui 的**多个窗口**里，rootInActiveWindow 只给一个，
        // 而且未必是那个真正带按钮的窗口（实测 root 落在屏幕外 2113→3150，
        // 而真正的按钮在 y=2261）。
        // 所以遍历所有窗口，逐个找带确认按钮的那个。
        val windows = runCatching {
            windows ?: emptyList()
        }.getOrNull().orEmpty()
        if (windows.isEmpty()) {
            autoApproveInWindow(rootInActiveWindow)
            return
        }
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val rect = Rect().also { root.getBoundsInScreen(it) }
            Log.i(TAG, "window pkg=${root.packageName} id=${window.id} root=$rect")
            // logcat 在部分 ROM 上被过滤（实测 PZ261A），诊断靠文件日志。
            FileLog.append("a11y window pkg=${root.packageName} id=${window.id} root=$rect")
            autoApproveInWindow(root)
        }
    }

    private fun autoApproveInWindow(root: AccessibilityNodeInfo?) {
        root ?: return
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
        Log.i(TAG, "capture consent detected: $labels")
        dumpTreeOnce(root)

        // 打印根节点与窗口信息：诊断「按钮不在树里」时需要知道无障碍
        // 到底绑定到了哪个窗口（可能是另一个显示区域的壳，不是授权框本身）。
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        Log.i(TAG, "consent window pkg=${root.packageName} root=$rootRect")

        // 注意：这里**不**再拿窗口位置做「是否落位」的判断。
        //
        // 之前误判过一次：以为 root 超出屏幕是「滑入动画还没结束」，
        // 于是等窗口稳定再动手。但实测 root 恒为 Rect(58, 2113 - 1142, 3150)，
        // 从头到尾不变 —— 它不是动画，是 systemui 里另一个窗口。
        // 真正的授权框窗口是遍历 windows 之后才找到的（按钮在 y=2261）。
        //
        // 真正需要防的是「按钮还没进视图树」（布局未完成），那个由
        // 「按 id 找不到就重试」来兜，不靠坐标判断。

        // 第一优先：系统固定 id 的确认按钮（android:id/button1）。
        // 它被系统标了 importantForAccessibility=no，只有在
        // flagIncludeNotImportantViews 生效时才会进视图树——这个 flag 必须在
        // res/xml/accessibility_service_config.xml 里声明，运行时改 serviceInfo
        // 会被系统忽略（实测 PZ261A / Android 16：改完 flags 仍是 0x43，缺 0x100）。
        // 命中后直接 performAction，完全不碰坐标。
        if (clickByViewIds(root, CONFIRM_VIEW_IDS)) {
            Log.i(TAG, "consent clicked via button1")
            CaptureAutoApprove.disarm()
            return
        }

        // 第二优先：按文案找确认按钮（部分 ROM 的确认按钮没有标准 id）。
        if (clickLabels(root, CaptureAutoApprove.approveLabels)) {
            Log.i(TAG, "consent clicked via label")
            CaptureAutoApprove.disarm()
            return
        }

        // 第二优先半：结构性点击。「允许」按钮不进视图树（byViewId 0 nodes），
        // 但「拒绝」在树里 —— 按钮栏里「拒绝」的兄弟就是「允许」。
        // ACTION_CLICK 按节点对象派发，不受 bounds 错位影响。
        if (clickApprovalSibling(root)) {
            Log.i(TAG, "consent clicked via deny sibling")
            CaptureAutoApprove.disarm()
            return
        }

        // 第三优先：Android 14 的共享范围选择（单个应用 / 整个屏幕）。
        if (clickLabels(root, CaptureAutoApprove.wholeScreenLabels)) {
            Log.i(TAG, "switched to whole-screen mode")
            return
        }

        // 最后手段：按「拒绝」按钮的几何位置反推确认按钮落点。
        if (tapApprovalByGeometry(root)) {
            return
        }

        // 走到这里说明确认按钮既不在视图树里、几何也推不出来。
        // **什么都不做**：宁可让用户自己按「允许」，也不能瞎点。
        // 之前这里会硬点一个算出来的坐标，在 PZ261A 上那个坐标落在屏幕外，
        // 系统把这一下当成「点了对话框外面」，直接把授权框关掉 →
        // 回执 RESULT_CANCELED → Toast「未授予屏幕采集权限」。
        //
        // 延迟再试一次：按钮没进树也可能是布局还没完成，不一定非是动画问题。
        Log.w(TAG, "consent button not found among: $labels")
        FileLog.append("a11y consent button not found, labels=$labels")
        scheduleRetry()
    }

    /**
     * 按 view id 找节点并点击。
     *
     * 按钮常常是「不可点的 TextView + 可点的父容器」两层结构，
     * 所以节点本身不可点时要向上找第一个可点的祖先。
     */
    private fun clickByViewIds(root: AccessibilityNodeInfo, ids: List<String>): Boolean {
        for (id in ids) {
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId(id)
            }.getOrNull().orEmpty()
            Log.i(
                TAG,
                "byViewId $id -> ${nodes.size}: " +
                    nodes.joinToString { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        "${node.text}|click=${node.isClickable}|$rect"
                    },
            )
            if (id == CONFIRM_VIEW_IDS.first()) {
                FileLog.append("a11y byViewId $id -> ${nodes.size} nodes")
            }
            for (node in nodes) {
                if (clickNodeOrAncestor(node)) {
                    return true
                }
            }
        }
        return false
    }

    /** 点节点自己；不可点就向上找第一个可点的祖先。 */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < MAX_PARENT_DEPTH) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth += 1
        }
        return false
    }

    /**
     * 延迟重试。
     *
     * 系统授权框带滑入动画，动画期间拿到的坐标是错的、按钮也还没进视图树。
     * 动画结束后系统通常会再发一次窗口事件，但不同 ROM 行为不一致，
     * 所以这里额外挂一个延迟重试兜底，别把成败押在「系统一定再发一次」上。
     *
     * 重试前会重新检查布防状态：用户已经手动处理完了就不再多事。
     */
    private fun scheduleRetry() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private val retryRunnable = Runnable {
        if (!CaptureAutoApprove.isArmed()) {
            return@Runnable
        }
        autoApproveCaptureDialog()
    }

    /**
     * 确认按钮被系统从视图树里摘掉了，但按钮栏是标准的右对齐 AlertDialog：
     * 「拒绝」在左、确认在右，两者等高、同一条基线。
     * 拿到「拒绝」的矩形与按钮栏的矩形，就能把确认按钮的中心算出来再用手势点。
     *
     * **这是最后手段，且必须先过屏幕边界校验。**
     *
     * 实测 PZ261A / Android 16：这份 bounds 整体比真实位置偏了 1000+ px
     * （deny 真实在 y=2261，a11y 报 y=3298，而屏幕只有 2670 高）。
     * 拿它推算出来的落点在屏幕外 —— 系统把这一下当成「点了对话框外面」，
     * 直接把授权框关掉，用户看到的就是「未授予屏幕采集权限」。
     *
     * 所以：越界就**不点**，宁可让用户自己按「允许」，也不能把授权框点没了。
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

        // 第一道闸：锚点必须完整落在当前屏幕内。
        // 越界说明这份 bounds 不是当前屏幕的坐标，推算出来的落点必然是废的。
        //
        // 屏幕尺寸要用 Display.getRealSize（**含导航栏的物理尺寸**）：
        // getBoundsInScreen 返回的就是这套物理坐标。
        // 不能用 displayMetrics.heightPixels —— 那是**不含导航栏的应用区高度**
        // （实测 2429 vs 物理 2670），拿它当上界会把正常位置误判成越界。
        val (screenWidth, screenHeight) = realScreenSize()
        if (!isFullyOnScreen(denyRect, screenWidth, screenHeight)) {
            FileLog.append(
                "capture consent: deny anchor off-screen $denyRect " +
                    "(screen ${screenWidth}x$screenHeight), skip tap",
            )
            geometryAttempts += 1
            if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
                CaptureAutoApprove.disarm()
            }
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

        // 第二道闸：落点本身也必须在屏幕内，且不能压在「拒绝」按钮上。
        // 压上去等于亲手把授权点了拒绝 —— 那正是用户看到的报错。
        val tapX = cx.roundToInt()
        val tapY = cy.roundToInt()
        if (tapX < 0 || tapY < 0 ||
            tapX >= screenWidth || tapY >= screenHeight
        ) {
            FileLog.append("capture consent: computed tap ($tapX,$tapY) off-screen, skip")
            geometryAttempts += 1
            if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
                CaptureAutoApprove.disarm()
            }
            return false
        }
        if (denyRect.contains(tapX, tapY)) {
            FileLog.append("capture consent: computed tap ($tapX,$tapY) hits deny button, skip")
            geometryAttempts += 1
            if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
                CaptureAutoApprove.disarm()
            }
            return false
        }

        geometryAttempts += 1
        Log.i(
            TAG,
            "geometry tap ($cx,$cy) deny=$denyRect bar=$barRect attempt=$geometryAttempts",
        )
        FileLog.append("capture consent: geometry tap ($tapX,$tapY)")
        val ok = performTap(cx, cy)
        if (geometryAttempts >= MAX_GEOMETRY_ATTEMPTS) {
            CaptureAutoApprove.disarm()
        }
        return ok
    }

    /** 矩形是否完整落在屏幕内。 */
    private fun isFullyOnScreen(rect: Rect, width: Int, height: Int): Boolean =
        rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height

    /**
     * 当前屏幕的**物理**尺寸（含导航栏）。
     *
     * getBoundsInScreen 返回的就是这套坐标，所以校验 bounds 时必须用同一套尺寸。
     *
     * 注意：`getRealMetrics` 返回的其实是**应用区**尺寸（实测 PZ261A：2429，
     * 比物理 2670 少一个导航栏），`getRealSize` 在这个精简 SDK 上不可用。
     * 所以这里用 getRealMetrics，再把底部导航栏的高度加回来 ——
     * 加回来是宁可放宽，也不能收紧：收紧会把正常位置误判成「越界」。
     */
    private fun realScreenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val fallbackWidth = metrics.widthPixels
        val fallbackHeight = metrics.heightPixels

        // 优先 currentWindowMetrics（API 30+，返回整个 display 的边界，含导航栏）。
        val bound = runCatching {
            (getSystemService(android.view.WindowManager::class.java))
                ?.currentWindowMetrics?.bounds
        }.getOrNull()
        if (bound != null && bound.width() > 0 && bound.height() > 0) {
            // currentWindowMetrics 返回物理屏尺寸（实测 PZ261A：1200x2670，含导航栏），
            // 是准的。**不加 slack**：错位窗口报出的假坐标可能超出物理屏（实测 y=2821），
            // 上界一旦放宽，这些假坐标就会漏过边界闸，几何兜底就拿假坐标瞎点
            // —— 12:30 实测 tap(822,2743) 点空，把授权框点没了。
            // 物理屏外的 bounds 必然是错位坐标，宁可 skip 让用户手点。
            return bound.width() to bound.height()
        }

        // 次选 getRealMetrics。实测 PZ261A 上 display 为 null 时走不到这里，
        // 保留作兜底。
        val real = android.util.DisplayMetrics()
        val ok = runCatching {
            val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(android.view.WindowManager::class.java))?.defaultDisplay
            }
            if (target != null) {
                target.getRealMetrics(real)
            } else {
                null
            }
        }.getOrNull()
        if (ok != null && real.widthPixels > 0 && real.heightPixels > 0) {
            // 导航栏高度 = 物理高度 - 应用区高度。这里拿不到物理高度，
            // 就用一个保守的固定余量（导航栏 + 状态栏 + 误差）放宽上界。
            val slack = (fallbackHeight - real.heightPixels).coerceAtLeast(0)
            return real.widthPixels to (real.heightPixels + slack + NAV_BAR_SLACK_PX)
        }
        // 全部失败：displayMetrics 不含导航栏，直接拿它当「物理高度」会误判越界。
        // 上界宁可放宽（加余量），也不能收紧。
        FileLog.append("capture consent: realScreenSize fallback ${fallbackWidth}x$fallbackHeight")
        return fallbackWidth to (fallbackHeight + NAV_BAR_SLACK_PX)
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

    /** 上次全树 dump 的时间，10 秒内不重复（rc.log 只有 128KB，别刷爆）。 */
    private var lastTreeDumpAt = 0L

    /**
     * 把授权框窗口的完整无障碍树 dump 进 rc.log（一次性，10 秒节流）。
     *
     * 背景：PZ261A / Android 16 的授权框上「允许」按钮不在树里
     * （byViewId 0 nodes、按钮栏 childCount=1），要靠这份 dump 确认树里
     * 到底还有哪些节点、还能不能结构性地找到可点目标。
     */
    private fun dumpTreeOnce(root: AccessibilityNodeInfo) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTreeDumpAt < 10_000L) {
            return
        }
        lastTreeDumpAt = now
        val sb = StringBuilder()
        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= 60 || depth > 14) {
                return
            }
            count += 1
            val rect = Rect().also { node.getBoundsInScreen(it) }
            sb.append('\n').append("  ".repeat(depth))
                .append("${node.className} text=\"${node.text}\" ")
                .append("click=${node.isClickable} id=${node.viewIdResourceName} $rect")
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0)
        FileLog.append("a11y tree (n=$count):$sb")
    }

    /**
     * 结构性点击：找到「拒绝」节点后，在它的父容器（按钮栏）里点除它之外的兄弟。
     *
     * 背景：PZ261A / Android 16 的授权框上「允许」（button1）不进无障碍视图树
     * —— flags 已含 flagIncludeNotImportantViews + flagReportViewIds，
     * byViewId 依旧 0 nodes；而「拒绝」在树里。两个按钮同属一个容器，
     * 那个兄弟就是「允许」。
     *
     * 关键：performAction(ACTION_CLICK) 按节点对象派发点击，**不依赖 bounds**
     * —— 这份 bounds 整体错位（报 y=2916，真实 y=2261），但坐标无关紧要，
     * 所以不受错位影响。手势点击做不到这一点。
     *
     * 两道闸防误点：兄弟必须可点（自身或祖先可点），文案不得命中「拒绝」类。
     */
    private fun clickApprovalSibling(root: AccessibilityNodeInfo): Boolean {
        val denyNode = findDenyNode(root) ?: return false
        var container = denyNode.parent ?: return false

        // 按钮栏可能是两层嵌套（外层 LinearLayout 含两个按钮容器）。
        // 从「拒绝」往上找第一个 childCount > 1 的容器；都找不到就用直接父容器。
        var up = 0
        while (container.childCount <= 1 && up < 2) {
            val grand = container.parent ?: break
            container = grand
            up += 1
        }
        val denyRect = Rect().also(denyNode::getBoundsInScreen)
        val dump = StringBuilder()
        var clicked = false
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            val rect = Rect().also { child.getBoundsInScreen(it) }
            val label = labelOf(child)
            dump.append("[i=$i \"$label\" click=${child.isClickable} cls=${child.className} $rect]")
            val isDenySelf = rect == denyRect ||
                CaptureAutoApprove.denyLabels.any { label.equals(it, ignoreCase = true) }
            if (isDenySelf) {
                continue
            }
            if (clickNodeOrAncestor(child)) {
                clicked = true
            }
        }
        FileLog.append("a11y siblings(up=$up childCount=${container.childCount}): $dump")
        return clicked
    }

    /**
     * 按文案点按钮。
     *
     * 不能只遍历节点找 `text == "允许" 且 isClickable`：系统对话框的按钮
     * 普遍是「不可点的 TextView + 可点的父容器」两层结构，直接找文本节点会漏。
     * 所以找到文本节点后还要向上找第一个可点的祖先。
     */
    private fun clickLabels(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
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
                if (clickNodeOrAncestor(node)) {
                    return true
                }
            }
        }
        return false
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
        removeBadgeWindow()
        super.onDestroy()
    }

    // ---------- 被控状态角标 ----------
    //
    // 被控时机主应当知情：屏幕上有一个不遮挡、不拦截触摸的常驻角标
    // 「被控制」，会话结束后变成「控制已关闭」并在几秒后消失。
    //
    // 用 TYPE_ACCESSIBILITY_OVERLAY：无障碍服务自带这个窗口能力，
    // 不需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限，锁屏界面也能显示。

    private var badgeView: TextView? = null
    private val badgeHandler = Handler(Looper.getMainLooper())
    private val badgeHideRunnable = Runnable { removeBadgeWindow() }

    /** 在主线程渲染/更新角标文案；autoHideMs > 0 时到点自动移除。 */
    private fun renderBadge(text: String, autoHideMs: Long) {
        val active = text == getString(R.string.control_badge_active)
        val badgeOn = ContextCompat.getColor(
            this,
            if (active) R.color.sess_badge_on else R.color.sess_badge_off_on,
        )
        val badgeBg = ContextCompat.getColor(
            this,
            if (active) R.color.sess_badge_bg else R.color.sess_badge_off_bg,
        )
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val textView = badgeView ?: run {
            val created = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                includeFontPadding = false
                setPadding(dp(12), dp(7), dp(12), dp(8))
                // FLAG_NOT_TOUCHABLE 是关键：A 端远程注入的坐标不能被角标挡住。
                isClickable = false
                isFocusable = false
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 贴顶部显示（用户指定），往下避开状态栏。
            params.y = dp(48)
            runCatching { windowManager.addView(created, params) }
                .onFailure { error ->
                    Log.w(TAG, "badge addView failed: ${error.message}")
                    return
                }
            badgeView = created
            created
        }
        textView.text = text
        textView.setTextColor(badgeOn)
        // 被控 = 实心橙底 + 白点 + 白字；结束 = 中性灰底 + 灰字，无呼吸。
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(8), dp(8))
            setColor(badgeOn)
        }
        textView.setCompoundDrawablesWithIntrinsicBounds(if (active) dot else null, null, null, null)
        textView.compoundDrawablePadding = dp(4)
        textView.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(badgeBg)
        }
        badgeHandler.removeCallbacks(badgeHideRunnable)
        if (autoHideMs > 0) {
            badgeHandler.postDelayed(badgeHideRunnable, autoHideMs)
        }
        FileLog.append("badge show: $text")
    }

    private fun removeBadgeWindow() {
        badgeHandler.removeCallbacks(badgeHideRunnable)
        val textView = badgeView ?: return
        badgeView = null
        runCatching {
            getSystemService(WindowManager::class.java)?.removeView(textView)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    /**
     * 依次点击一串坐标（用于远程输入锁屏密码）。
     *
     * **不能连续调用 dispatchGesture**：系统同一时刻只挂一个手势，后一个会
     * 直接把前一个取消掉。实测连发 3 下只有最后一下生效，密码自然输不对。
     * 所以这里改成「上一次 onCompleted 之后再发下一次」的串行链。
     */
    fun tapSequence(
        points: List<Pair<Float, Float>>,
        intervalMs: Long = 140L,
        onFinished: (completed: Int) -> Unit = {},
    ) {
        if (points.isEmpty()) {
            onFinished(0)
            return
        }
        val queue = ArrayDeque(points)
        var completed = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        fun next() {
            val point = queue.removeFirstOrNull()
            if (point == null) {
                FileLog.append("tap sequence done $completed/${points.size}")
                onFinished(completed)
                return
            }
            val path = Path().apply { moveTo(point.first, point.second) }
            dispatch(
                path = path,
                startTimeMs = 0L,
                durationMs = 50L,
                onDone = { ok ->
                    if (ok) {
                        completed += 1
                    } else {
                        FileLog.append("tap sequence abort at (${point.first.toInt()},${point.second.toInt()})")
                    }
                    handler.postDelayed({ next() }, intervalMs)
                },
            )
        }
        next()
    }

    private fun dispatch(
        path: Path,
        startTimeMs: Long,
        durationMs: Long,
        onDone: (Boolean) -> Unit = {},
    ): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, startTimeMs, durationMs),
            )
            .build()
        // 以前这里传的是 null 回调，注入成功还是被系统拦掉完全无从判断，
        // 「滑动没反应」只能靠猜。现在至少失败了能在诊断日志里看到。
        // onCompleted 也要记：只有「系统收下了并且跑完」才说明事件真的送到了窗口。
        var settled = false
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (settled) {
                        return
                    }
                    settled = true
                    onDone(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (settled) {
                        return
                    }
                    settled = true
                    noteGestureFailure("cancelled")
                    onDone(false)
                }
            },
            null,
        )
        if (!accepted) {
            noteGestureFailure("rejected")
            if (!settled) {
                settled = true
                onDone(false)
            }
        }
        return accepted
    }

    /**
     * 注入失败时记一行，但做 5 秒节流。
     *
     * 锁屏、系统弹窗等场景下每次触摸都会被取消，不节流会把日志刷满，
     * 反而看不到真正有用的那几条。
     */
    private fun noteGestureFailure(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGestureFailureAt < 5_000L) {
            return
        }
        lastGestureFailureAt = now
        FileLog.append("gesture $reason")
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

        /** 授权框动画结束后的重试间隔。滑入动画约 200~400ms，取 500ms 留足余量。 */
        private const val RETRY_DELAY_MS = 500L

        /**
         * 屏幕高度的额外余量，用来把「应用区高度」放宽到「物理高度」。
         *
         * getRealMetrics 给的是应用区（不含导航栏），getBoundsInScreen 给的是物理坐标，
         * 两者差一个导航栏（实测 PZ261A 差 241px）。这里给 300px 余量覆盖常见导航栏高度。
         */
        private const val NAV_BAR_SLACK_PX = 300

        /** 「控制已关闭」角标的停留时长。 */
        private const val CONTROL_ENDED_BADGE_MS = 4000L

        /**
         * 无障碍就绪回调槽，由被控服务注册。
         *
         * 放在 companion 里而不是实例上：被控服务启动时无障碍可能还没连上，
         * 拿不到实例，注册就会落空。静态槽不存在这个问题。
         */
        @Volatile
        var onReady: (() -> Unit)? = null
            private set

        /** 注册就绪回调；若无障碍已经连上，立即补调一次。 */
        fun registerOnReady(callback: () -> Unit) {
            onReady = callback
            if (isConnected()) {
                callback()
            }
        }

        /** 注销就绪回调，避免持有已销毁服务的引用。 */
        fun unregisterOnReady() {
            onReady = null
        }

        // ---------- 被控状态角标的静态入口 ----------
        //
        // 回调来自 WebRTC 线程，必须 post 到主线程再碰窗口。
        // 无障碍没连上时静默放弃：这种状态下会话本来就会被拒绝建立。

        /** 被控会话已建立，显示常驻角标「被控制」。 */
        fun showControlActive() {
            val service = instance ?: return
            service.badgeHandler.post {
                service.renderBadge(service.getString(R.string.control_badge_active), 0L)
            }
        }

        /** 会话结束，角标切换为「控制已关闭」，几秒后自动消失。 */
        fun showControlEnded() {
            val service = instance ?: return
            service.badgeHandler.post {
                service.renderBadge(
                    service.getString(R.string.control_badge_ended),
                    CONTROL_ENDED_BADGE_MS,
                )
            }
        }

        /** button1 是确认；取消/拒绝在部分 ROM 是 button2，三按钮布局里是 button3。 */
        private val DENY_VIEW_IDS = listOf("android:id/button3", "android:id/button2")

        /**
         * 确认按钮的系统 view id。
         *
         * 标准 AlertDialog 里 button1 恒为确认按钮。它被系统标了
         * importantForAccessibility=no，只有 flagIncludeNotImportantViews 生效时
         * 才会出现在视图树里（该 flag 必须在 accessibility_service_config.xml 声明）。
         */
        private val CONFIRM_VIEW_IDS = listOf("android:id/button1")

        @Volatile
        var instance: RemoteAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
