package com.yuesf.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.yuesf.remotecontrol.agent.SignalingKeepAliveService
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.ConnectedDevice
import com.yuesf.remotecontrol.debug.FileLog
import com.yuesf.remotecontrol.data.SpaceGuard
import com.yuesf.remotecontrol.data.UpdateChecker
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
import java.io.File
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

    /** 更新检查：当前检查到的可用更新；null 表示无更新/检查失败。 */
    private var availableUpdate: UpdateChecker.UpdateInfo? = null
    private var updateCheckInFlight = false
    private var updateDownloadInFlight = false

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
                renderRecentDevices()
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
     * 打开 App 就恢复信令连接（在线待控）。
     *
     * 信令连接与被控服务是两回事：这里只拉起轻量信令保活服务（收控制请求 +
     * 保活三件套），被控服务由信令服务在收到 control-request 时按需拉起。
     */
    private fun autoStartIfConfigured() {
        val config = preferences.loadConfig() ?: return
        requestNotificationPermissionIfNeeded()
        ensureBatteryOptimizationIgnored()
        ensureOverlayPermission()
        if (!SignalingKeepAliveService.isRunning) {
            runCatching { SignalingKeepAliveService.start(this) }
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
        // 顶栏状态完全由 observeSignaling 的状态流驱动，不要手动 setText：
        // 状态早就是 ONLINE 时手动写「连接中」，StateFlow 值不变不会重新发射，
        // 顶栏就永远停在「连接中」，而链路其实是通的。
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

    // ===================== 版本检查与更新 =====================

    /** 设置页展示本机版本；每次进设置页都刷新，避免升级后显示旧号。 */
    private fun renderCurrentVersion() {
        binding.updateCurrentVersionText.text = getString(
            R.string.update_current_version,
            UpdateChecker.currentVersionLabel(this),
        )
    }

    /**
     * 进入设置页自动检查一次。加 in-flight 保护：切页/旋转不会重复发请求。
     * 结果直接落到 UI；失败只在状态行提示，不弹窗打扰。
     */
    private fun checkForUpdate() {
        if (updateCheckInFlight || updateDownloadInFlight) return
        updateCheckInFlight = true
        binding.updateStatusText.setText(R.string.update_checking)
        binding.updateNotesText.visibility = View.GONE
        binding.updateButton.visibility = View.GONE
        lifecycleScope.launch {
            val result = UpdateChecker.check()
            updateCheckInFlight = false
            availableUpdate = null
            when (result) {
                is UpdateChecker.CheckResult.Available -> {
                    availableUpdate = result.info
                    binding.updateStatusText.text = getString(
                        R.string.update_available,
                        result.info.versionName,
                    )
                    if (result.info.releaseNotes.isNotBlank()) {
                        binding.updateNotesText.text = result.info.releaseNotes
                        binding.updateNotesText.visibility = View.VISIBLE
                    }
                    binding.updateButton.visibility = View.VISIBLE
                }

                UpdateChecker.CheckResult.UpToDate -> {
                    binding.updateStatusText.setText(R.string.update_up_to_date)
                }

                is UpdateChecker.CheckResult.Failed -> {
                    binding.updateStatusText.text = result.message
                }
            }
        }
    }

    /** 下载 APK 并拉起系统安装器。全程在协程里，进度回写按钮文字。 */
    private fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        if (updateDownloadInFlight) return
        if (!packageManager.canRequestPackageInstalls()) {
            toast(getString(R.string.update_install_unknown_blocked))
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            return
        }
        updateDownloadInFlight = true
        binding.updateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val apk = UpdateChecker.downloadApk(this@MainActivity, info) { progress ->
                    binding.updateButton.text = getString(R.string.update_downloading, progress)
                }
                binding.updateButton.setText(R.string.update_button)
                binding.updateButton.isEnabled = true
                installApk(apk)
            } catch (e: Exception) {
                binding.updateButton.setText(R.string.update_button)
                binding.updateButton.isEnabled = true
                binding.updateStatusText.text = getString(
                    R.string.remote_session_error,
                    e.message ?: e.javaClass.simpleName,
                )
            } finally {
                updateDownloadInFlight = false
            }
        }
    }

    /** 用 FileProvider 授权 cacheDir 里的 APK 给系统安装器。 */
    private fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    override fun onResume() {
        super.onResume()
        if (spaceBlocked) {
            return
        }
        renderDiagnostics()
        renderSections()
        renderAccessibilityStatus()
        renderRecentDevices()
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
        setupPairCodeCells()

        binding.saveButton.setOnClickListener {
            // 全自动：保存配置后直接确保信令服务在跑、连接用的是新配置。
            // deviceId 不变（换服务器/令牌不会换码）；服务已运行时 onStartCommand
            // 会用新配置重建连接，保活闹钟后续也按新配置走。没有手动上下线入口。
            persistForm() ?: return@setOnClickListener
            toast(getString(R.string.config_saved))
            startAgentService()
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
        binding.releaseCaptureButton.setOnClickListener {
            if (RemoteAgentService.releaseCapture()) {
                toast(getString(R.string.capture_released))
            } else {
                toast(getString(R.string.capture_release_unavailable))
            }
            renderSections()
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
        binding.updateButton.setOnClickListener {
            availableUpdate?.let(::downloadAndInstall)
        }
        binding.recheckUpdateButton.setOnClickListener {
            checkForUpdate()
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
            PAGE_HOME -> {
                renderRecentDevices()
            }

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
                renderCurrentVersion()
                checkForUpdate()
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
                    SignalingRepository.devices.collect { devices ->
                        renderDevices(devices)
                        renderRecentDevices()
                    }
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
                            showPairCodeError(getString(R.string.pair_code_not_found))
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
        val service = if (SignalingKeepAliveService.isRunning) "信令运行中" else "未启动"
        val agentSvc = if (RemoteAgentService.isRunning) "被控中" else "空闲"
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
        val alarm = if (SignalingKeepAliveService.lastAlarmExact) "精确" else "降级"
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
            append("（").append(agentSvc).append("）")
            append(" / 采集 ").append(capture)
            append("（").append(RemoteAgentService.captureProfileLabel()).append("）")
            append(" / 电池 ").append(battery)
            append(" / Doze ").append(doze)
            append(" / Wi-Fi锁 ").append(SignalingKeepAliveService.wifiLockLabel())
            append(" / 闹钟 ").append(alarm)
            append(" / 悬浮窗 ").append(overlay)
            append(" / 错误 ").append(lastErrorDesc)
        }
    }

    private fun timeLabel(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

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
        requestNotificationPermissionIfNeeded()

        runCatching { SignalingKeepAliveService.start(this) }
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
        binding.captureStatusText.setText(
            if (RemoteAgentService.isCaptureAuthorized) {
                R.string.capture_available
            } else {
                R.string.capture_unavailable
            },
        )
        // 只有服务在跑且采集真的就绪时才有东西可释放。
        binding.releaseCaptureButton.visibility =
            if (RemoteAgentService.isRunning && RemoteAgentService.isCaptureAuthorized) {
                LinearLayout.VISIBLE
            } else {
                LinearLayout.GONE
            }
        renderPairCode(SignalingRepository.pairCode.value)
        renderAccessibilityStatus()
    }

    private fun renderPairCode(code: String?) {
        binding.pairCodeText.text =
            code ?: getString(R.string.pair_code_pending)
    }

    /** 四个配对码格，列表顺序即码的字符顺序。 */
    private val pairCodeCells: List<EditText>
        get() = listOf(
            binding.pairCodeCell1,
            binding.pairCodeCell2,
            binding.pairCodeCell3,
            binding.pairCodeCell4,
        )

    /**
     * 四位码输入链：只收字母数字并自动转大写；填满一格自动跳下一格，
     * 空格按退格回到上一格——用户不用手动点来点去。
     */
    private fun setupPairCodeCells() {
        val lengthFilter = InputFilter.LengthFilter(1)
        val upperAlnumFilter = InputFilter { source, start, end, _, _, _ ->
            val original = source.subSequence(start, end).toString()
            val filtered = original.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
            if (filtered == original) null else filtered
        }
        pairCodeCells.forEachIndexed { index, cell ->
            cell.filters = arrayOf(lengthFilter, upperAlnumFilter)
            cell.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!s.isNullOrEmpty() && index < pairCodeCells.lastIndex) {
                        pairCodeCells[index + 1].requestFocus()
                    }
                    clearPairCodeError()
                }
            })
            cell.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    cell.text.isNullOrEmpty() && index > 0
                ) {
                    val previous = pairCodeCells[index - 1]
                    previous.requestFocus()
                    previous.setSelection(previous.text?.length ?: 0)
                    true
                } else {
                    false
                }
            }
            if (index == pairCodeCells.lastIndex) {
                cell.setOnEditorActionListener { _, actionId, event ->
                    val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN
                    if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                        connectByPairCode()
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun readPairCode(): String =
        pairCodeCells.joinToString("") { it.text?.toString()?.trim().orEmpty() }

    /** 扫码回填或清空：统一大写，逐格填入。 */
    private fun fillPairCode(code: String) {
        val normalized = code.trim().uppercase(Locale.ROOT)
        pairCodeCells.forEachIndexed { index, cell ->
            cell.setText(normalized.getOrNull(index)?.toString().orEmpty())
        }
        clearPairCodeError()
    }

    /** 错误用页面内常驻文字 + 格子的错误描边，不用一闪而过的 Toast。 */
    private fun showPairCodeError(message: String) {
        binding.pairCodeErrorText.text = message
        binding.pairCodeErrorText.visibility = View.VISIBLE
        val errorBackground = ContextCompat.getDrawable(this, R.drawable.bg_code_cell_error)
        pairCodeCells.forEach { it.background = errorBackground }
    }

    private fun clearPairCodeError() {
        if (binding.pairCodeErrorText.visibility != View.VISIBLE) return
        binding.pairCodeErrorText.text = null
        binding.pairCodeErrorText.visibility = View.GONE
        val normalBackground = ContextCompat.getDrawable(this, R.drawable.bg_code_cell)
        pairCodeCells.forEach { it.background = normalBackground }
    }

    /**
     * 按配对码连接。
     *
     * 流程是「发 resolve_code → 服务端回 code-resolved → 拿 deviceId 开会话」，
     * 而不是本地比对设备列表：码的归属只有服务端知道，列表可能还没刷新。
     */
    private fun connectByPairCode() {
        val code = readPairCode()
        if (code.length != pairCodeCells.size || !code.all { it.isLetterOrDigit() }) {
            showPairCodeError(getString(R.string.pair_code_invalid))
            return
        }
        clearPairCodeError()

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
        fillPairCode("")
        connectToDevice(resolution.deviceId, resolution.deviceName, resolution.code)
    }

    private fun renderAccessibilityStatus() {
        val enabled = RemoteAccessibilityService.isConnected()
        binding.accessibilityStatusText.setText(
            if (enabled) R.string.accessibility_enabled_status else R.string.accessibility_disabled_status,
        )
        binding.accessibilityStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.sem_online else R.color.sem_offline,
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

    /**
     * SIGNAL DECK 设备按钮：surfaceVariant 底 + 1dp outlineVariant 描边 + 12dp 圆角。
     * 用 backgroundTintList 而不是 setBackgroundColor，保留 MaterialButton 的按压涟漪。
     */
    private fun styleDeviceButton(button: MaterialButton, minHeightRes: Int) {
        button.isAllCaps = false
        button.cornerRadius = resources.getDimensionPixelSize(R.dimen.radius_md)
        button.strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_hairline)
        button.strokeColor =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_outline_variant))
        button.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_surface_variant))
        button.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface))
        button.minimumHeight = resources.getDimensionPixelSize(minHeightRes)
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
            // 采集状态由 B 随心跳上报；空闲时是关闭的，连接时 B 会自动拉起授权。
            val captureSuffix =
                if (device.captureReady == false) getString(R.string.capture_off_suffix) else ""
            val button = MaterialButton(this).apply {
                text = if (device.pairCode != null) {
                    getString(
                        R.string.connect_device_with_code,
                        device.pairCode,
                        device.deviceName,
                        presence + captureSuffix,
                    )
                } else {
                    getString(R.string.connect_device_with_presence, device.deviceName, presence + captureSuffix)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.space_sm)
                }
                setOnClickListener {
                    connectToDevice(device.deviceId, device.deviceName, device.pairCode)
                }
            }
            styleDeviceButton(button, R.dimen.touch_min)
            binding.deviceListContainer.addView(button)
        }
    }

    /** 统一的会话入口：无论从配对码、在线列表还是最近连接卡片进来，都记一笔历史。 */
    private fun connectToDevice(deviceId: String, deviceName: String, pairCode: String?) {
        preferences.saveConnectedDevice(deviceId, deviceName, pairCode)
        startActivity(
            Intent(this, RemoteSessionActivity::class.java)
                .putExtra(RemoteSessionActivity.EXTRA_TARGET_DEVICE_ID, deviceId)
                .putExtra(RemoteSessionActivity.EXTRA_TARGET_DEVICE_NAME, deviceName),
        )
    }

    /**
     * 首页「最近连接」卡片。
     *
     * 数据源是本地历史 [ConnectedDevice]，在线状态则用 [latestDevices] 按 deviceId 叠加：
     * 历史回答「连过谁」，设备列表回答「现在在不在线」，两者职责分开。
     * 离线设备也照常展示并可点击——它一上线卡片会自动变成在线，不用重新输码。
     */
    private fun renderRecentDevices() {
        val history = preferences.loadConnectedDevices()
        binding.emptyRecentText.visibility =
            if (history.isEmpty()) TextView.VISIBLE else TextView.GONE
        binding.recentDeviceListContainer.removeAllViews()

        // 一行两列：每两个设备打包成一个横向行，按钮 width=0 + weight=1 保证等宽。
        // 用嵌套 LinearLayout 而非 RecyclerView/GridLayout，改动小且不需要新 import；
        // 历史最多 10 条，静态 addView 的开销可以忽略。
        val gapPx = resources.getDimensionPixelSize(R.dimen.space_sm)
        // fontScale > 1.3 时由一行两列降为一行一列：布局降级，不是把 11sp 压到 10sp 以下。
        val columns = if (resources.configuration.fontScale > 1.3f) 1 else 2
        for (start in history.indices step columns) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = gapPx }
            }
            // 本行不满列数时，唯一的按钮占满整行，避免右侧留半块空白。
            val isLastAlone = start + columns >= history.size
            for (offset in 0 until columns) {
                val index = start + offset
                if (index >= history.size) break
                val item = history[index]
                val live = latestDevices.firstOrNull { it.deviceId == item.deviceId }
                val code = live?.pairCode ?: item.pairCode
                val presence =
                    if (live != null) presenceLabel(live) else getString(R.string.presence_lost)
                val label = if (code != null) {
                    getString(R.string.recent_device_card, item.deviceName, code, presence)
                } else {
                    getString(R.string.recent_device_card_no_code, item.deviceName, presence)
                }
                val button = MaterialButton(this).apply {
                    text = label
                    // 半宽列里长设备名容易挤爆，缩小字号并允许换行；
                    // 状态靠 ● / ○ / ⊘ 形状 + 文字双重编码，不靠字号承担可读性。
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        // 只有成对时第二列才需要左边距；落单时整行只有一个按钮，不加边距。
                        if (!isLastAlone && offset == 1) marginStart = gapPx
                    }
                    setOnClickListener {
                        connectToDevice(item.deviceId, item.deviceName, item.pairCode)
                    }
                }
                styleDeviceButton(button, R.dimen.device_card_min_height)
                row.addView(button)
            }
            binding.recentDeviceListContainer.addView(row)
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
        // 真正决定连哪台设备的是四位配对码，扫码方连上后自己输即可。
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
        val pairCode = payload.optString("pairCode").trim().uppercase(Locale.ROOT)
            .takeIf { it.length == 4 && it.all { c -> c.isLetterOrDigit() } }
        if (pairCode != null) {
            fillPairCode(pairCode)
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
