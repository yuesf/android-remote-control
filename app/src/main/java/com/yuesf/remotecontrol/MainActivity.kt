package com.yuesf.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yuesf.remotecontrol.accessibility.CaptureAutoApprove
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import com.yuesf.remotecontrol.agent.RemoteAgentService
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.data.normalizeServerUrl
import com.yuesf.remotecontrol.databinding.ActivityMainBinding
import com.yuesf.remotecontrol.signaling.ConnectionPhase
import com.yuesf.remotecontrol.signaling.DeviceInfo
import com.yuesf.remotecontrol.signaling.SignalingRepository
import com.yuesf.remotecontrol.signaling.SignalingState
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private var suppressRoleCallback = false

    private var signalCount = 0
    private var lastSignalDesc = "无"
    private var lastErrorDesc = "无"

    /** 悬浮窗权限引导同一次运行只弹一次。 */
    private var overlayPromptShown = false

    /**
     * 运行在双开空间/应用分身时置位：此时不初始化界面绑定，
     * 其它生命周期回调必须提前返回，否则 lateinit 会抛异常。
     */
    private var spaceBlocked = false

    /** 最近一次拿到的设备列表。本地重算在线状态时直接复用，不需要重新联网。 */
    private var latestDevices: List<DeviceInfo> = emptyList()
    private val presenceHandler = Handler(Looper.getMainLooper())
    private var presenceTickCount = 0

    /**
     * 在线状态刷新节奏。
     *
     * 每 30 秒只用本机时间重算一次显示（零网络开销），每 4 次（2 分钟）才向服务端
     * 要一次最新列表。被控端 10 分钟才上报一次，控制端没必要秒级轮询；
     * 但又不能只在收到推送时更新，否则「对方悄悄失联」这件事界面永远发现不了。
     */
    private val presenceTicker = object : Runnable {
        override fun run() {
            presenceTickCount += 1
            if (presenceTickCount % 4 == 0) {
                runCatching { SignalingRepository.refreshDevices() }
            }
            if (latestDevices.isNotEmpty()) {
                renderDevices(latestDevices)
            }
            presenceHandler.postDelayed(this, PRESENCE_TICK_MS)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            requestNotificationPermissionIfNeeded()
            runCatching { RemoteAgentService.startWithProjection(this, data) }
                .onFailure { error ->
                    toast(
                        getString(
                            R.string.agent_start_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
        } else {
            toast(getString(R.string.capture_permission_denied))
        }
        renderRole()
    }

    private val scanPairingLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::applyPairingPayload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.init(this)

        if (!SpaceGuard.isPrimarySpace(this)) {
            spaceBlocked = true
            showSpaceBlocked()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        populateForm()
        renderBuildInfo()
        bindActions()
        observeSignaling()
        renderRole()
        autoStartIfConfigured()
    }

    /**
     * 打开 App 就恢复上次保存的角色与连接。
     *
     * 以前必须手动点一次「保存并启动」——进程被杀掉再打开，界面上一切正常，
     * 但服务没起来、信令没连，A 端看到的就是「设备离线」，很像程序坏了。
     */
    private fun autoStartIfConfigured() {
        val config = preferences.loadConfig() ?: return
        when (config.role) {
            DeviceRole.AGENT -> {
                if (RemoteAgentService.isRunning) {
                    return
                }
                requestNotificationPermissionIfNeeded()
                ensureBatteryOptimizationIgnored()
                ensureOverlayPermission()
                runCatching { RemoteAgentService.start(this) }
                    .onFailure { error ->
                        toast(
                            getString(
                                R.string.agent_start_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                binding.connectionStatusText.text = getString(R.string.status_connecting)
            }

            DeviceRole.CONTROLLER -> {
                if (SignalingRepository.state.value.phase != ConnectionPhase.ONLINE) {
                    SignalingRepository.connect(config)
                }
            }
        }
    }

    /**
     * 被控端必须进电池白名单，否则息屏进 Doze 后进程被冻结，
     * 信令定时掉线，A 端看到的就是「设备时有时无」。
     * 已豁免时系统不会再弹，所以放在自动启动里也不会反复打扰。
     */
    private fun ensureBatteryOptimizationIgnored() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    /**
     * 双开空间/应用分身里运行没有意义：设备身份会重复，控制端会看到两个同名设备，
     * 被控端两个实例还会抢屏幕采集。这里只提示并退出，不提供任何可用入口。
     */
    private fun showSpaceBlocked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.space_blocked_title)
            .setMessage(R.string.space_blocked_message)
            .setCancelable(false)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .show()
    }

    private fun renderBuildInfo() {
        binding.buildInfoText.text = getString(
            R.string.build_info_format,
            Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
        )
    }

    override fun onResume() {
        super.onResume()
        if (spaceBlocked) {
            return
        }
        renderDiagnostics()
        renderRole()
        renderAccessibilityStatus()
        if (currentRoleOrNull() == DeviceRole.CONTROLLER &&
            SignalingRepository.state.value.phase == ConnectionPhase.ONLINE
        ) {
            SignalingRepository.refreshDevices()
        }
        presenceHandler.removeCallbacks(presenceTicker)
        presenceHandler.postDelayed(presenceTicker, PRESENCE_TICK_MS)
    }

    override fun onPause() {
        presenceHandler.removeCallbacks(presenceTicker)
        super.onPause()
    }

    private fun populateForm() {
        binding.serverUrlInput.setText(preferences.savedServerUrl())
        binding.tokenInput.setText(preferences.savedAuthToken())
        binding.deviceNameInput.setText(preferences.savedDeviceName())
        when (preferences.savedRoleOrNull()) {
            DeviceRole.AGENT -> setRoleSelection(DeviceRole.AGENT)
            DeviceRole.CONTROLLER -> setRoleSelection(DeviceRole.CONTROLLER)
            null -> Unit
        }
    }

    /**
     * 程序化勾选角色时不触发切换副作用，避免界面还原被当成用户操作。
     */
    private fun setRoleSelection(role: DeviceRole) {
        suppressRoleCallback = true
        when (role) {
            DeviceRole.AGENT -> binding.agentRoleButton.isChecked = true
            DeviceRole.CONTROLLER -> binding.controllerRoleButton.isChecked = true
        }
        suppressRoleCallback = false
    }

    /**
     * 角色切换立即生效：同一台手机不能同时以被控端和控制端身份在线。
     * 切到控制端必须停掉被控服务并重连；切到被控端必须放开控制端连接。
     */
    private fun applyRoleChange(role: DeviceRole) {
        preferences.saveRole(role)
        val agentWasRunning = RemoteAgentService.isRunning

        if (role == DeviceRole.CONTROLLER) {
            if (agentWasRunning) {
                RemoteAgentService.stop(this)
            }
            SignalingRepository.disconnect()
            preferences.loadConfig()?.let { SignalingRepository.connect(it) }
            if (agentWasRunning) {
                toast(getString(R.string.role_switched_to_controller))
            }
        } else {
            if (!agentWasRunning) {
                SignalingRepository.disconnect()
                val ready = preferences.loadConfig() != null
                toast(
                    getString(
                        if (ready) {
                            R.string.role_switched_to_agent
                        } else {
                            R.string.role_switched_to_agent_need_config
                        },
                    ),
                )
            }
        }
        renderRole()
    }

    private fun bindActions() {
        binding.roleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressRoleCallback) {
                return@setOnCheckedChangeListener
            }
            val role = when (checkedId) {
                R.id.agentRoleButton -> DeviceRole.AGENT
                R.id.controllerRoleButton -> DeviceRole.CONTROLLER
                else -> return@setOnCheckedChangeListener
            }
            applyRoleChange(role)
        }

        binding.saveButton.setOnClickListener {
            saveAndApply()
        }
        binding.startAgentButton.setOnClickListener {
            saveAndApply()
        }
        binding.stopAgentButton.setOnClickListener {
            RemoteAgentService.stop(this)
            SignalingRepository.disconnect()
            binding.connectionStatusText.text = getString(R.string.status_disconnected)
            renderRole()
        }
        binding.grantCaptureButton.setOnClickListener {
            val config = persistForm() ?: return@setOnClickListener
            if (config.role != DeviceRole.AGENT) {
                return@setOnClickListener
            }
            requestNotificationPermissionIfNeeded()
            val manager = getSystemService(MediaProjectionManager::class.java)
            // 与自动路径保持一致：用户点过授权按钮后，系统确认框同样交给无障碍代点，
            // 免得「自动的不用点、手动的还要点」这种不一致。
            CaptureAutoApprove.arm()
            runCatching { capturePermissionLauncher.launch(manager.createScreenCaptureIntent()) }
                .onFailure { toast(getString(R.string.settings_unavailable)) }        }
        binding.showPairingQrButton.setOnClickListener {
            showPairingQr()
        }
        binding.scanPairingButton.setOnClickListener {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt(getString(R.string.pairing_qr_prompt))
            scanPairingLauncher.launch(options)
        }
        binding.refreshDevicesButton.setOnClickListener {
            if (!SignalingRepository.refreshDevices()) {
                toast(getString(R.string.signaling_not_connected))
            }
        }
        binding.accessibilitySettingsButton.setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.batterySettingsButton.setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.capabilityCheckButton.setOnClickListener {
            showCapabilityReport()
        }
    }

    private fun showCapabilityReport() {
        val service = RemoteAccessibilityService.instance
        if (service == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.capability_dialog_title)
                .setMessage(R.string.capability_not_connected)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val report = runCatching { service.evaluateCapabilities(testGesture = true) }.getOrNull()
        if (report == null) {
            toast(getString(R.string.capability_not_connected))
            return
        }

        val yes = getString(R.string.capability_yes)
        val no = getString(R.string.capability_no)
        val unknown = getString(R.string.capability_unknown)
        val dispatchResult = when (report.gestureDispatchSucceeded) {
            true -> yes
            false -> no
            null -> unknown
        }
        val conclusion = when {
            report.gestureDispatchSucceeded == false -> getString(R.string.capability_conclusion_gesture_blocked)
            report.gesturesUsable -> getString(R.string.capability_conclusion_ok)
            else -> getString(R.string.capability_conclusion_gesture_blocked)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.capability_dialog_title)
            .setMessage(
                getString(
                    R.string.capability_report,
                    report.capabilitiesRaw,
                    if (report.canRetrieveWindowContent) yes else no,
                    if (report.canPerformGestures) yes else no,
                    dispatchResult,
                    if (report.canTakeScreenshot) yes else no,
                    conclusion,
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SignalingRepository.state.collect(::renderSignalingState)
                }
                launch {
                    SignalingRepository.devices.collect(::renderDevices)
                }
                // 现场诊断：某些机型（如荣耀）把 logcat 关了，拿不到任何日志，
                // 只能在界面上把「有没有收到信号」这层事实直接摆出来。
                launch {
                    SignalingRepository.signals.collect { signal ->
                        signalCount += 1
                        lastSignalDesc = "${signal.payload.optString("kind")} @ ${timeLabel()}"
                        renderDiagnostics()
                    }
                }
                launch {
                    SignalingRepository.errors.collect { error ->
                        lastErrorDesc = error.code
                        renderDiagnostics()
                    }
                }
            }
        }
    }

    private fun renderDiagnostics() {
        val ws = when (SignalingRepository.state.value.phase) {
            ConnectionPhase.ONLINE -> "在线"
            ConnectionPhase.CONNECTING -> "连接中"
            ConnectionPhase.RECONNECTING -> "重连中"
            ConnectionPhase.ERROR -> "错误"
            else -> "未连接"
        }
        val service = if (RemoteAgentService.isRunning) "运行中" else "未启动"
        val capture = if (RemoteAgentService.isCaptureAuthorized) "就绪" else "未授权"
        val battery = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName)
            ?.let { if (it) "已豁免" else "受限" }
            ?: "未知"
        val overlay = if (Settings.canDrawOverlays(this)) "已开" else "未开"
        // Doze 是被控端最常见的「莫名离线」原因，直接摆在诊断行上，不用再去翻日志。
        val powerManager = getSystemService(PowerManager::class.java)
        val doze = when {
            powerManager == null -> "未知"
            powerManager.isDeviceIdleMode -> "深度"
            else -> "否"
        }
        val alarm = if (RemoteAgentService.lastAlarmExact) "精确" else "降级"
        binding.diagnosticsText.text = buildString {
            append("诊断：WS ").append(ws)
            append(" / 界面收到 ").append(signalCount).append(" 条（最后 ").append(lastSignalDesc).append("）")
            append(" / 服务收到 ").append(RemoteAgentService.receivedSignals)
            append(" 条（").append(RemoteAgentService.lastSignalKind).append("）")
            append(" / ack=").append(RemoteAgentService.lastAckResult?.toString() ?: "无")
            append(" / 服务 ").append(service)
            append(" / 采集 ").append(capture)
            append("（").append(RemoteAgentService.captureProfileLabel()).append("）")
            append(" / 电池 ").append(battery)
            append(" / Doze ").append(doze)
            append(" / 闹钟 ").append(alarm)
            append(" / 悬浮窗 ").append(overlay)
            append(" / 错误 ").append(lastErrorDesc)
        }
    }

    private fun timeLabel(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun saveAndApply() {
        val config = persistForm() ?: return
        if (config.role == DeviceRole.AGENT) {
            requestNotificationPermissionIfNeeded()
            startAgentService()
        } else {
            if (RemoteAgentService.isRunning) {
                RemoteAgentService.stop(this)
            }
            SignalingRepository.connect(config)
            renderRole()
        }
    }

    private fun persistForm() = run {
        binding.serverUrlLayout.error = null
        binding.tokenLayout.error = null
        binding.deviceNameLayout.error = null

        val role = currentRoleOrNull()
        if (role == null) {
            toast(getString(R.string.role_required))
            return@run null
        }

        val serverUrl = binding.serverUrlInput.text?.toString()?.trim().orEmpty()
        val parsedUrl = normalizeServerUrl(serverUrl)
        if (parsedUrl == null) {
            binding.serverUrlLayout.error = getString(R.string.invalid_server)
            return@run null
        }
        // 回填规范化后的地址，让用户看到实际生效的形式（补全 http://、去掉尾部斜杠）。
        binding.serverUrlInput.setText(parsedUrl.toString().trimEnd('/'))

        val token = binding.tokenInput.text?.toString()?.trim().orEmpty()
        if (token.length < 16) {
            binding.tokenLayout.error = getString(R.string.invalid_token)
            return@run null
        }

        val deviceName = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        if (deviceName.isEmpty()) {
            binding.deviceNameLayout.error = getString(R.string.invalid_device_name)
            return@run null
        }

        val config = preferences.save(serverUrl, token, deviceName, role)
        if (config == null) {
            toast(getString(R.string.config_required))
        }
        config
    }

    private fun startAgentService() {
        val config = preferences.loadConfig()
        if (config == null || config.role != DeviceRole.AGENT) {
            toast(getString(R.string.agent_config_required))
            return
        }

        runCatching { RemoteAgentService.start(this) }
            .onFailure { error ->
                toast(
                    getString(
                        R.string.agent_start_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        ensureBatteryOptimizationIgnored()
        ensureOverlayPermission()
        binding.connectionStatusText.text = getString(R.string.status_connecting)
        renderRole()
    }

    /**
     * 自动代点采集授权依赖「显示在其他应用上层」：Android 10+ 禁止后台启动 Activity，
     * 只有持有该权限的应用在豁免名单里。没有它，进程重启后连授权框都拉不起来，
     * 只能退回到「用户自己打开 App 手动点」。App 本身不显示任何悬浮窗。
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            return
        }
        // 同一次运行只提示一次：用户明确点过取消后不要反复打断。
        if (overlayPromptShown) {
            return
        }
        overlayPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_go) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }.onFailure { toast(getString(R.string.settings_unavailable)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderRole() {
        val role = currentRoleOrNull()
        val agent = role == DeviceRole.AGENT
        val controller = role == DeviceRole.CONTROLLER
        binding.roleHintText.visibility = if (role == null) TextView.VISIBLE else TextView.GONE
        binding.agentSection.visibility = if (agent) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.controllerSection.visibility = if (controller) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.startAgentButton.visibility =
            if (agent && !RemoteAgentService.isRunning) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.stopAgentButton.visibility =
            if (agent && RemoteAgentService.isRunning) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.captureStatusText.setText(
            if (RemoteAgentService.isCaptureAuthorized) {
                R.string.capture_available
            } else {
                R.string.capture_unavailable
            },
        )
        renderAccessibilityStatus()
    }

    private fun renderAccessibilityStatus() {
        val enabled = RemoteAccessibilityService.isConnected()
        binding.accessibilityStatusText.setText(
            if (enabled) R.string.accessibility_enabled_status else R.string.accessibility_disabled_status,
        )
        binding.accessibilityStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.brand_primary else R.color.brand_primary_dark,
            ),
        )
    }

    private fun renderSignalingState(state: SignalingState) {
        val text = when (state.phase) {
            ConnectionPhase.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionPhase.CONNECTING -> getString(R.string.status_connecting)
            ConnectionPhase.ONLINE -> getString(R.string.status_online)
            ConnectionPhase.RECONNECTING -> getString(R.string.status_reconnecting)
            ConnectionPhase.ERROR -> getString(R.string.connection_failed, state.detail.orEmpty())
        }
        binding.connectionStatusText.text = text

        if (state.phase == ConnectionPhase.ONLINE && currentRoleOrNull() == DeviceRole.CONTROLLER) {
            SignalingRepository.refreshDevices()
        }
        renderRole()
    }

    private fun renderDevices(devices: List<DeviceInfo>) {
        latestDevices = devices
        binding.deviceListContainer.removeAllViews()
        val agents = devices.filter {
            it.role == DeviceRole.AGENT && it.deviceId != preferences.deviceId
        }
        binding.emptyDevicesText.visibility =
            if (agents.isEmpty()) TextView.VISIBLE else TextView.GONE

        for (device in agents) {
            val presence = presenceLabel(device)
            val button = MaterialButton(this).apply {
                text = getString(R.string.connect_device_with_presence, device.deviceName, presence)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, RemoteSessionActivity::class.java)
                            .putExtra(
                                RemoteSessionActivity.EXTRA_TARGET_DEVICE_ID,
                                device.deviceId,
                            )
                            .putExtra(
                                RemoteSessionActivity.EXTRA_TARGET_DEVICE_NAME,
                                device.deviceName,
                            ),
                    )
                }
            }
            binding.deviceListContainer.addView(button)
        }
    }

    /**
     * 三档在线状态，按「距最后一次上报多久」算，而不是按「连接还在不在」算。
     *
     * 这是关键区别：连接可能还挂在服务端，但设备已经进 Doze 或被 ROM 冻结，
     * 点进去必然连不上。旧实现只要连接还在就显示在线，等于骗用户。
     *
     * 拿不到 lastSeenAt（服务端还是旧版本）时退回「在线」，不能因为缺字段就说人家失联。
     */
    private fun presenceLabel(device: DeviceInfo): String {
        val raw = device.lastSeenAt ?: return getString(R.string.presence_online)
        val seenAt = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: return getString(R.string.presence_online)
        // 服务端与本机时钟可能有几秒偏差，算出负数时按刚上报处理。
        val minutes = ((System.currentTimeMillis() - seenAt) / 60_000).coerceAtLeast(0).toInt()
        return when {
            minutes < PRESENCE_ONLINE_WINDOW_MINUTES -> getString(R.string.presence_online)
            minutes < PRESENCE_LOST_WINDOW_MINUTES -> getString(R.string.presence_minutes_ago, minutes)
            else -> getString(R.string.presence_lost)
        }
    }

    private fun currentRoleOrNull(): DeviceRole? = when {
        binding.agentRoleButton.isChecked -> DeviceRole.AGENT
        binding.controllerRoleButton.isChecked -> DeviceRole.CONTROLLER
        else -> null
    }

    private fun showPairingQr() {
        val config = persistForm() ?: return
        if (config.role != DeviceRole.AGENT) {
            toast(getString(R.string.pairing_config_required))
            return
        }

        val payload = JSONObject()
            .put("version", 1)
            .put("server", config.serverUrl.toString().trimEnd('/'))
            .put("token", config.authToken)
            .put("deviceId", config.deviceId)
            .put("deviceName", config.deviceName)
            .put("role", DeviceRole.AGENT.wireValue)

        val bitmap = runCatching {
            BarcodeEncoder().encodeBitmap(payload.toString(), BarcodeFormat.QR_CODE, 720, 720)
        }.getOrElse {
            toast(getString(R.string.pairing_invalid))
            return
        }

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_qr_title)
            .setMessage(R.string.pairing_qr_prompt)
            .setView(imageView)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun applyPairingPayload(content: String) {
        val payload = runCatching { JSONObject(content) }.getOrNull()
        if (payload == null || payload.optInt("version") != 1) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        val server = payload.optString("server")
        val token = payload.optString("token")
        val targetName = payload.optString("deviceName", "B 设备")
        val parsedUrl = normalizeServerUrl(server)
        if (parsedUrl == null || token.length < 16) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        binding.serverUrlInput.setText(server)
        binding.tokenInput.setText(token)
        setRoleSelection(DeviceRole.CONTROLLER)

        val ownName = preferences.savedDeviceName()
        val config = preferences.save(server, token, ownName, DeviceRole.CONTROLLER)
        if (config == null) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        if (RemoteAgentService.isRunning) {
            RemoteAgentService.stop(this)
        }
        SignalingRepository.connect(config)
        renderRole()
        toast(getString(R.string.pairing_success, targetName))
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 本地重算在线状态的节奏。不发网络请求，只是一次文本重排。 */
        const val PRESENCE_TICK_MS = 30_000L

        /** 距最后一次上报多久以内算「在线」，与被控端的心跳周期一致。 */
        const val PRESENCE_ONLINE_WINDOW_MINUTES = 10

        /** 超过多久算「已失联」，等于 3 个心跳周期，和服务端的判定阈值对齐。 */
        const val PRESENCE_LOST_WINDOW_MINUTES = 30
    }
}
