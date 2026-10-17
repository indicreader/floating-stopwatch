// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.floatingapp.india.databinding.ActivityMainBinding
import com.floatingapp.india.service.FloatingStopwatchService
import com.floatingapp.india.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedMode: String = FloatingStopwatchService.MODE_STOPWATCH

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchOverlayService()
        } else {
            Toast.makeText(this, "Notification permission is recommended for timer service", Toast.LENGTH_SHORT).show()
            launchOverlayService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTopSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.cardOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPermission.setOnClickListener {
            requestOverlayPermission()
        }

        // Setup Practice Timer & Countdown Mode Cards
        binding.cardPresetStopwatch.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_STOPWATCH)
            checkPermissionsAndLaunch()
        }

        binding.cardPresetCountdown.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_COUNTDOWN)
            checkPermissionsAndLaunch()
        }

        binding.btnLaunchOverlay.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_STOPWATCH)
            checkPermissionsAndLaunch()
        }

        binding.btnLaunchCountdown.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_COUNTDOWN)
            checkPermissionsAndLaunch()
        }

        updateCardSelectionUI()
    }

    private fun selectMode(mode: String) {
        selectedMode = mode
        updateCardSelectionUI()
    }

    private fun updateCardSelectionUI() {
        val colorSelectedStroke = android.graphics.Color.parseColor("#00E676")
        val colorNormalStroke = android.graphics.Color.parseColor("#24342C")
        val colorCountdownStroke = android.graphics.Color.parseColor("#FFAB00")

        binding.cardPresetStopwatch.strokeColor = if (selectedMode == FloatingStopwatchService.MODE_STOPWATCH) colorSelectedStroke else colorNormalStroke
        binding.cardPresetCountdown.strokeColor = if (selectedMode == FloatingStopwatchService.MODE_COUNTDOWN) colorCountdownStroke else colorNormalStroke

        binding.cardPresetStopwatch.strokeWidth = if (selectedMode == FloatingStopwatchService.MODE_STOPWATCH) 4 else 2
        binding.cardPresetCountdown.strokeWidth = if (selectedMode == FloatingStopwatchService.MODE_COUNTDOWN) 4 else 2
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (hasOverlay) {
            binding.cardPermissionBanner.visibility = View.GONE
            binding.btnLaunchOverlay.isEnabled = true
            binding.btnLaunchOverlay.alpha = 1.0f
            binding.btnLaunchCountdown.isEnabled = true
            binding.btnLaunchCountdown.alpha = 1.0f
        } else {
            binding.cardPermissionBanner.visibility = View.VISIBLE
            binding.tvPermissionStatus.setText(R.string.status_permission_denied)
            binding.btnLaunchOverlay.isEnabled = false
            binding.btnLaunchOverlay.alpha = 0.5f
            binding.btnLaunchCountdown.isEnabled = false
            binding.btnLaunchCountdown.alpha = 0.5f
        }
    }

    private fun checkPermissionsAndLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        launchOverlayService()
    }

    private fun launchOverlayService() {
        val intent = Intent(this, FloatingStopwatchService::class.java).apply {
            putExtra(FloatingStopwatchService.EXTRA_MODE, selectedMode)
        }
        ContextCompat.startForegroundService(this, intent)

        val modeLabel = if (selectedMode == FloatingStopwatchService.MODE_COUNTDOWN) "Countdown Timer" else "Practice Stopwatch"
        Toast.makeText(this, "$modeLabel Launched", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
