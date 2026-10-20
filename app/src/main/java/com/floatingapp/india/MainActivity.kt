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
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.content.res.Configuration
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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

        binding.btnLaunchCountdown.setOnClickListener {
            selectMode(FloatingStopwatchService.MODE_COUNTDOWN)
            checkPermissionsAndLaunch()
        }

        setupWindowInsets()
        updateCardSelectionUI()
        applyAdaptiveLayout()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                top = insets.top,
                left = insets.left,
                right = insets.right
            )
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveLayout()
    }

    private fun applyAdaptiveLayout() {
        val displayMetrics = resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        val isWide = widthDp >= 600 || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Constrain max width for ultra-wide displays/tablets to prevent visual distortion
        val maxContainerWidthPx = (760 * displayMetrics.density).toInt()
        val actualWidthPx = displayMetrics.widthPixels
        if (actualWidthPx > maxContainerWidthPx) {
            val horizontalPadding = (actualWidthPx - maxContainerWidthPx) / 2
            binding.nestedScrollView.setPadding(horizontalPadding, binding.nestedScrollView.paddingTop, horizontalPadding, binding.nestedScrollView.paddingBottom)
        } else {
            val defaultPadding = (16 * displayMetrics.density).toInt()
            binding.nestedScrollView.setPadding(defaultPadding, binding.nestedScrollView.paddingTop, defaultPadding, binding.nestedScrollView.paddingBottom)
        }

        // Adaptive arrangement for Stopwatch and Countdown cards: side-by-side on wide/landscape, stacked on compact
        val margin16 = (16 * displayMetrics.density).toInt()
        val margin8 = (8 * displayMetrics.density).toInt()

        if (isWide) {
            binding.layoutPresetsGroup.orientation = LinearLayout.HORIZONTAL
            (binding.cardPresetStopwatch.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = 0
                lp.weight = 1f
                lp.marginEnd = margin8
                lp.bottomMargin = margin16
                binding.cardPresetStopwatch.layoutParams = lp
            }
            (binding.cardPresetCountdown.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = 0
                lp.weight = 1f
                lp.marginStart = margin8
                lp.bottomMargin = margin16
                binding.cardPresetCountdown.layoutParams = lp
            }
        } else {
            binding.layoutPresetsGroup.orientation = LinearLayout.VERTICAL
            (binding.cardPresetStopwatch.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.marginEnd = 0
                lp.bottomMargin = margin16
                binding.cardPresetStopwatch.layoutParams = lp
            }
            (binding.cardPresetCountdown.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.marginStart = 0
                lp.bottomMargin = margin16
                binding.cardPresetCountdown.layoutParams = lp
            }
        }
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
