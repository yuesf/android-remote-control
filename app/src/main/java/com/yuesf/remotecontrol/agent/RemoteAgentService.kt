package com.yuesf.remotecontrol.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.signaling.HeartbeatPolicy
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

class RemoteAgentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = ScreenCaptureHolder()
    private var signalJob: Job? = null
    private var agentSession: AgentRtcSession? = null
    private var sessionTargetId: String? = null

    /** 上次自动拉起采集授权框的时间，用于节流。 */
    private var lastCaptureAutoRequestAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 会话结束后延迟回到空闲档。
     *
     * 不立刻切：A 端手抖点断开、或是短暂抖动后马上重连时，立刻缩到 360 宽
     * 会让重连后的第一眼是糊的，还要再切回来，来回 resize 纯属浪费。
     */
    private val enterIdleRunnable = Runnable {
        if (agentSession == null) {
            capture.enterIdleMode()
            releaseWifiLock()
            refreshNotification()
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
        FileLog.append("service created")
        createNotificationChannel()
        isRunning = true
        instance = this
        acquireWakeLock()
        // 无条件持有：不充电 + 锁屏时 Wi-Fi 会进 PS，锁一放长连接就废。
        acquireWifiLock()
        scheduleKeepAliveAlarm()
        registerNetworkMonitor()
        Log.i(TAG, "service created, subscribing signals")
        // 系统层终止投屏时，把当前会话收掉，但服务继续在线等待下一次连接。
        capture.onProjectionStopped = {
            serviceScope.launch {
                isCaptureAuthorized = false
                agentSession?.close("screen-capture-stopped")
            }
        }
        signalJob = serviceScope.launch {
            // 单个信号处理失败不能让整条订阅终止：collect 里抛异常会结束这个协程，
            // 之后再也不会收到任何信号，表现就是「在线但收到请求毫无反应」，
            // 而 socket 和心跳都正常，从外部看极难定位。
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

        // 保活闹钟唤醒。服务若已在前台，这里只做「续连接」这件事：
        // 重复走一遍 startForeground 在 Android 12+ 的后台启动限制下并不总是被允许，
        // 而连接本身只需要 ensureConnected，没必要付那个风险。
        if (intent?.action == ACTION_KEEP_ALIVE) {
            FileLog.append(
                "alarm fired online=${SignalingRepository.isOnline} running=$isRunning",
            )
            if (isRunning) {
                acquireWakeLock()
                // Wi-Fi 锁必须一直持有：Doze 一进来 radio 就会进 PS，锁没了下行包
                // 会被 AP 缓存到 DTIM 才送达，长连接就等于断了（详见 acquireWifiLock 注释）。
                acquireWifiLock()
                runCatching { SignalingRepository.ensureConnected() }
                    .onFailure { FileLog.append("alarm reconnect failed: ${it.javaClass.simpleName}") }

                // 主动上报一次存活。
                // 只靠服务端 ping 刷新 lastSeenAt 是不够的——服务端的业务心跳按用户规则
                // 走（非峰值 60 分钟），控制端在线判定会读到 60 分钟前的旧值，
                // 把一台好好的设备显示成「已失联」。这里每轮保活（10 分钟）报一次，
                // 控制端 12 分钟窗口才是准的。
                val reported = runCatching { SignalingRepository.reportPresence() }
                    .getOrDefault(false)
                if (!reported) {
                    // 连一条 ping 都发不出去，这条连接已经没救了，直接重建，
                    // 不要等 25 分钟的沉默超时。
                    FileLog.append("presence failed -> force rebuild")
                    runCatching { SignalingRepository.forceReconnect() }
                        .onFailure { FileLog.append("force rebuild failed: ${it.javaClass.simpleName}") }
                }

                maybeAutoRequestCapture()
                scheduleKeepAliveAlarm()
                return START_STICKY
            }
            // 服务曾被系统回收：落到下面的正常流程把自己重新拉起来。
        }

        val projectionData = extractProjectionData(intent)

        // Android 14 起：必须先以 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 进入前台，
        // 才允许调用 getMediaProjection。顺序反了会被系统立刻终止投影
        // （表现为刚授权完就变成“未授权”，且没有任何异常）。
        if (projectionData == null) {
            startInForeground(capture.isReady())
        }
        if (projectionData != null) {
            startInForeground(withProjection = true)
            // 授权凭证只能使用一次（Android 14 起强制），所以这里立刻把采集管线建好常驻，
            // 之后每次会话都复用同一个 VideoTrack，不再要求用户重复授权。
            if (capture.prepare(this, projectionData)) {
                // 光拿到授权不够：Android 14 会回收「声明了 mediaProjection 却没有真正投屏」
                // 的投影对象，表现就是刚授权完几秒又变成未授权。所以立刻开始出帧，
                // 并让它常驻（会话结束时也不停），把投影保住。
                // 先降档再出帧：虚拟屏一创建就是 360 宽，没有「先满规格再缩」的浪费。
                capture.enterIdleMode()
                capture.startCapture()
                isCaptureAuthorized = true
                refreshNotification()
            }
        }
        isRunning = true

        val config = AppPreferences(this).loadConfig()
        if (config == null || config.role != DeviceRole.AGENT) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { SignalingRepository.connect(config) }
            .onFailure { Log.e(TAG, "signaling connect failed", it) }
        maybeAutoRequestCapture()
        return START_STICKY
    }

    /**
     * 熄屏保活锁。
     *
     * 前台服务只保证进程不被回收，**不保证进程被调度**：屏幕一灭，没有唤醒锁时
     * SoC 会进入挂起，WebSocket 收到的数据只能堆在内核接收队列里没人读。
     *
     * 实测症状：锁屏后 `/proc/net/tcp6` 里到 8787 的连接长期停在 CLOSE_WAIT
     * 且接收队列有 1 字节未读，服务端还认为设备在线，实际任何 control-request
     * 都得不到响应——A 端表现为「连不上」，而在 B 上看不出任何异常。
     *
     * 所以唤醒锁**常驻**，这是「随时可连」的硬成本，去掉就等于把上面那个 bug 放回来。
     *
     * 但要注意：进入 Doze 之后系统会**忽略 wakelock**，这把锁届时是失效的。
     * 真正在 Doze 里保命的是 `scheduleKeepAliveAlarm()` 那个精确闹钟，
     * 以及电池优化白名单。两者缺一，拔掉充电线锁屏半小时就会离线。
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) {
            return
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { Log.w(TAG, "acquire wake lock failed", it) }
        }
    }

    /**
     * Wi-Fi 锁：**常驻持有，且必须用 HIGH_PERF**。
     *
     * 这里踩过一个很隐蔽的坑，记下来别再犯：
     *
     * 之前用的是 `WIFI_MODE_FULL_LOW_LATENCY`（type=4），理由是文档说它更省电。
     * 但实测 `dumpsys wifi` 里 `mPowerSaveDisableRequests=0`——**它压根没有禁用
     * Wi-Fi 省电模式**。AOSP 的 WifiLockManager 里只有 HIGH_PERF 会去拧
     * power-save 开关，LOW_LATENCY 只是给前台实时应用加个调度提示，
     * 而且 `WifiLockManager#onAppForeground` 说明它依赖应用在前台。
     *
     * 后果正好对上用户报的现象：
     * - 插着充电线 → 系统本来就不让 radio 省电 → 一切正常（我之前的测试全是假通过）
     * - 拔线 + 锁屏 → Wi-Fi 进 PS 模式，下行帧被 AP 缓存到 DTIM 周期才送达
     *   → 服务端 ping 的 pong 回不去 → 连续 3 轮判定失联 → A 上显示「B 不在线」
     *
     * 所以只能用 HIGH_PERF（type=3）。它虽然被标记 deprecated，但行为是对的：
     * 会让 `mPowerSaveDisableRequests` 加一，radio 不进 PS。
     * 这是「随时可连」在射频层的硬成本，和 wakelock 一样去不掉。
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) {
            return
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val lock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
        wifiLock = lock?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { Log.w(TAG, "acquire wifi lock failed", it) }
        }
        if (wifiLock?.isHeld == true) {
            FileLog.append("wifi lock acquired (high_perf)")
        }
    }

    /**
     * 会话结束时**不放锁**。
     *
     * 之前的策略是"会话结束就放 Wi-Fi 锁省电"，这在插着充电线时看不出问题
     * （充电状态下系统本来就不让 radio 省电），一旦拔线就会掉线。
     * 所以 [HOLD_WIFI_LOCK_WHEN_IDLE] 固定为 true，这个方法实际上是个空操作，
     * 留着它是为了保留"万一以后要回退"的开关位置。
     */
    private fun releaseWifiLock() {
        if (HOLD_WIFI_LOCK_WHEN_IDLE) {
            return
        }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        if (wifiLock != null) {
            FileLog.append("wifi lock released")
        }
        wifiLock = null
    }

    private fun releaseKeepAliveLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * Doze 兜底唤醒闹钟。
     *
     * 这是「拔掉充电线 + 锁屏」场景下唯一的救命机制。
     *
     * Doze 一旦生效，系统同时做三件事：挂起 CPU、**忽略应用持有的 wakelock**、掐断后台网络。
     * 于是 WebSocket 断了，而重连是靠 `mainHandler.postDelayed` 排的，CPU 挂着回调永远不执行——
     * 服务看着还活着，实际既不连也不重连，A 端看到的就是「B 不在线」，
     * 而且只要不碰它，永远不会自己好。
     *
     * `setExactAndAllowWhileIdle` 是 Doze 里仍会按约定时间投递的少数通道之一，
     * 投递时会短暂解除空闲限制（对已进电池白名单的应用，这段时间网络是通的），
     * 正好够把连接续上。周期由 [HeartbeatPolicy] 决定（峰值 2 分钟 / 平时 60 分钟），
     * 并自动截断到峰值窗口起点。Doze 对该接口限流 9 分钟，峰值期的 2 分钟实际会被顺延。
     *
     * 没有精确闹钟权限时降级到 `setAndAllowWhileIdle`：时间会漂，但 Doze 里照样能触发。
     */
    private fun scheduleKeepAliveAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            FileLog.append("alarm: AlarmManager unavailable")
            return
        }
        val pending = PendingIntent.getService(
            this,
            REQUEST_KEEP_ALIVE,
            Intent(this, RemoteAgentService::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val delay = HeartbeatPolicy.nextDelayMs()
        val triggerAt = SystemClock.elapsedRealtime() + delay
        val exact = runCatching {
            val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending,
                )
            }
            canBeExact
        }.getOrElse { error ->
            FileLog.append("alarm schedule failed: ${error.javaClass.simpleName}")
            false
        }
        lastAlarmExact = exact
        FileLog.append("alarm +${delay / 60_000}min exact=$exact")
    }

    private fun cancelKeepAliveAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getService(
            this,
            REQUEST_KEEP_ALIVE,
            Intent(this, RemoteAgentService::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarmManager.cancel(pending) }
        pending.cancel()
    }

    /**
     * 网络状态监听：网络一恢复就立刻重连，不用等下一个 10 分钟闹钟。
     *
     * 典型场景是 Doze 退出、Wi-Fi 重新关联、飞行模式关掉。少了这个回调，
     * 短时间的断网恢复要白等一整轮闹钟周期。
     */
    private fun registerNetworkMonitor() {
        if (networkCallback != null) {
            return
        }
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.append("network available -> ensureConnected")
                runCatching { SignalingRepository.ensureConnected() }
            }

            override fun onLost(network: Network) {
                FileLog.append("network lost")
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure {
                networkCallback = null
                FileLog.append("network callback failed: ${it.javaClass.simpleName}")
            }
    }

    private fun unregisterNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * 进程重启后投影授权必然失效（Android 14 的凭证只能用一次）。
     * 这里自动把系统授权框拉起来，由无障碍服务代点确认，做到重启后也无人值守。
     *
     * 三个前置条件缺一不可：采集确实失效、有悬浮窗权限（后台启动 Activity 的豁免项）、
     * 无障碍服务在线（否则没人能点掉那个框）。加了 2 分钟节流，
     * 用户若连续拒绝也不至于被反复打断。
     */
    private fun maybeAutoRequestCapture() {
        if (capture.isReady()) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (!RemoteAccessibilityService.isConnected()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAutoRequestAt < CAPTURE_AUTO_REQUEST_INTERVAL_MS) {
            return
        }
        lastCaptureAutoRequestAt = now
        runCatching {
            startActivity(
                Intent(this, CaptureRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { Log.w(TAG, "auto capture request failed", it) }
    }

    override fun onDestroy() {
        agentSession?.close("service-stopped")
        agentSession = null
        sessionTargetId = null
        capture.release()
        isCaptureAuthorized = false
        isRunning = false
        if (instance === this) {
            instance = null
        }
        releaseKeepAliveLocks()
        cancelKeepAliveAlarm()
        unregisterNetworkMonitor()
        mainHandler.removeCallbacks(enterIdleRunnable)

        signalJob?.cancel()
        serviceScope.cancel()
        if (AppPreferences(this).savedRoleOrNull() != DeviceRole.CONTROLLER) {
            SignalingRepository.disconnect()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务里划掉 App 时触发。
     *
     * `stopWithTask="false"` 已经保证系统不会跟着把服务停掉，这里不主动 stopSelf，
     * 服务与信令连接继续存活，A 端随时能连进来——这正是无人值守要的效果。
     * 留一条日志是为了以后排查「划掉之后到底还活着没有」。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, foreground service stays online")
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
            "control-request" -> {
                // 端到端握手：先回执，再去做建 PeerConnection 这类慢操作。
                // 有了这一条，A 才能区分「B 压根没收到」和「B 收到了但建连失败」——
                // 否则只能靠超时盲猜，且两种故障在界面上完全一样。
                val acked = SignalingRepository.sendSignal(
                    signal.fromDeviceId,
                    JSONObject()
                        .put("kind", "control-ack")
                        .put("protocolVersion", 1),
                )
                lastAckResult = acked
                FileLog.append("control-ack sent=$acked")
                Log.i(TAG, "control-ack sent=$acked")
                startAgentSession(signal.fromDeviceId)
            }
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
                    agentSession = null
                    sessionTargetId = null
                    // 采集管线是常驻的，会话结束不代表授权失效，
                    // 这里跟着置 false 会让诊断面板误报「未授权」。
                    isCaptureAuthorized = capture.isReady()
                }
            }
        }
    }

    private fun startAgentSession(controllerDeviceId: String) {
        Log.i(TAG, "startAgentSession from=${controllerDeviceId.take(8)}")
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
            return
        }

        if (!capture.isReady()) {
            Log.w(TAG, "reject: capture not prepared (B 端还没授权屏幕采集)")
            sendSessionError(controllerDeviceId, "CAPTURE_AUTH_REQUIRED")
            return
        }

        Log.i(TAG, "creating RTC session")
        startInForeground(withProjection = true)
        // 会话期间要传画面：撤销待执行的「回空闲档」，并把 Wi-Fi 提到高性能。
        mainHandler.removeCallbacks(enterIdleRunnable)
        acquireWifiLock()
        sessionTargetId = controllerDeviceId
        lateinit var session: AgentRtcSession

        session = AgentRtcSession(
            context = this,
            capture = capture,
            onLocalSignal = { payload ->
                SignalingRepository.sendSignal(controllerDeviceId, payload)
            },
            onStatus = { status -> Log.d(TAG, "RTC status: $status") },
            onSessionClosed = { reason ->
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
                    isCaptureAuthorized = capture.isReady()
                    // 会话结束就回空闲档：停满规格出帧、放掉 Wi-Fi 高性能锁。
                    scheduleIdle()
                }
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

    private fun scheduleIdle() {
        mainHandler.removeCallbacks(enterIdleRunnable)
        mainHandler.postDelayed(enterIdleRunnable, IDLE_DELAY_MS)
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
            // 采集没就绪时直接说出来：进程被杀重启后投影授权必然失效，
            // 这时服务还在线、A 也能连上，但画面一定是黑的，光看通知看不出任何异常。
            .setContentTitle(
                getString(
                    if (capture.isReady()) R.string.notification_title
                    else R.string.notification_title_needs_capture,
                ),
            )
            .setContentText(
                getString(
                    if (capture.isReady()) R.string.notification_body
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
        const val ACTION_START = "com.yuesf.remotecontrol.agent.START"
        const val ACTION_STOP = "com.yuesf.remotecontrol.agent.STOP"
        const val ACTION_KEEP_ALIVE = "com.yuesf.remotecontrol.agent.KEEP_ALIVE"
        private const val REQUEST_KEEP_ALIVE = 4202

        /** 自动重新请求采集授权的最小间隔。 */
        private const val CAPTURE_AUTO_REQUEST_INTERVAL_MS = 120_000L

        /** 会话结束后多久回落到空闲采集档。给「手抖断开马上重连」留缓冲。 */
        private const val IDLE_DELAY_MS = 15_000L

        /**
         * 空闲时是否继续持有 Wi-Fi 锁。
         *
         * 曾经为了省电改成 false（只在会话期间持有），代价是拔掉充电线 + 锁屏后
         * 射频按 DTIM 周期休眠，长连接更容易被判死。离线属于功能故障，
         * 比多耗几十毫瓦严重，所以恢复成**常驻**，但模式从 HIGH_PERF 换成更温和的
         * LOW_LATENCY（API 29+）：保低延迟、不强制 radio 全速。
         */
        private const val HOLD_WIFI_LOCK_WHEN_IDLE = true

        private const val WAKE_LOCK_TAG = "remotecontrol:agent"
        private const val WIFI_LOCK_TAG = "remotecontrol:wifi"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 诊断用：保活闹钟是否拿到了精确闹钟权限（false 表示降级为 Doze 下会漂的版本）。 */
        @Volatile
        var lastAlarmExact: Boolean = false

        @Volatile
        private var instance: RemoteAgentService? = null

        /** 诊断面板用：当前采集档位（空闲 360x800@1fps / 会话 1080x2400@12fps）。 */
        fun captureProfileLabel(): String =
            instance?.let { service ->
                runCatching { service.capture.profileLabel() }.getOrNull()
            } ?: "未采集"

        /**
         * Wi-Fi 锁状态，给诊断行用。
         *
         * 这个值决定「拔掉充电线 + 锁屏」后还能不能被连上：锁没拿到，
         * radio 进 PS，下行包按 DTIM 周期才送达，长连接会被服务端判死。
         * 排查离线问题时第一个要看的就它。
         */
        fun wifiLockLabel(): String {
            val held = instance?.wifiLock?.isHeld == true
            return if (held) "已锁" else "未锁"
        }

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

        fun start(context: Context) {
            val intent = Intent(context, RemoteAgentService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startWithProjection(context: Context, projectionData: Intent) {
            val intent = Intent(context, RemoteAgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAgentService::class.java))
        }
    }
}
