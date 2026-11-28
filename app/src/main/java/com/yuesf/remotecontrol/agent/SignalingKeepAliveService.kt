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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.MainActivity
import com.yuesf.remotecontrol.R
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.signaling.HeartbeatPolicy
import com.yuesf.remotecontrol.signaling.SignalingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 轻量信令保活服务：唯一职责是「让本机在线、随时收得到控制请求」。
 *
 * 与被控服务（[RemoteAgentService]）是两回事，拆开的原因：
 * - 信令在线 = 常驻能力。熄屏收请求靠唤醒锁 + Wi-Fi 锁 + 精确保活闹钟三件套，
 *   这些必须一直在，否则 A 发请求 B 收不到（「在线但毫无反应」的老 bug）。
 * - 被控 = 按需能力。只有收到 control-request（被控制心跳）才拉起被控服务，
 *   会话结束立即停止——空闲时 B 上没有「被控」的前台通知和会话机器。
 *
 * 所以这个服务不碰采集、不建 RTC 会话，只做连接保活和请求转发。
 */
class SignalingKeepAliveService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signalJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // 双开空间里的实例会持有独立 deviceId，与主空间实例抢同一个被控身份。
        if (!SpaceGuard.isPrimarySpace(this)) {
            Log.w(TAG, "refuse to run in secondary user space")
            stopSelf()
            return
        }
        FileLog.init(this)
        FileLog.append("signaling service created")
        createNotificationChannel()
        isRunning = true
        instance = this
        applyWakeLockPolicy()
        scheduleWakeLockBoundaryAlarm()
        acquireWifiLock()
        scheduleKeepAliveAlarm()
        registerNetworkMonitor()

        // 监听控制请求：被控服务没在跑时，由这里拉起它（被控制心跳的入口）。
        // 被控服务自己在跑时它有自己的订阅（去重/重发 offer），这里不用管。
        signalJob = serviceScope.launch {
            SignalingRepository.signals.collect { signal ->
                runCatching {
                    if (signal.payload.optString("kind") == "control-request" &&
                        !RemoteAgentService.isRunning
                    ) {
                        startAgentService(signal.fromDeviceId)
                    }
                }.onFailure {
                    Log.e(TAG, "failed to handle signal", it)
                }
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
                // 保活闹钟醒来时重判时段：即使边界闹钟错过（Doze 顺延/重启），
                // 最多 10 分钟后也会自动纠正到正确状态。
                applyWakeLockPolicy()
                scheduleWakeLockBoundaryAlarm()
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

                scheduleKeepAliveAlarm()
                return START_STICKY
            }
            // 服务曾被系统回收：落到下面的正常流程把自己重新拉起来。
        }

        // 时段边界闹钟：进入/退出秒连时段时切换唤醒锁。
        if (intent?.action == ACTION_WAKELOCK_BOUNDARY) {
            FileLog.append("wakelock boundary fired, isRunning=$isRunning")
            if (isRunning) {
                applyWakeLockPolicy()
                // 自滚动：排下一个边界，形成一条永远向前的闹钟链。
                scheduleWakeLockBoundaryAlarm()
            }
            return START_STICKY
        }

        startInForeground()

        val config = AppPreferences(this).loadConfig()
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { SignalingRepository.connect(config) }
            .onFailure { Log.e(TAG, "signaling connect failed", it) }
        return START_STICKY
    }

    /** 收到控制请求：拉起被控服务，由它做授权、建会话。 */
    private fun startAgentService(controllerDeviceId: String) {
        FileLog.append("control-request -> start agent service")
        val intent = Intent(this, RemoteAgentService::class.java)
            .setAction(RemoteAgentService.ACTION_SESSION)
            .putExtra(RemoteAgentService.EXTRA_CONTROLLER_ID, controllerDeviceId)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { FileLog.append("start agent service failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 熄屏保活锁（与被控服务拆分前的实现一致，注释保留完整上下文）。
     *
     * 前台服务只保证进程不被回收，**不保证进程被调度**：屏幕一灭，没有唤醒锁时
     * SoC 会进入挂起，WebSocket 收到的数据只能堆在内核接收队列里没人读。
     *
     * 实测症状：锁屏后 `/proc/net/tcp6` 里到 8787 的连接长期停在 CLOSE_WAIT
     * 且接收队列有 1 字节未读，服务端还认为设备在线，实际任何 control-request
     * 都得不到响应——A 端表现为「连不上」，而在 B 上看不出任何异常。
     *
     * 所以空闲时段之外唤醒锁**常驻**，这是「随时可连」的硬成本。
     * 进入 Doze 后系统会忽略 wakelock，真正在 Doze 里保命的是
     * `scheduleKeepAliveAlarm()` 那个精确闹钟 + 电池优化白名单。
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
     * 按 [WakeLockPolicy] 决定唤醒锁的持/放；被控会话进行中强制持锁
     * （正在被控时卡顿比耗电更不可接受，由被控服务通过 [setSessionActive] 通知）。
     */
    private fun applyWakeLockPolicy() {
        val hold = sessionActive || WakeLockPolicy.shouldHold()
        if (hold) {
            if (wakeLock?.isHeld != true) {
                acquireWakeLock()
                FileLog.append("wakelock ON (${WakeLockPolicy.describe()}, session=$sessionActive)")
            }
        } else if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
            FileLog.append("wakelock OFF (省电时段)")
        }
    }

    /** 被控服务在会话开始/结束时调用：会话期间强制持唤醒锁。 */
    private fun setSessionActiveInternal(active: Boolean) {
        sessionActive = active
        applyWakeLockPolicy()
    }

    private fun scheduleWakeLockBoundaryAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val delay = WakeLockPolicy.nextBoundaryDelayMs() ?: return
        if (delay <= 0) {
            applyWakeLockPolicy()
            return
        }
        val pending = PendingIntent.getService(
            this,
            REQUEST_WAKELOCK_BOUNDARY,
            Intent(this, SignalingKeepAliveService::class.java).setAction(ACTION_WAKELOCK_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = SystemClock.elapsedRealtime() + delay
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        runCatching {
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
        }.onFailure { FileLog.append("wakelock boundary alarm failed: ${it.javaClass.simpleName}") }
    }

    private fun cancelWakeLockBoundaryAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getService(
            this,
            REQUEST_WAKELOCK_BOUNDARY,
            Intent(this, SignalingKeepAliveService::class.java).setAction(ACTION_WAKELOCK_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarmManager.cancel(pending) }
        pending.cancel()
    }

    /**
     * Wi-Fi 锁：**常驻持有，且必须用 HIGH_PERF**。
     *
     * 之前用 `WIFI_MODE_FULL_LOW_LATENCY`（type=4）的教训：实测 `dumpsys wifi`
     * 里 `mPowerSaveDisableRequests=0`——它压根没有禁用 Wi-Fi 省电模式。
     * 拔线 + 锁屏后 Wi-Fi 进 PS，下行包被 AP 缓存到 DTIM 周期才送达，
     * pong 回不去 → 服务端连续判定失联 → A 上显示「B 不在线」。
     * 只有 HIGH_PERF（type=3）会让 radio 不进 PS，这是「随时可连」的射频层硬成本。
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
     * Doze 兜底唤醒闹钟：「拔掉充电线 + 锁屏」场景下唯一的救命机制。
     *
     * Doze 生效时系统挂起 CPU、忽略 wakelock、掐断后台网络，WebSocket 断了，
     * 而 mainHandler 上的重连回调永远不执行。`setExactAndAllowWhileIdle` 是
     * Doze 里仍会投递的少数通道之一，正好够把连接续上。
     * 周期由 [HeartbeatPolicy] 决定；没有精确闹钟权限时降级到会漂的版本。
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
            Intent(this, SignalingKeepAliveService::class.java).setAction(ACTION_KEEP_ALIVE),
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
            Intent(this, SignalingKeepAliveService::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarmManager.cancel(pending) }
        pending.cancel()
    }

    /** 网络一恢复就立刻重连，不用等下一轮保活闹钟。 */
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

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            Intent(this, SignalingKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(getString(R.string.notification_signaling_title))
            .setContentText(getString(R.string.notification_signaling_body))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopService)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_signaling_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_signaling_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        signalJob?.cancel()
        serviceScope.cancel()
        isRunning = false
        if (instance === this) {
            instance = null
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        cancelKeepAliveAlarm()
        cancelWakeLockBoundaryAlarm()
        unregisterNetworkMonitor()
        // 服务是信令连接的持有者（保活闹钟、网络监听都挂在它上面），
        // 服务停掉就没人维护这条连接了，必须一起断开。
        SignalingRepository.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务里划掉 App 时触发。
     *
     * `stopWithTask="false"` 已经保证系统不会跟着把服务停掉，这里不主动 stopSelf，
     * 服务与信令连接继续存活，A 端随时能连进来——这正是无人值守要的效果。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, signaling service stays online")
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "SignalingKeepAlive"
        private const val CHANNEL_ID = "signaling_online"
        private const val NOTIFICATION_ID = 4200
        const val ACTION_START = "com.yuesf.remotecontrol.signaling.START"
        const val ACTION_STOP = "com.yuesf.remotecontrol.signaling.STOP"
        const val ACTION_KEEP_ALIVE = "com.yuesf.remotecontrol.signaling.KEEP_ALIVE"
        private const val ACTION_WAKELOCK_BOUNDARY = "com.yuesf.remotecontrol.signaling.WAKELOCK_BOUNDARY"
        private const val REQUEST_KEEP_ALIVE = 4204
        private const val REQUEST_WAKELOCK_BOUNDARY = 4205

        private const val WAKE_LOCK_TAG = "remotecontrol:signaling"
        private const val WIFI_LOCK_TAG = "remotecontrol:signaling-wifi"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 诊断用：保活闹钟是否拿到了精确闹钟权限（false 表示降级为 Doze 下会漂的版本）。 */
        @Volatile
        var lastAlarmExact: Boolean = false

        @Volatile
        private var sessionActive = false

        @Volatile
        private var instance: SignalingKeepAliveService? = null

        /**
         * Wi-Fi 锁状态，给诊断行用。
         *
         * 这个值决定「拔掉充电线 + 锁屏」后还能不能被连上：锁没拿到，
         * radio 进 PS，下行包按 DTIM 周期才送达，长连接会被服务端判死。
         */
        fun wifiLockLabel(): String {
            val held = instance?.wifiLock?.isHeld == true
            return if (held) "已锁" else "未锁"
        }

        fun start(context: Context) {
            val intent = Intent(context, SignalingKeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignalingKeepAliveService::class.java))
        }

        /** 被控服务通知：会话开始/结束，用于强制持锁。 */
        fun setSessionActive(active: Boolean) {
            val service = instance
            if (service != null) {
                service.mainHandler.post { service.setSessionActiveInternal(active) }
            } else {
                sessionActive = active
            }
        }
    }
}
