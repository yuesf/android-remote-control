package com.yuesf.remotecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import com.yuesf.remotecontrol.accessibility.RemoteAccessibilityService
import com.yuesf.remotecontrol.agent.RemoteAgentService
import com.yuesf.remotecontrol.data.AppPreferences
import com.yuesf.remotecontrol.data.DeviceRole
import com.yuesf.remotecontrol.databinding.ActivityMainBinding
import com.yuesf.remotecontrol.signaling.ConnectionPhase
import com.yuesf.remotecontrol.signaling.DeviceInfo
import com.yuesf.remotecontrol.signaling.SignalingRepository
import com.yuesf.remotecontrol.signaling.SignalingState
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        populateForm()
        renderBuildInfo()
        bindActions()
        observeSignaling()
        renderRole()
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
        renderRole()
        renderAccessibilityStatus()
        if (currentRole() == DeviceRole.CONTROLLER &&
            SignalingRepository.state.value.phase == ConnectionPhase.ONLINE
        ) {
            SignalingRepository.refreshDevices()
        }
    }

    private fun populateForm() {
        binding.serverUrlInput.setText(preferences.savedServerUrl())
        binding.tokenInput.setText(preferences.savedAuthToken())
        binding.deviceNameInput.setText(preferences.savedDeviceName())
        when (preferences.savedRole()) {
            DeviceRole.AGENT -> binding.agentRoleButton.isChecked = true
            DeviceRole.CONTROLLER -> binding.controllerRoleButton.isChecked = true
        }
    }

    private fun bindActions() {
        binding.roleGroup.setOnCheckedChangeListener { _, _ ->
            binding.connectionStatusText.text = getString(R.string.status_disconnected)
            renderRole()
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
            }
        }
    }

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

        val serverUrl = binding.serverUrlInput.text?.toString()?.trim().orEmpty()
        val parsedUrl = serverUrl.toHttpUrlOrNull()
        if (parsedUrl == null || (parsedUrl.scheme != "http" && parsedUrl.scheme != "https")) {
            binding.serverUrlLayout.error = getString(R.string.invalid_server)
            return@run null
        }

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

        val config = preferences.save(serverUrl, token, deviceName, currentRole())
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
        binding.connectionStatusText.text = getString(R.string.status_connecting)
        renderRole()
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
        val agent = currentRole() == DeviceRole.AGENT
        binding.agentSection.visibility = if (agent) LinearLayout.VISIBLE else LinearLayout.GONE
        binding.controllerSection.visibility = if (agent) LinearLayout.GONE else LinearLayout.VISIBLE
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

        if (state.phase == ConnectionPhase.ONLINE && currentRole() == DeviceRole.CONTROLLER) {
            SignalingRepository.refreshDevices()
        }
        renderRole()
    }

    private fun renderDevices(devices: List<DeviceInfo>) {
        binding.deviceListContainer.removeAllViews()
        val agents = devices.filter {
            it.role == DeviceRole.AGENT && it.deviceId != preferences.deviceId
        }
        binding.emptyDevicesText.visibility =
            if (agents.isEmpty()) TextView.VISIBLE else TextView.GONE

        for (device in agents) {
            val button = MaterialButton(this).apply {
                text = getString(R.string.connect_device, device.deviceName)
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

    private fun currentRole(): DeviceRole =
        if (binding.agentRoleButton.isChecked) DeviceRole.AGENT else DeviceRole.CONTROLLER

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
        val parsedUrl = server.toHttpUrlOrNull()
        if (parsedUrl == null ||
            (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") ||
            token.length < 16
        ) {
            toast(getString(R.string.pairing_invalid))
            return
        }

        binding.serverUrlInput.setText(server)
        binding.tokenInput.setText(token)
        binding.controllerRoleButton.isChecked = true

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
}
