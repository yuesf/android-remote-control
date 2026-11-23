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
import android.view.View
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
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.data.normalizeServerUrl
import com.yuesf.remotecontrol.databinding.ActivityMainBinding
import com.yuesf.remotecontrol.signaling.CodeResolution
import com.yuesf.remotecontrol.signaling.ConnectionPhase
import com.yuesf.remotecontrol.signaling.DeviceInfo
import com.yuesf.remotecontrol.signaling.HeartbeatPolicy
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

    private var signalCount = 0
    private var lastSignalDesc = "无"
    private var lastErrorDesc = "无"

    /** 正在解析的配对码：服务端回 code-resolved 时用它过滤掉残留/他人的结果。 */
    private var pendingPairCode: String? = null

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
        renderSections()
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
        renderSections()
        autoStartIfConfigured()
    }

    /**
     * 打开 App 就恢复连接并拉起被控服务。
     *
     * 改造后不再区分角色：每台设备都必须常驻「可被控」能力（信令 + 采集 + 无障碍），
     * 否则对方输你的配对码也连不上；同时控制别人的能力在需要时才动用。
     * 以前按角色二选一，导致控制端机器上根本没有被控服务，反过来连它就连不上。
     */
    private fun autoStartIfConfigured() {
        val config = preferences.loadConfig() ?: return
        requestNotificationPermissionIfNeeded()
        ensureBatteryOptimizationIgnored()
        ensureOverlayPermission()
        if (!RemoteAgentService.isRunning) {
            runCatching { RemoteAgentService.start(this) }
                .onFailure { error ->
                    toast(
                        getString(
                            R.string.agent_start_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
        }
        if (SignalingRepository.state.value.phase != ConnectionPhase.ONLINE) {
            SignalingRepository.connect(config)
        }
        binding.connectionStatusText.setText(R.string.status_connecting)
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
        renderSections()
        renderAccessibilityStatus()
        if (SignalingRepository.state.value.phase == ConnectionPhase.ONLINE) {
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
    }

    private fun bindActions() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showPage(PAGE_HOME)
                R.id.nav_devices -> showPage(PAGE_DEVICES)
                R.id.nav_settings -> showPage(PAGE_SETTINGS)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        showPage(PAGE_HOME)

        binding.connectByCodeButton.setOnClickListener {
            connectByPairCode()
        }
        // 输完两位数字直接回车就能连，不用再去点按钮。
        binding.pairCodeInput.setOnEditorActionListener { _, _, _ ->
            connectByPairCode()
            true
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
            renderSections()
        }
        binding.grantCaptureButton.setOnClickListener {
            val config = persistForm() ?: return@setOnClickListener
            requestNotificationPermissionIfNeeded()
            val manager = getSystemService(MediaProjectionManager::class.java)
            // 手动路径：弹窗原样留给用户自己按「允许」，无障碍不要碰。
            // 之前这里也让无障碍代点，代点算错坐标就点了对话框外面，
            // 系统当成用户取消把框关掉，用户只看到「未授予屏幕采集权限」。
            CaptureAutoApprove.armManual()
            runCatching { capturePermissionLauncher.launch(manager.createScreenCaptureIntent()) }
                .onFailure { toast(getString(R.string.settings_unavailable)) }
        }
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

    /**
     * 三个导航页共用一个 Activity，用可见性切换，不上 Fragment。
     *
     * 这些页面之间没有独立的状态需要保存，切页只是换一块内容看；
     * 用 Fragment 反而要把现有的一大堆视图引用拆散，收益为零。
     * 切到某页时顺手把该页需要的最新数据刷一遍（设备列表 / 诊断行），
     * 否则停在别的页面上时数据变了，切回来看到的还是旧的。
     */
    private fun showPage(page: Int) {
        binding.homePage.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        binding.devicesPage.visibility = if (page == PAGE_DEVICES) View.VISIBLE else View.GONE
        binding.settingsPage.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.GONE

        when (page) {
            PAGE_DEVICES -> {
                if (SignalingRepository.state.value.phase == ConnectionPhase.ONLINE) {
                    SignalingRepository.refreshDevices()
                }
                renderPairCode(SignalingRepository.pairCode.value)
                renderDevices(latestDevices)
            }

            PAGE_SETTINGS -> {
                renderSections()
                renderDiagnostics()
            }
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
                launch {
                    SignalingRepository.pairCode.collect { code ->
                        renderPairCode(code)
                        renderDiagnostics()
                    }
                }
                launch {
                    SignalingRepository.codeResolutions.collect(::handleCodeResolved)
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
                        // 码输错是最常见的用户失误，必须立刻给明确反馈，
                        // 而不是只把错误码塞进诊断行里让人自己找。
                        if (error.code == "CODE_NOT_FOUND" && pendingPairCode != null) {
                            pendingPairCode = null
                            binding.pairCodeInputLayout.error =
                                getString(R.string.pair_code_not_found)
                        }
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
        // 不再有角色分支：每台设备都常驻被控能力，这些指标全部相关。
        // 以前只在被控端显示，结果控制端机器上「采集未授权」这种真问题被藏了起来。
        binding.diagnosticsText.text = buildString {
            append("诊断：WS ").append(ws)
            append(" / 本机码 ").append(SignalingRepository.pairCode.value ?: "--")
            append(" / 界面收到 ").append(signalCount).append(" 条（最后 ").append(lastSignalDesc).append("）")
            append(" / 服务收到 ").append(RemoteAgentService.receivedSignals)
            append(" 条（").append(RemoteAgentService.lastSignalKind).append("）")
            append(" / ack=").append(RemoteAgentService.lastAckResult?.toString() ?: "无")
            append(" / 服务 ").append(service)
            append(" / 采集 ").append(capture)
            append("（").append(RemoteAgentService.captureProfileLabel()).append("）")
            append(" / 电池 ").append(battery)
            append(" / Doze ").append(doze)
            append(" / Wi-Fi锁 ").append(RemoteAgentService.wifiLockLabel())
            append(" / 闹钟 ").append(alarm)
            append(" / 悬浮窗 ").append(overlay)
            append(" / 错误 ").append(lastErrorDesc)
        }
    }

    private fun timeLabel(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun saveAndApply() {
        persistForm() ?: return
        requestNotificationPermissionIfNeeded()
        startAgentService()
    }

    private fun persistForm() = run {
        binding.serverUrlLayout.error = null
        binding.tokenLayout.error = null
        binding.deviceNameLayout.error = null

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

        val config = preferences.save(serverUrl, token, deviceName)
        if (config == null) {
            toast(getString(R.string.config_required))
        }
        config
    }

    private fun startAgentService() {
        if (preferences.loadConfig() == null) {
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
        binding.connectionStatusText.setText(R.string.status_connecting)
        renderSections()
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

    /**
     * 两块卡片现在都常显：这台手机既能被控（要能接别人的连接），
     * 也能主动去控别人（输对方的码）。真正区分两者的只有「这次是往哪个方向发起」。
     */
    private fun renderSections() {
        binding.agentSection.visibility = LinearLayout.VISIBLE
        binding.controllerSection.visibility = LinearLayout.VISIBLE
        binding.startAgentButton.visibility =
            if (!RemoteAgentService.isRunning) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.stopAgentButton.visibility =
            if (RemoteAgentService.isRunning) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.captureStatusText.setText(
            if (RemoteAgentService.isCaptureAuthorized) {
                R.string.capture_available
            } else {
                R.string.capture_unavailable
            },
        )
        renderPairCode(SignalingRepository.pairCode.value)
        renderAccessibilityStatus()
    }

    private fun renderPairCode(code: String?) {
        binding.pairCodeText.text =
            code ?: getString(R.string.pair_code_pending)
    }

    /**
     * 按配对码连接。
     *
     * 流程是「发 resolve_code → 服务端回 code-resolved → 拿 deviceId 开会话」，
     * 而不是本地比对设备列表：码的归属只有服务端知道，列表可能还没刷新。
     */
    private fun connectByPairCode() {
        val code = binding.pairCodeInput.text?.toString()?.trim().orEmpty()
        if (code.length != 2 || !code.all { it.isDigit() }) {
            binding.pairCodeInputLayout.error = getString(R.string.pair_code_invalid)
            return
        }
        binding.pairCodeInputLayout.error = null

        if (SignalingRepository.state.value.phase != ConnectionPhase.ONLINE) {
            toast(getString(R.string.signaling_not_connected))
            return
        }
        if (code == SignalingRepository.pairCode.value) {
            toast(getString(R.string.pair_code_self))
            return
        }

        pendingPairCode = code
        if (!SignalingRepository.resolveCode(code)) {
            pendingPairCode = null
            toast(getString(R.string.signaling_not_connected))
        }
    }

    private fun handleCodeResolved(resolution: CodeResolution) {
        // 只处理自己发起的那次查询；服务端或其他逻辑也可能带回结果。
        if (pendingPairCode != null && pendingPairCode != resolution.code) {
            return
        }
        pendingPairCode = null

        if (!resolution.online) {
            toast(getString(R.string.pair_code_offline, resolution.deviceName))
            return
        }
        if (resolution.deviceId == preferences.deviceId) {
            toast(getString(R.string.pair_code_self))
            return
        }
        binding.pairCodeInput.setText("")
        startActivity(
            Intent(this, RemoteSessionActivity::class.java)
                .putExtra(RemoteSessionActivity.EXTRA_TARGET_DEVICE_ID, resolution.deviceId)
                .putExtra(RemoteSessionActivity.EXTRA_TARGET_DEVICE_NAME, resolution.deviceName),
        )
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

        if (state.phase == ConnectionPhase.ONLINE) {
            SignalingRepository.refreshDevices()
        }
        // 诊断行必须跟着连接状态刷新。之前只在「收到信号/报错」时重绘，
        // 于是重连成功后仍停在「WS 重连中」，把正常状态显示成故障。
        renderDiagnostics()
        renderSections()
    }

    private fun renderDevices(devices: List<DeviceInfo>) {
        latestDevices = devices
        binding.deviceListContainer.removeAllViews()
        // 不再按角色筛：任何一台在线的其他设备都可以被连，首选入口是配对码，
        // 但列表仍是「不知道对方码时」的兜底。
        val others = devices.filter { it.deviceId != preferences.deviceId }
        binding.emptyDevicesText.visibility =
            if (others.isEmpty()) TextView.VISIBLE else TextView.GONE

        for (device in others) {
            val presence = presenceLabel(device)
            val button = MaterialButton(this).apply {
                text = if (device.pairCode != null) {
                    getString(
                        R.string.connect_device_with_code,
                        device.pairCode,
                        device.deviceName,
                        presence,
                    )
                } else {
                    getString(R.string.connect_device_with_presence, device.deviceName, presence)
                }
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
        // 阈值必须跟**保活周期**对齐，不是业务心跳。
        // lastSeenAt 是 B 每轮保活时刷新的（10 分钟），如果拿 60 分钟的业务心跳
        // 去算窗口，非峰值时段会长时间显示「N 分钟前」，用户以为快失联了。
        val keepAliveMinutes =
            (HeartbeatPolicy.KEEP_ALIVE_INTERVAL_MS / 60_000).coerceAtLeast(1).toInt()
        val onlineWindow = keepAliveMinutes + 2
        val lostWindow = keepAliveMinutes * HeartbeatPolicy.MAX_MISSED_HEARTBEATS
        return when {
            minutes <= onlineWindow -> getString(R.string.presence_online)
            minutes < lostWindow -> getString(R.string.presence_minutes_ago, minutes)
            else -> getString(R.string.presence_lost)
        }
    }

    private fun showPairingQr() {
        val config = persistForm() ?: return

        // 二维码只用于「快速导入服务器配置」，不再承担角色声明——
        // 真正决定连哪台设备的是两位配对码，扫码方连上后自己输即可。
        val payload = JSONObject()
            .put("version", 1)
            .put("server", config.serverUrl.toString().trimEnd('/'))
            .put("token", config.authToken)
            .put("deviceName", config.deviceName)
            .put("pairCode", SignalingRepository.pairCode.value)

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
        val pairCode = SignalingRepository.pairCode.value
        val message = if (pairCode != null) {
            getString(R.string.pairing_qr_prompt_with_code, pairCode)
        } else {
            getString(R.string.pairing_qr_prompt)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pairing_qr_title)
            .setMessage(message)
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
        val parsedUrl = normalizeServerUrl(server)
        if (parsedUrl == null || token.length < 16) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        binding.serverUrlInput.setText(server)
        binding.tokenInput.setText(token)

        val ownName = preferences.savedDeviceName()
        val config = preferences.save(server, token, ownName)
        if (config == null) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        SignalingRepository.connect(config)
        renderSections()

        // 码在二维码里就顺手填进输入框，用户点一下「连接」即可。
        val pairCode = payload.optString("pairCode").takeIf { it.length == 2 }
        if (pairCode != null) {
            binding.pairCodeInput.setText(pairCode)
            // 输入框在首页，扫完码还停在「设备」页的话用户看不到已填入，
            // 会以为没扫上。这里直接把导航切回首页。
            binding.bottomNav.selectedItemId = R.id.nav_home
            toast(getString(R.string.pairing_success_with_code, pairCode))
        } else {
            toast(getString(R.string.pairing_success, payload.optString("deviceName", "设备")))
        }
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

        const val PAGE_HOME = 0
        const val PAGE_DEVICES = 1
        const val PAGE_SETTINGS = 2
    }
}
