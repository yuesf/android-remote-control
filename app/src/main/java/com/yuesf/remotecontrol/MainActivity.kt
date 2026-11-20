package com.yuesf.remotecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferences(this)

        populateForm()
        bindActions()
        observeSignaling()
        renderRole()
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
            startAgentService()
        }
        binding.stopAgentButton.setOnClickListener {
            RemoteAgentService.stop(this)
            SignalingRepository.disconnect()
            binding.connectionStatusText.text = getString(R.string.status_disconnected)
            renderRole()
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
                    val sent = SignalingRepository.sendControlRequest(device.deviceId)
                    toast(
                        if (sent) {
                            getString(R.string.control_request_sent, device.deviceName)
                        } else {
                            getString(R.string.signaling_not_connected)
                        },
                    )
                }
            }
            binding.deviceListContainer.addView(button)
        }
    }

    private fun currentRole(): DeviceRole =
        if (binding.agentRoleButton.isChecked) DeviceRole.AGENT else DeviceRole.CONTROLLER

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.settings_unavailable)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
