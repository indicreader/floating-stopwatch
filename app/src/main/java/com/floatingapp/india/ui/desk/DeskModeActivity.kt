// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.ui.desk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.floatingapp.india.databinding.ActivityDeskModeBinding
import com.floatingapp.india.model.OptionChoice
import com.floatingapp.india.model.QuestionResponse
import com.floatingapp.india.model.SessionMetrics
import com.floatingapp.india.service.FloatingStopwatchService
import com.floatingapp.india.ui.lap.LapAdapter
import java.util.Locale

class DeskModeActivity : AppCompatActivity(), FloatingStopwatchService.TimerListener {

    private lateinit var binding: ActivityDeskModeBinding
    private lateinit var lapAdapter: LapAdapter

    private var service: FloatingStopwatchService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? FloatingStopwatchService.LocalBinder
            service = localBinder?.getService() ?: FloatingStopwatchService.activeService
            isBound = true
            service?.hideOverlay()
            service?.addTimerListener(this@DeskModeActivity)
            updateInitialUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service?.removeTimerListener(this@DeskModeActivity)
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on during study/desk practice sessions
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityDeskModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge Window Insets so top bar is safely below punch hole & status bar,
        // and bottom controls are safely above the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutDeskRoot) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            v.setPadding(
                systemBars.left + (16 * density).toInt(),
                systemBars.top + (8 * density).toInt(),
                systemBars.right + (16 * density).toInt(),
                systemBars.bottom + (10 * density).toInt()
            )
            insets
        }

        setupRecyclerView()
        setupButtons()
        setupBackHandler()

        // Apply orientation specific adjustments
        applyOrientationLayout(resources.configuration.orientation)

        // Ensure floating overlay is completely hidden while Desk Mode is foreground
        enforceOverlayHidden()

        // Connect to active FloatingStopwatchService with ACTION_HIDE_OVERLAY
        val serviceIntent = Intent(this, FloatingStopwatchService::class.java).apply {
            action = FloatingStopwatchService.ACTION_HIDE_OVERLAY
        }
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        enforceOverlayHidden()
    }

    private fun enforceOverlayHidden() {
        FloatingStopwatchService.activeService?.hideOverlay()
        val hideIntent = Intent(this, FloatingStopwatchService::class.java).apply {
            action = FloatingStopwatchService.ACTION_HIDE_OVERLAY
        }
        startService(hideIntent)
    }

    private fun setupRecyclerView() {
        lapAdapter = LapAdapter { qNum, newStatus ->
            service?.getSessionManager()?.updateVerification(qNum, newStatus)
            refreshLapsList()
        }
        binding.rvDeskLaps.apply {
            layoutManager = LinearLayoutManager(this@DeskModeActivity)
            adapter = lapAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupButtons() {
        binding.btnOptionA.setOnClickListener { service?.triggerOption(OptionChoice.A) }
        binding.btnOptionB.setOnClickListener { service?.triggerOption(OptionChoice.B) }
        binding.btnOptionC.setOnClickListener { service?.triggerOption(OptionChoice.C) }
        binding.btnOptionD.setOnClickListener { service?.triggerOption(OptionChoice.D) }
        binding.btnDeskSkip.setOnClickListener { service?.triggerOption(OptionChoice.SKIP) }

        binding.btnDeskPlayPause.setOnClickListener {
            service?.triggerPlayPause()
        }

        binding.btnDeskReset.setOnClickListener {
            service?.triggerReset()
        }

        binding.btnDeskReview.setOnClickListener {
            val last = service?.getSessionManager()?.responses?.lastOrNull()
            if (last != null) {
                service?.triggerFlagToggle(last.questionNumber)
                Toast.makeText(this, "Toggled review for Q${last.questionNumber}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No question answered yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDeskSplits.setOnClickListener {
            val isVisible = binding.layoutLapsDrawer.visibility == View.VISIBLE
            binding.layoutLapsDrawer.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) refreshLapsList()
        }

        binding.btnCloseLaps.setOnClickListener {
            binding.layoutLapsDrawer.visibility = View.GONE
        }

        binding.btnDeskReturnOverlay.setOnClickListener {
            exitDeskMode()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutLapsDrawer.visibility == View.VISIBLE) {
                    binding.layoutLapsDrawer.visibility = View.GONE
                } else {
                    exitDeskMode()
                }
            }
        })
    }

    private fun exitDeskMode() {
        // Exiting Desk Mode must NOT activate or show the floating overlay
        val isTimerRunning = service?.isRunningState() == true
        if (!isTimerRunning) {
            val stopIntent = Intent(this, FloatingStopwatchService::class.java)
            stopService(stopIntent)
        } else {
            FloatingStopwatchService.activeService?.hideOverlay()
        }
        finish()
    }

    private fun updateInitialUI() {
        val s = service ?: return
        val session = s.getSessionManager()
        val mode = s.getClockMode()

        binding.tvDeskModeTitle.text = if (mode == FloatingStopwatchService.ClockMode.COUNTDOWN) "COUNTDOWN" else "STOPWATCH"
        binding.btnDeskDrillTag.text = s.getDrillTagRepo().getFormattedSessionTag()
        updatePlayPauseButton(s.isRunningState())
        updateQuestionBadge(session.nextQuestionNumber)
        updateAverageBadge(session.responses)
        refreshLapsList()
    }

    private fun updatePlayPauseButton(isRunning: Boolean) {
        binding.btnDeskPlayPause.text = if (isRunning) "⏸ Pause" else "▶ Resume"
    }

    private fun updateQuestionBadge(qNumber: Int) {
        binding.tvDeskQuestionBadge.text = "Q: $qNumber"
    }

    private fun updateAverageBadge(responses: List<QuestionResponse>) {
        if (responses.isNotEmpty()) {
            val total = responses.sumOf { it.splitDurationMillis }
            val avg = total / responses.size
            binding.tvDeskAvgBadge.text = "Avg: ${SessionMetrics.formatAveragePace(avg)}"
        } else {
            binding.tvDeskAvgBadge.text = "Avg: --"
        }
    }

    private fun refreshLapsList() {
        val responses = service?.getSessionManager()?.responses ?: return
        lapAdapter.submitList(responses.map { it.copy() })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout(newConfig.orientation)
    }

    private fun applyOrientationLayout(orientation: Int) {
        val density = resources.displayMetrics.density
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Horizontal row for ABCD across center
            binding.layoutOptionsContainer.orientation = LinearLayout.HORIZONTAL
            binding.tvDeskTimer.textSize = 42f

            val buttonParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                val margin = (4 * density).toInt()
                setMargins(margin, 0, margin, 0)
            }
            binding.btnOptionA.layoutParams = buttonParams
            binding.btnOptionB.layoutParams = buttonParams
            binding.btnOptionC.layoutParams = buttonParams
            binding.btnOptionD.layoutParams = buttonParams
        } else {
            // Vertical stack for portrait
            binding.layoutOptionsContainer.orientation = LinearLayout.VERTICAL
            binding.tvDeskTimer.textSize = 54f

            val buttonParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (58 * density).toInt()).apply {
                val vMargin = (4 * density).toInt()
                setMargins(0, vMargin, 0, vMargin)
            }
            binding.btnOptionA.layoutParams = buttonParams
            binding.btnOptionB.layoutParams = buttonParams
            binding.btnOptionC.layoutParams = buttonParams
            binding.btnOptionD.layoutParams = buttonParams
        }
    }

    // --- FloatingStopwatchService.TimerListener Callbacks ---

    override fun onTick(totalElapsedMillis: Long, splitElapsedMillis: Long, mode: FloatingStopwatchService.ClockMode, isRunning: Boolean) {
        runOnUiThread {
            binding.tvDeskTimer.text = formatStopwatchTime(totalElapsedMillis)

            val session = service?.getSessionManager()
            val targetSec = session?.targetDurationSeconds ?: 30
            val qIndex = String.format(Locale.US, "Q%02d", session?.nextQuestionNumber ?: 1)
            val formattedSplit = QuestionResponse.formatDuration(splitElapsedMillis)
            binding.tvDeskSplit.text = String.format(Locale.US, "Active %s • Split: %s", qIndex, formattedSplit)

            val isOvertime = splitElapsedMillis > (targetSec * 1000L)
            binding.tvDeskSplit.setTextColor(if (isOvertime) Color.parseColor("#FF5252") else Color.parseColor("#859990"))
        }
    }

    override fun onStateChanged(isRunning: Boolean) {
        runOnUiThread {
            updatePlayPauseButton(isRunning)
            lapAdapter.isPaused = !isRunning
        }
    }

    override fun onResponseLogged() {
        runOnUiThread {
            val session = service?.getSessionManager() ?: return@runOnUiThread
            updateQuestionBadge(session.nextQuestionNumber)
            updateAverageBadge(session.responses)
            refreshLapsList()
        }
    }

    override fun onReset() {
        runOnUiThread {
            val session = service?.getSessionManager()
            binding.tvDeskTimer.text = "00:00:00.0"
            binding.tvDeskSplit.text = "Active Q01 • Split: 00:00.0"
            updateQuestionBadge(session?.nextQuestionNumber ?: 1)
            updateAverageBadge(emptyList())
            refreshLapsList()
        }
    }

    private fun formatStopwatchTime(millis: Long): String {
        val hours = (millis / (1000 * 60 * 60)).toInt()
        val minutes = ((millis / (1000 * 60)) % 60).toInt()
        val seconds = ((millis / 1000) % 60).toInt()
        val tenths = ((millis % 1000) / 100).toInt()
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service?.removeTimerListener(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
