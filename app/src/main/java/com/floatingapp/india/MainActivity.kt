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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import com.floatingapp.india.databinding.ActivityMainBinding
import com.floatingapp.india.manager.DrillTagRepository
import com.floatingapp.india.service.FloatingStopwatchService
import com.floatingapp.india.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drillTagRepo: DrillTagRepository
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
        drillTagRepo = DrillTagRepository(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge system window insets & display cutouts so top bar & settings button
        // are safely below the camera cutout/punch-hole, and bottom cards are above navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnTopSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.cardOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPermission.setOnClickListener {
            requestOverlayPermission()
        }

        // Setup Configurable Drill Tag Selector (<Tag> - <Number>)
        setupDrillTagSelector()

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

        binding.btnLaunchDeskStopwatch.setOnClickListener {
            launchDeskMode(FloatingStopwatchService.MODE_STOPWATCH)
        }

        binding.btnLaunchCountdown.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_COUNTDOWN)
            checkPermissionsAndLaunch()
        }

        binding.btnLaunchDeskCountdown.setOnClickListener {
            launchDeskMode(FloatingStopwatchService.MODE_COUNTDOWN)
        }

        updateCardSelectionUI()
    }

    private fun setupDrillTagSelector() {
        binding.btnTagNumMinus.setOnClickListener {
            drillTagRepo.decrementNumber()
            updateDrillTagDisplay()
        }

        binding.btnTagNumPlus.setOnClickListener {
            drillTagRepo.incrementNumber()
            updateDrillTagDisplay()
        }

        refreshDrillTagUI()
    }

    private fun updateDrillTagDisplay() {
        binding.tvTagNumDisplay.text = drillTagRepo.getActiveNumber().toString()
        binding.tvActiveTagPreview.text = drillTagRepo.getFormattedSessionTag()
    }

    private fun refreshDrillTagUI() {
        val presets = drillTagRepo.getTagPresets()
        val items = presets.toMutableList().apply { add("+ Add Custom Tag...") }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(android.graphics.Color.parseColor("#E6ECE9"))
                view.textSize = 14f
                view.setPadding(4, 0, 4, 0)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(android.graphics.Color.parseColor("#E6ECE9"))
                view.setBackgroundColor(android.graphics.Color.parseColor("#17221D"))
                view.setPadding(24, 24, 24, 24)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDrillTag.adapter = adapter

        val activeTag = drillTagRepo.getActiveTag()
        val activeIndex = presets.indexOf(activeTag)
        if (activeIndex != -1) {
            binding.spinnerDrillTag.setSelection(activeIndex)
        }

        binding.spinnerDrillTag.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = items[position]
                if (selected == "+ Add Custom Tag...") {
                    promptAddCustomTag()
                } else {
                    drillTagRepo.setActiveTag(selected)
                    updateDrillTagDisplay()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateDrillTagDisplay()
    }

    private fun promptAddCustomTag() {
        val input = EditText(this).apply {
            hint = "e.g. Test, Module, Set"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Custom Drill Tag")
            .setMessage("Enter a new prefix to identify practice drill sections:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val custom = input.text.toString().trim()
                if (custom.isNotEmpty()) {
                    drillTagRepo.addCustomTag(custom)
                }
                refreshDrillTagUI()
            }
            .setNegativeButton("Cancel") { _, _ ->
                refreshDrillTagUI()
            }
            .setOnCancelListener {
                refreshDrillTagUI()
            }
            .show()
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
        updateDrillTagDisplay()
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

    private fun launchDeskMode(mode: String) {
        selectMode(mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val serviceIntent = Intent(this, FloatingStopwatchService::class.java).apply {
            action = FloatingStopwatchService.ACTION_HIDE_OVERLAY
            putExtra(FloatingStopwatchService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        val deskIntent = Intent(this, com.floatingapp.india.ui.desk.DeskModeActivity::class.java)
        startActivity(deskIntent)
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
