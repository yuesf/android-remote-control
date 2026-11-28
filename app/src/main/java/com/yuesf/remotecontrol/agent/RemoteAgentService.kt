package com.yuesf.remotecontrol.agent

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.CaptureRequestActivity
import com.yuesf.remotecontrol.MainActivity
import com.yuesf.remotecontrol.R
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.signaling.RemoteSignal
import com.yuesf.remotecontrol.signaling.SignalingRepository
import com.yuesf.remotecontrol.webrtc.AgentRtcSession
import com.yuesf.remotecontrol.webrtc.ScreenCaptureHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 被控会话服务：**只在被控期间存活**。
 *
 * 与信令保活服务（[SignalingKeepAliveService]）是两回事：信令连接、保活三件套
 * （唤醒锁 / Wi-Fi 锁 / 精确保活闹钟）都在信令服务上常驻；本服务只在收到
 * control-request（被控制心跳）时被它拉起，负责采集授权、建 RTC 会话，
 * 会话结束（或授权失败/超时）立即释放采集并停止自身。
 *
 * 生命周期一览：
 * - 信令服务收到 control-request → 拉起本服务（带 EXTRA_CONTROLLER_ID）
 * - 本服务回 control-ack → 按需拉起采集授权（无障碍代点）→ 建会话
 * - 会话结束 / 授权超时 / 授权被拒 → 释放投影 → stopSelf
 */
class RemoteAgentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = ScreenCaptureHolder()
    private var signalJob: Job? = null
    private var agentSession: AgentRtcSession? = null
    private var sessionTargetId: String? = null

    /** 正在等采集授权的 control-request 发起方；null = 没有在等。 */
    private var pendingControllerId: String? = null

    /** 等待采集授权的最后期限（elapsedRealtime）。 */
    private var captureWaitDeadlineAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 手动授权（设置页）但一直没有会话进来时的兜底：60 秒后释放采集并停止。
     * 走 control-request 正常路径时，轮询会在授权就绪后立即建会话并撤销这个兜底。
     */
    private val standaloneStopRunnable = Runnable {
        if (agentSession == null && pendingControllerId == null) {
            FileLog.append("standalone capture idle, stopping agent service")
            releaseCaptureNow()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 双开空间里的实例会持有独立 deviceId，与主空间实例抢同一个被控身份。
        if (!SpaceGuard.isPrimarySpace(this)) {
            Log.w(TAG, "refuse to run in secondary user space")
            stopSelf()
            return
        }
        FileLog.init(this)
        FileLog.append("agent service created")
        createNotificationChannel()
        isRunning = true
        instance = this

        // 系统层终止投屏时（用户点了系统投屏通知的「停止」等），
        // 把当前会话收掉，onSessionClosed 里会释放采集并停止本服务。
        capture.onProjectionStopped = {
            serviceScope.launch {
                isCaptureAuthorized = false
                SignalingRepository.setCaptureReady(false)
                agentSession?.close("screen-capture-stopped")
            }
        }
        signalJob = serviceScope.launch {
            // 单个信号处理失败不能让整条订阅终止：collect 里抛异常会结束这个协程，
            // 之后再也不会收到任何信号。信令连接本身由信令保活服务维护，
            // 本服务只在被控期间订阅，处理与当前会话相关的信号。
            SignalingRepository.signals.collect { signal ->
                runCatching { handleRemoteSignal(signal) }
                    .onFailure { Log.e(TAG, "failed to handle signal ${signal.payload.optString("kind")}", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SpaceGuard.isPrimarySpace(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionData = extractProjectionData(intent)
        val controllerId = intent?.getStringExtra(EXTRA_CONTROLLER_ID)

        if (projectionData != null) {
            // 授权结果回传（CaptureRequestActivity / 设置页手动授权）。
            // Android 14 起：必须先以 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // 进入前台，才允许调用 getMediaProjection。顺序反了会被系统立刻终止投影。
            startInForeground(withProjection = true)
            if (capture.prepare(this, projectionData)) {
                // 先按空闲档出帧（360 宽低规格）保住投影；真正的会话开始时
                // AgentRtcSession 会自己切到会话档。
                capture.enterIdleMode()
                capture.startCapture()
                isCaptureAuthorized = true
                SignalingRepository.setCaptureReady(true)
                // 状态变化立刻上报一次，控制端不用等下一轮保活才知道 B 能被控了。
                runCatching { SignalingRepository.reportPresence() }
                refreshNotification()
                if (pendingControllerId == null && agentSession == null) {
                    // 手动授权但没有任何会话在等：60 秒后没有会话就释放并停止，
                    // 避免投影空挂在系统上（银行类 App 会一直报「正在共享屏幕」）。
                    mainHandler.postDelayed(standaloneStopRunnable, STANDALONE_STOP_DELAY_MS)
                }
            }
            return START_NOT_STICKY
        }

        if (controllerId != null) {
            // 信令保活服务收到 control-request 后拉起的正常被控入口。
            // 会话期间要传画面：先把「手动授权兜底停止」撤销掉。
            mainHandler.removeCallbacks(standaloneStopRunnable)
            startInForeground(withProjection = false)
            ensureSignalingConnected()
            startAgentSession(controllerId)
            return START_NOT_STICKY
        }

        // 既没有会话目标也没有授权数据：无事可做。
        stopSelf()
        return START_NOT_STICKY
    }

    /** 信令连接由信令保活服务持有；若它没在跑（独立启动场景），兜底连一次。 */
    private fun ensureSignalingConnected() {
        if (SignalingRepository.isOnline) {
            return
        }
        val config = AppPreferences(this).loadConfig() ?: return
        runCatching { SignalingRepository.connect(config) }
            .onFailure { Log.e(TAG, "signaling connect failed", it) }
    }

    /**
     * 真正释放采集：停 MediaProjection、同步授权状态、刷新通知并上报。
     *
     * 投影一停，系统里不再有活动投屏，银行类 App 的「检测到正在共享/录制屏幕」
     * 提示随之消失。投影终止回调会关掉进行中的会话。
     */
    private fun releaseCaptureNow() {
        if (!capture.isReady()) {
            return
        }
        capture.release()
        isCaptureAuthorized = false
        SignalingRepository.setCaptureReady(false)
        // 采集状态变了，立刻上报，控制端的设备列表不用等下一轮保活。
        runCatching { SignalingRepository.reportPresence() }
        refreshNotification()
        FileLog.append("capture released")
    }

    /**
     * 按需授权：A 发起 control-request 而采集还没就绪时，拉起系统授权框
     * 并由无障碍代点确认。前置条件不满足（无悬浮窗权限/无障碍没连）返回 false。
     */
    private fun requestCaptureAuthorization(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            return false
        }
        if (!RemoteAccessibilityService.isConnected()) {
            return false
        }
        return runCatching {
            startActivity(
                Intent(this, CaptureRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            true
        }.onFailure { Log.w(TAG, "capture auth request failed", it) }
            .getOrDefault(false)
    }

    override fun onDestroy() {
        agentSession?.close("service-stopped")
        agentSession = null
        sessionTargetId = null
        capture.release()
        isCaptureAuthorized = false
        SignalingRepository.setCaptureReady(false)
        isRunning = false
        if (instance === this) {
            instance = null
        }
        mainHandler.removeCallbacks(standaloneStopRunnable)
        pendingControllerId = null
        mainHandler.removeCallbacks(::pollCaptureAuthorization)
        // 会话结束：唤醒锁策略交还给信令保活服务（秒连时段判断）。
        SignalingKeepAliveService.setSessionActive(false)

        signalJob?.cancel()
        serviceScope.cancel()
        // 注意：信令连接归信令保活服务所有，这里**不能** disconnect，
        // 否则被控服务一停 B 就从服务端消失了。
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务里划掉 App 时触发。
     *
     * `stopWithTask="false"` 已经保证系统不会跟着把服务停掉；本服务只在被控
     * 期间存在，被控中划掉 App 也不中断会话。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, agent session service stays")
        super.onTaskRemoved(rootIntent)
    }

    private fun handleRemoteSignal(signal: RemoteSignal) {
        val payload = signal.payload
        val kind = payload.optString("kind")
        receivedSignals += 1
        lastSignalKind = kind
        FileLog.append("handle kind=$kind from=${signal.fromDeviceId.take(8)}")
        Log.i(TAG, "signal: kind=$kind from=${signal.fromDeviceId.take(8)}")
        when (kind) {
            // control-request 的新入口走 ACTION_SESSION intent（信令服务转发），
            // 能到这里的是「本服务已存活期间」的重发请求，交给 startAgentSession 去重。
            "control-request" -> startAgentSession(signal.fromDeviceId)
            "webrtc-answer" -> {
                if (signal.fromDeviceId == sessionTargetId) {
                    agentSession?.setRemoteAnswer(payload.optString("sdp"))
                }
            }

            "webrtc-ice" -> {
                if (signal.fromDeviceId == sessionTargetId) {
                    agentSession?.addRemoteIceCandidate(
                        sdpMid = payload.optString("sdpMid").ifBlank { null },
                        sdpMLineIndex = payload.optInt("sdpMLineIndex"),
                        candidate = payload.optString("candidate"),
                    )
                }
            }

            "session-end" -> {
                if (signal.fromDeviceId == sessionTargetId) {
                    agentSession?.close("remote-end")
                }
            }
        }
    }

    private fun startAgentSession(controllerDeviceId: String) {
        Log.i(TAG, "startAgentSession from=${controllerDeviceId.take(8)}")

        // 端到端握手：先回执，再去做建 PeerConnection 这类慢操作。
        // 有了这一条，A 才能区分「B 压根没收到」和「B 收到了但建连失败」——
        // 否则只能靠超时盲猜，且两种故障在界面上完全一样。
        // 顺带把锁屏态带过去：B 若设了密码，密码键盘是安全窗口，
        // A 那边看到的是黑屏，必须提前告诉用户「用解锁面板」而不是「画面坏了」。
        val keyguard = getSystemService(KeyguardManager::class.java)
        val acked = SignalingRepository.sendSignal(
            controllerDeviceId,
            JSONObject()
                .put("kind", "control-ack")
                .put("protocolVersion", 1)
                .put("locked", keyguard?.isKeyguardLocked ?: false)
                .put("secure", keyguard?.isDeviceSecure ?: false),
        )
        lastAckResult = acked
        FileLog.append("control-ack sent=$acked")

        // 正在等采集授权时又收到 control-request：同一台是重发（忽略），
        // 不同设备按忙处理。
        val pending = pendingControllerId
        if (pending != null) {
            if (pending == controllerDeviceId) {
                Log.i(TAG, "capture auth in progress, ignore resend")
            } else {
                Log.w(TAG, "busy: capture auth in progress")
                sendSessionError(controllerDeviceId, "DEVICE_BUSY")
            }
            return
        }

        val existing = agentSession
        if (existing != null) {
            if (controllerDeviceId == sessionTargetId) {
                Log.i(TAG, "same controller resend offer")
                existing.resendOffer()
                return
            }
            if (existing.isAlive()) {
                Log.w(TAG, "busy: current=${sessionTargetId?.take(8)}")
                sendSessionError(controllerDeviceId, "DEVICE_BUSY")
                return
            }
            // 残留会话：A 端异常断开（切网、锁屏、进程被杀）时不会有 session-end，
            // PeerConnection 也不会自己结束。不收掉它，B 会永远回 DEVICE_BUSY，
            // 从 A 看就是「怎么都连不上」，而 B 端一切正常。
            Log.w(TAG, "reclaim stale session ${sessionTargetId?.take(8)}")
            FileLog.append("reclaim stale session")
            existing.close("stale-reclaim")
            agentSession = null
            sessionTargetId = null
        }

        if (!RemoteAccessibilityService.isConnected()) {
            Log.w(TAG, "reject: accessibility not connected")
            sendSessionError(controllerDeviceId, "ACCESSIBILITY_NOT_ENABLED")
            stopSelf()
            return
        }

        // 按需采集：空闲时投影是释放掉的。这里先拉起系统授权框（无障碍代点），
        // 轮询等授权完成再建会话；超时才回 CAPTURE_AUTH_REQUIRED。
        if (!capture.isReady()) {
            Log.i(TAG, "capture not ready, requesting authorization")
            if (!requestCaptureAuthorization()) {
                Log.w(TAG, "reject: cannot request capture auth")
                sendSessionError(controllerDeviceId, "CAPTURE_AUTH_REQUIRED")
                stopSelf()
                return
            }
            pendingControllerId = controllerDeviceId
            captureWaitDeadlineAt = SystemClock.elapsedRealtime() + CAPTURE_AUTH_WAIT_MS
            mainHandler.postDelayed(::pollCaptureAuthorization, CAPTURE_POLL_INTERVAL_MS)
            return
        }

        beginSession(controllerDeviceId)
    }

    /** 轮询等待采集授权完成；完成就建会话，超时回 CAPTURE_AUTH_REQUIRED 并停止。 */
    private fun pollCaptureAuthorization() {
        val controllerDeviceId = pendingControllerId ?: return
        if (capture.isReady()) {
            Log.i(TAG, "capture ready after auth, starting session")
            pendingControllerId = null
            beginSession(controllerDeviceId)
            return
        }
        if (SystemClock.elapsedRealtime() >= captureWaitDeadlineAt) {
            Log.w(TAG, "capture auth wait timeout")
            pendingControllerId = null
            sendSessionError(controllerDeviceId, "CAPTURE_AUTH_REQUIRED")
            stopSelf()
            return
        }
        mainHandler.postDelayed(::pollCaptureAuthorization, CAPTURE_POLL_INTERVAL_MS)
    }

    private fun beginSession(controllerDeviceId: String) {
        Log.i(TAG, "creating RTC session")
        startInForeground(withProjection = true)
        sessionTargetId = controllerDeviceId
        // 会话期间强制持唤醒锁（不受秒连时段限制），结束交还。
        SignalingKeepAliveService.setSessionActive(true)
        lateinit var session: AgentRtcSession

        session = AgentRtcSession(
            context = this,
            capture = capture,
            onLocalSignal = { payload ->
                SignalingRepository.sendSignal(controllerDeviceId, payload)
            },
            onStatus = { status -> Log.d(TAG, "RTC status: $status") },
            onSessionClosed = { reason ->
                // 会话无论怎么结束（对端挂断、超时、链路失败），
                // 都短暂显示「控制已关闭」提示后消失。
                RemoteAccessibilityService.showControlEnded()
                if (reason != "remote-end") {
                    SignalingRepository.sendSignal(
                        controllerDeviceId,
                        JSONObject()
                            .put("kind", "session-ended")
                            .put("reason", reason),
                    )
                }
                if (agentSession === session) {
                    agentSession = null
                    sessionTargetId = null
                    // 立即释放采集并停止服务：被控结束后系统里不能有活动投屏。
                    releaseCaptureNow()
                    stopSelf()
                }
            },
            onPeerConnected = {
                // 「被控制」常驻角标已移除：被控期间不再有任何屏幕提示。
            },
        )
        agentSession = session

        runCatching { session.start() }
            .onFailure { error ->
                Log.e(TAG, "Unable to start screen capture", error)
                session.close("capture-start-failed")
                sendSessionError(controllerDeviceId, "CAPTURE_START_FAILED")
            }
    }

    private fun sendSessionError(controllerDeviceId: String, code: String) {
        SignalingRepository.sendSignal(
            controllerDeviceId,
            JSONObject()
                .put("kind", "session-error")
                .put("code", code),
        )
    }

    private fun startInForeground(withProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } else if (withProjection) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun extractProjectionData(intent: Intent?): Intent? {
        if (intent == null) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            // 采集没就绪时直接说出来：授权还没完成（正在代点）或失败了，
            // 光看通知标题分不清「正在被控」和「等着被控」。
            .setContentTitle(
                getString(
                    if (agentSession != null) R.string.notification_title
                    else R.string.notification_title_needs_capture,
                ),
            )
            .setContentText(
                getString(
                    if (agentSession != null) R.string.notification_body
                    else R.string.notification_body_needs_capture,
                ),
            )
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopService)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 授权状态变化后刷新常驻通知，避免通知一直停留在旧文案。 */
    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RemoteAgentService"
        private const val CHANNEL_ID = "remote_agent"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        /** 信令保活服务转发控制请求时带的入口 action / 参数。 */
        const val ACTION_SESSION = "com.yuesf.remotecontrol.agent.SESSION"
        const val EXTRA_CONTROLLER_ID = "controller_device_id"
        const val ACTION_STOP = "com.yuesf.remotecontrol.agent.STOP"

        /**
         * 手动授权但一直没有会话进来时，多久后释放采集并停止服务。
         * 走正常被控入口时轮询/会话会撤销这个兜底，不受它影响。
         */
        private const val STANDALONE_STOP_DELAY_MS = 60_000L

        /** 等采集授权（拉框 + 无障碍代点）完成的最长等待。无障碍代点时间窗本身有 25 秒上限。 */
        private const val CAPTURE_AUTH_WAIT_MS = 22_000L

        /** 采集授权轮询间隔。 */
        private const val CAPTURE_POLL_INTERVAL_MS = 300L

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: RemoteAgentService? = null

        /** 诊断面板用：当前采集档位。 */
        fun captureProfileLabel(): String =
            instance?.let { service ->
                runCatching { service.capture.profileLabel() }.getOrNull()
            } ?: "未采集"

        @Volatile
        var isCaptureAuthorized: Boolean = false
            private set

        /** 现场诊断用：服务这条订阅实际收到多少条信号。 */
        @Volatile
        var receivedSignals: Int = 0
            internal set

        @Volatile
        var lastSignalKind: String = "无"
            internal set

        @Volatile
        var lastAckResult: Boolean? = null
            internal set

        fun startWithProjection(context: Context, projectionData: Intent) {
            val intent = Intent(context, RemoteAgentService::class.java)
                .setAction(ACTION_SESSION)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAgentService::class.java))
        }

        /**
         * 手动释放屏幕采集（设置页「释放屏幕采集」按钮）。
         *
         * 真正停掉 MediaProjection：系统里不再有活动的投屏，银行等敏感 App
         * 的「检测到正在共享/录制屏幕」提示随之消失。
         *
         * @return true=已释放；false=被控服务没在运行（本来就没事可释放）。
         */
        fun releaseCapture(): Boolean {
            val service = instance ?: return false
            service.releaseCaptureNow()
            return true
        }
    }
}
