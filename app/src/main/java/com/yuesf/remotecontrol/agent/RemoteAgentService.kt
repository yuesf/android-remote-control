package com.yuesf.remotecontrol.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuesf.remotecontrol.MainActivity
import com.yuesf.remotecontrol.R
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.signaling.RemoteSignal
import com.yuesf.remotecontrol.signaling.SignalingRepository
import com.yuesf.remotecontrol.webrtc.AgentRtcSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class RemoteAgentService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signalJob: Job? = null
    private var agentSession: AgentRtcSession? = null
    private var sessionTargetId: String? = null
    private var pendingProjectionData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        // 双开空间里的实例会持有独立 deviceId，与主空间实例抢同一个被控身份。
        if (!SpaceGuard.isPrimarySpace(this)) {
            Log.w(TAG, "refuse to run in secondary user space")
            stopSelf()
            return
        }
        createNotificationChannel()
        isRunning = true
        signalJob = serviceScope.launch {
            SignalingRepository.signals.collect(::handleRemoteSignal)
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

        extractProjectionData(intent)?.let { projectionData ->
            pendingProjectionData = projectionData
            isCaptureAuthorized = true
        }

        startInForeground(pendingProjectionData != null)
        isRunning = true

        val config = AppPreferences(this).loadConfig()
        if (config == null || config.role != DeviceRole.AGENT) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching { SignalingRepository.connect(config) }
            .onFailure { Log.e(TAG, "signaling connect failed", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        agentSession?.close("service-stopped")
        agentSession = null
        sessionTargetId = null
        pendingProjectionData = null
        isCaptureAuthorized = false
        isRunning = false

        signalJob?.cancel()
        serviceScope.cancel()
        if (AppPreferences(this).savedRoleOrNull() != DeviceRole.CONTROLLER) {
            SignalingRepository.disconnect()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleRemoteSignal(signal: RemoteSignal) {
        val payload = signal.payload
        when (payload.optString("kind")) {
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
                    agentSession = null
                    sessionTargetId = null
                    isCaptureAuthorized = false
                }
            }
        }
    }

    private fun startAgentSession(controllerDeviceId: String) {
        val existing = agentSession
        if (existing != null) {
            if (controllerDeviceId == sessionTargetId) {
                existing.resendOffer()
            } else {
                sendSessionError(controllerDeviceId, "DEVICE_BUSY")
            }
            return
        }

        if (!RemoteAccessibilityService.isConnected()) {
            sendSessionError(controllerDeviceId, "ACCESSIBILITY_NOT_ENABLED")
            return
        }

        val projectionData = pendingProjectionData
        if (projectionData == null) {
            sendSessionError(controllerDeviceId, "CAPTURE_AUTH_REQUIRED")
            return
        }

        startInForeground(withProjection = true)
        pendingProjectionData = null
        sessionTargetId = controllerDeviceId
        lateinit var session: AgentRtcSession

        session = AgentRtcSession(
            context = this,
            projectionData = projectionData,
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
                    isCaptureAuthorized = false
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
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopService)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isCaptureAuthorized: Boolean = false
            private set

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
