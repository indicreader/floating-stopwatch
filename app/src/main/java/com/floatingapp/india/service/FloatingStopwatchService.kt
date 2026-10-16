// Forked and modified from com.furyform.floatingclock
package com.floatingapp.india.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.floatingapp.india.MainActivity
import com.floatingapp.india.R
import com.floatingapp.india.databinding.LayoutFloatingStopwatchBinding
import com.floatingapp.india.manager.PracticeSessionManager
import com.floatingapp.india.model.OptionChoice
import com.floatingapp.india.model.QuestionResponse
import com.floatingapp.india.ui.lap.LapAdapter
import com.floatingapp.india.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FloatingStopwatchService runs the floating overlay window.
 * Supports:
 * - Dynamic corner drag resizing & two-finger pinch-to-zoom
 * - Instant access to SettingsActivity directly from the overlay
 * - Full-surface drag from anywhere
 * - 4 complete clock presets: Countdown, Simple, Practice Stopwatch, and Wall Clock
 */
class FloatingStopwatchService : Service() {

    companion object {
        const val EXTRA_MODE = "extra_clock_mode"
        const val MODE_STOPWATCH = "mode_stopwatch"
        const val MODE_COUNTDOWN = "mode_countdown"
        const val MODE_SIMPLE = "mode_simple"
        const val MODE_WALL_CLOCK = "mode_wall_clock"
    }

    enum class ClockMode {
        STOPWATCH,
        COUNTDOWN,
        SIMPLE,
        WALL_CLOCK
    }

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingStopwatchBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var currentMode: ClockMode = ClockMode.STOPWATCH

    // Practice Stopwatch State
    private val sessionManager = PracticeSessionManager()
    private lateinit var lapAdapter: LapAdapter
    private var isRunning: Boolean = false
    private var baseStartTime: Long = 0L
    private var totalAccumulatedMillis: Long = 0L

    // Countdown State
    private val countdownPresets = listOf(
        1 * 60 * 1000L,   // 1 min
        3 * 60 * 1000L,   // 3 min
        5 * 60 * 1000L,   // 5 min (default)
        10 * 60 * 1000L,  // 10 min
        15 * 60 * 1000L,  // 15 min
        25 * 60 * 1000L,  // 25 min (Pomodoro)
        30 * 60 * 1000L,  // 30 min
        60 * 60 * 1000L   // 60 min
    )
    private var countdownPresetIndex = 2 // default 5m
    private var countdownTotalMillis: Long = 5 * 60 * 1000L
    private var countdownRemainingMillis: Long = 5 * 60 * 1000L

    // Wall Clock State
    private var is24HourFormat: Boolean = false

    private val tickerHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            when (currentMode) {
                ClockMode.STOPWATCH -> {
                    if (isRunning) {
                        updateTimerDisplay()
                        tickerHandler.postDelayed(this, 50)
                    }
                }
                ClockMode.COUNTDOWN -> {
                    if (isRunning) {
                        updateCountdownTicker()
                        tickerHandler.postDelayed(this, 50)
                    }
                }
                ClockMode.WALL_CLOCK -> {
                    updateWallClockDisplay()
                    tickerHandler.postDelayed(this, 500)
                }
                ClockMode.SIMPLE -> {
                    if (isRunning) {
                        updateSimpleDisplay()
                        tickerHandler.postDelayed(this, 100)
                    } else {
                        updateSimpleDisplay()
                        tickerHandler.postDelayed(this, 1000)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        loadPreferences()
        startForegroundServiceNotification()
        inflateOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeKey = intent?.getStringExtra(EXTRA_MODE)
            ?: prefs.getString("pref_last_clock_mode", MODE_STOPWATCH)
            ?: MODE_STOPWATCH

        val newMode = when (modeKey) {
            MODE_COUNTDOWN -> ClockMode.COUNTDOWN
            MODE_SIMPLE -> ClockMode.SIMPLE
            MODE_WALL_CLOCK -> ClockMode.WALL_CLOCK
            else -> ClockMode.STOPWATCH
        }

        currentMode = newMode
        prefs.edit().putString("pref_last_clock_mode", modeKey).apply()

        applyModeConfiguration()
        return START_NOT_STICKY
    }

    private fun loadPreferences() {
        val targetSecString = prefs.getString("pref_target_question_time", "30") ?: "30"
        sessionManager.targetDurationSeconds = targetSecString.toIntOrNull() ?: 30
    }

    private fun startForegroundServiceNotification() {
        val channelId = "channel_practice_timer"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Clock Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of the active floating clock overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Floating overlay is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateOverlayWindow() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_PracticeTimer_M3)
        binding = LayoutFloatingStopwatchBinding.inflate(LayoutInflater.from(themedContext))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val keepScreenOn = prefs.getBoolean("pref_keep_screen_on", false)
        val screenOnFlag = if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0

        val density = resources.displayMetrics.density
        val defaultWidth = (340 * density).toInt()
        val savedWidth = prefs.getInt("pref_overlay_width_px", defaultWidth)

        layoutParams = WindowManager.LayoutParams(
            savedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or screenOnFlag,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        setupDragTouchListener()
        setupResizeListener()
        setupLapRecyclerView()
        setupOptionButtons()
        setupControlButtons()
        setupInlinePrompt()
        applyPreferences()
        scaleTypography(savedWidth)

        windowManager.addView(binding.root, layoutParams)
        applyModeConfiguration()
    }

    /**
     * Enables dragging the overlay smoothly from ANYWHERE on the card,
     * with two-finger pinch-to-zoom support.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val touchListener = View.OnTouchListener { _, event ->
            val isLocked = prefs.getBoolean("pref_lock_position", false)
            if (isLocked) return@OnTouchListener false

            // Two-finger pinch to resize
            if (event.pointerCount > 1) {
                scaleGestureDetector.onTouchEvent(event)
                return@OnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(binding.root, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val shouldSnap = prefs.getBoolean("pref_snap_edges", false)
                    if (shouldSnap) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val middle = screenWidth / 2
                        layoutParams.x = if (layoutParams.x + (binding.root.width / 2) < middle) 16 else screenWidth - binding.root.width - 16
                        windowManager.updateViewLayout(binding.root, layoutParams)
                    }
                    true
                }
                else -> false
            }
        }

        // Attach touch listener across ALL surfaces so the user can drag from ANYWHERE
        binding.cardFloatingContainer.setOnTouchListener(touchListener)
        binding.layoutContentBody.setOnTouchListener(touchListener)
        binding.viewTopAccentBar.setOnTouchListener(touchListener)
        binding.layoutDragHeader.setOnTouchListener(touchListener)
        binding.tvWindowTitle.setOnTouchListener(touchListener)
        binding.ivDragHandle.setOnTouchListener(touchListener)
        binding.tvTimerDisplay.setOnTouchListener(touchListener)
        binding.tvTimerSubtext.setOnTouchListener(touchListener)
        binding.tvActivePacing.setOnTouchListener(touchListener)
        binding.layoutAccuracyBanner.setOnTouchListener(touchListener)
        binding.layoutOptionButtons.root.setOnTouchListener(touchListener)
    }

    /**
     * Dedicated drag-to-resize handle at the bottom-right corner,
     * plus ScaleGestureDetector for pinch-to-resize.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeListener() {
        var startWidth = 0
        var startTouchX = 0f
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val minWidth = (220 * density).toInt()
        val maxWidth = screenWidth - (16 * density).toInt()

        binding.ivResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = layoutParams.width
                    startTouchX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - startTouchX).toInt()
                    val newWidth = (startWidth + deltaX).coerceIn(minWidth, maxWidth)
                    layoutParams.width = newWidth
                    windowManager.updateViewLayout(binding.root, layoutParams)
                    scaleTypography(newWidth)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("pref_overlay_width_px", layoutParams.width).apply()
                    true
                }
                else -> false
            }
        }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newWidth = (layoutParams.width * detector.scaleFactor).toInt().coerceIn(minWidth, maxWidth)
                layoutParams.width = newWidth
                windowManager.updateViewLayout(binding.root, layoutParams)
                scaleTypography(newWidth)
                prefs.edit().putInt("pref_overlay_width_px", newWidth).apply()
                return true
            }
        })
    }

    private fun scaleTypography(currentWidthPx: Int) {
        val density = resources.displayMetrics.density
        val baseWidthPx = 340 * density
        val scaleRatio = (currentWidthPx / baseWidthPx).coerceIn(0.7f, 1.6f)
        val baseFontSize = prefs.getString("pref_font_size", "32")?.toFloatOrNull() ?: 32f
        binding.tvTimerDisplay.textSize = baseFontSize * scaleRatio
    }

    private fun setupLapRecyclerView() {
        lapAdapter = LapAdapter { questionNumber, newStatus ->
            sessionManager.updateVerification(questionNumber, newStatus)
            updateMetricsBanner()
            lapAdapter.notifyItemChanged(
                sessionManager.responses.indexOfFirst { it.questionNumber == questionNumber }
            )
        }

        binding.rvLaps.apply {
            layoutManager = LinearLayoutManager(this@FloatingStopwatchService)
            adapter = lapAdapter
        }
    }

    private fun setupOptionButtons() {
        val optionsLayout = binding.layoutOptionButtons

        optionsLayout.btnOptionA.setOnClickListener { onOptionTapped(OptionChoice.A) }
        optionsLayout.btnOptionB.setOnClickListener { onOptionTapped(OptionChoice.B) }
        optionsLayout.btnOptionC.setOnClickListener { onOptionTapped(OptionChoice.C) }
        optionsLayout.btnOptionD.setOnClickListener { onOptionTapped(OptionChoice.D) }
        optionsLayout.btnOptionSkip.setOnClickListener { onOptionTapped(OptionChoice.SKIP) }
    }

    private fun setupControlButtons() {
        binding.btnPlayPause.setOnClickListener {
            when (currentMode) {
                ClockMode.STOPWATCH -> {
                    if (isRunning) pauseStopwatch() else startStopwatchWithPromptCheck()
                }
                ClockMode.COUNTDOWN -> {
                    if (isRunning) pauseCountdown() else startCountdown()
                }
                ClockMode.SIMPLE -> {
                    if (isRunning) pauseStopwatch() else startStopwatch()
                }
                ClockMode.WALL_CLOCK -> {
                    // Wall clock runs live
                }
            }
        }

        binding.btnReset.setOnClickListener {
            when (currentMode) {
                ClockMode.STOPWATCH, ClockMode.SIMPLE -> resetStopwatch()
                ClockMode.COUNTDOWN -> resetCountdown()
                ClockMode.WALL_CLOCK -> {}
            }
        }

        binding.btnToggleLaps.setOnClickListener {
            val isVisible = binding.layoutLapsDrawer.visibility == View.VISIBLE
            binding.layoutLapsDrawer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.btnCountdownPreset.setOnClickListener {
            cycleCountdownPreset()
        }

        binding.btnWallClockToggle.setOnClickListener {
            is24HourFormat = !is24HourFormat
            updateWallClockDisplay()
            Toast.makeText(this, if (is24HourFormat) "24-Hour Format" else "12-Hour Format", Toast.LENGTH_SHORT).show()
        }

        binding.btnFloatingSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }

        binding.btnClose.setOnClickListener {
            stopSelf()
        }
    }

    private fun setupInlinePrompt() {
        binding.btnInlineStartConfirm.setOnClickListener {
            val offsetInput = binding.etInlineQuestionOffset.text.toString().toIntOrNull() ?: 1
            sessionManager.startingQuestionOffset = if (offsetInput > 0) offsetInput else 1
            binding.layoutInlinePrompt.visibility = View.GONE

            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(binding.root, layoutParams)

            startStopwatch()
        }
    }

    private fun applyPreferences() {
        val fontSizeSp = prefs.getString("pref_font_size", "32")?.toFloatOrNull() ?: 32f
        binding.tvTimerDisplay.textSize = fontSizeSp

        val opacityPercent = prefs.getString("pref_clock_opacity", "90")?.toIntOrNull() ?: 90
        binding.cardFloatingContainer.alpha = opacityPercent / 100f
    }

    /**
     * Reconfigures overlay UI for the selected preset mode.
     */
    private fun applyModeConfiguration() {
        tickerHandler.removeCallbacks(tickerRunnable)
        isRunning = false
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        when (currentMode) {
            ClockMode.STOPWATCH -> configureStopwatchMode()
            ClockMode.COUNTDOWN -> configureCountdownMode()
            ClockMode.SIMPLE -> configureSimpleMode()
            ClockMode.WALL_CLOCK -> configureWallClockMode()
        }
    }

    private fun configureStopwatchMode() {
        binding.tvWindowTitle.text = "STOPWATCH"
        binding.tvWindowTitle.setTextColor(Color.parseColor("#859990"))
        binding.viewTopAccentBar.setBackgroundColor(Color.parseColor("#00E676"))
        binding.btnPlayPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))

        binding.tvTimerSubtext.visibility = View.GONE
        binding.tvActivePacing.visibility = View.VISIBLE

        val showButtons = prefs.getBoolean("pref_show_practice_buttons", true)
        binding.layoutOptionButtons.root.visibility = if (showButtons) View.VISIBLE else View.GONE

        binding.btnToggleLaps.visibility = View.VISIBLE
        binding.tvLapCountBadge.visibility = View.VISIBLE
        binding.btnCountdownPreset.visibility = View.GONE
        binding.btnWallClockToggle.visibility = View.GONE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.btnReset.visibility = View.VISIBLE

        resetStopwatch()
    }

    private fun configureCountdownMode() {
        binding.tvWindowTitle.text = "COUNTDOWN"
        binding.tvWindowTitle.setTextColor(Color.parseColor("#FFAB00"))
        binding.viewTopAccentBar.setBackgroundColor(Color.parseColor("#FFAB00"))
        binding.btnPlayPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFAB00"))

        binding.tvTimerSubtext.visibility = View.VISIBLE
        binding.tvTimerSubtext.text = "TIME REMAINING"
        binding.tvTimerSubtext.setTextColor(Color.parseColor("#FFD54F"))

        binding.tvActivePacing.visibility = View.GONE
        binding.layoutOptionButtons.root.visibility = View.GONE
        binding.layoutLapsDrawer.visibility = View.GONE
        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE

        binding.btnToggleLaps.visibility = View.GONE
        binding.tvLapCountBadge.visibility = View.GONE
        binding.btnCountdownPreset.visibility = View.VISIBLE
        binding.btnWallClockToggle.visibility = View.GONE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.btnReset.visibility = View.VISIBLE

        resetCountdown()
    }

    private fun configureSimpleMode() {
        binding.tvWindowTitle.text = "SIMPLE"
        binding.tvWindowTitle.setTextColor(Color.parseColor("#FF5252"))
        binding.viewTopAccentBar.setBackgroundColor(Color.parseColor("#FF5252"))
        binding.btnPlayPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))

        binding.tvTimerSubtext.visibility = View.GONE
        binding.tvActivePacing.visibility = View.GONE
        binding.layoutOptionButtons.root.visibility = View.GONE
        binding.layoutLapsDrawer.visibility = View.GONE
        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE

        binding.btnToggleLaps.visibility = View.GONE
        binding.tvLapCountBadge.visibility = View.GONE
        binding.btnCountdownPreset.visibility = View.GONE
        binding.btnWallClockToggle.visibility = View.GONE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.btnReset.visibility = View.VISIBLE

        resetStopwatch()
    }

    private fun configureWallClockMode() {
        binding.tvWindowTitle.text = "LOCAL TIME"
        binding.tvWindowTitle.setTextColor(Color.parseColor("#FF5252"))
        binding.viewTopAccentBar.setBackgroundColor(Color.parseColor("#FF5252"))

        binding.tvTimerSubtext.visibility = View.VISIBLE
        binding.tvActivePacing.visibility = View.GONE
        binding.layoutOptionButtons.root.visibility = View.GONE
        binding.layoutLapsDrawer.visibility = View.GONE
        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE

        binding.btnToggleLaps.visibility = View.GONE
        binding.tvLapCountBadge.visibility = View.GONE
        binding.btnCountdownPreset.visibility = View.GONE
        binding.btnWallClockToggle.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.GONE
        binding.btnReset.visibility = View.GONE

        updateWallClockDisplay()
        tickerHandler.post(tickerRunnable)
    }

    // ==========================================
    // PRACTICE STOPWATCH LOGIC
    // ==========================================

    private fun onOptionTapped(option: OptionChoice) {
        if (!isRunning || currentMode != ClockMode.STOPWATCH) return

        triggerHapticFeedback()

        val currentElapsed = calculateCurrentElapsedMillis()
        sessionManager.recordLap(option, currentElapsed)

        lapAdapter.submitList(sessionManager.responses.toList()) {
            if (binding.layoutLapsDrawer.visibility == View.VISIBLE && sessionManager.responses.isNotEmpty()) {
                binding.rvLaps.scrollToPosition(sessionManager.responses.size - 1)
            }
        }
        binding.tvNoLaps.visibility = View.GONE
        binding.tvLapCountBadge.text = "${sessionManager.responses.size}"
    }

    private fun startStopwatchWithPromptCheck() {
        val shouldPrompt = prefs.getBoolean("pref_prompt_start_question_num", true)

        if (shouldPrompt && totalAccumulatedMillis == 0L && sessionManager.responses.isEmpty()) {
            binding.etInlineQuestionOffset.setText(sessionManager.startingQuestionOffset.toString())
            binding.layoutInlinePrompt.visibility = View.VISIBLE

            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(binding.root, layoutParams)
        } else {
            startStopwatch()
        }
    }

    private fun startStopwatch() {
        loadPreferences()
        isRunning = true
        baseStartTime = SystemClock.elapsedRealtime()

        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE
        lapAdapter.isPaused = false

        tickerHandler.post(tickerRunnable)
    }

    private fun pauseStopwatch() {
        if (!isRunning) return

        isRunning = false
        totalAccumulatedMillis += (SystemClock.elapsedRealtime() - baseStartTime)
        tickerHandler.removeCallbacks(tickerRunnable)

        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        if (currentMode == ClockMode.STOPWATCH) {
            lapAdapter.isPaused = true
            updateMetricsBanner()
            binding.layoutAccuracyBanner.visibility = View.VISIBLE
            binding.layoutLapsDrawer.visibility = View.VISIBLE
            binding.rvLaps.scrollToPosition(0)
        }
    }

    private fun resetStopwatch() {
        isRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)

        totalAccumulatedMillis = 0L
        baseStartTime = 0L
        sessionManager.reset()

        binding.tvTimerDisplay.text = "00:00:00.0"
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        lapAdapter.submitList(emptyList())
        lapAdapter.isPaused = false

        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE
        binding.tvLapCountBadge.text = "0"
        binding.tvNoLaps.visibility = View.VISIBLE

        updateActivePacingDisplay(0L)
    }

    private fun calculateCurrentElapsedMillis(): Long {
        return if (isRunning) {
            totalAccumulatedMillis + (SystemClock.elapsedRealtime() - baseStartTime)
        } else {
            totalAccumulatedMillis
        }
    }

    private fun updateTimerDisplay() {
        val elapsed = calculateCurrentElapsedMillis()
        val showMillis = prefs.getBoolean("pref_show_milliseconds", true)
        binding.tvTimerDisplay.text = formatFullStopwatchTime(elapsed, showMillis)

        val split = elapsed - sessionManager.activeSplitBaseTimestamp
        updateActivePacingDisplay(split)
    }

    private fun updateSimpleDisplay() {
        val elapsed = calculateCurrentElapsedMillis()
        binding.tvTimerDisplay.text = formatFullStopwatchTime(elapsed, true)
    }

    private fun updateActivePacingDisplay(currentSplitMillis: Long) {
        val targetSec = sessionManager.targetDurationSeconds
        val formattedSplit = QuestionResponse.formatDuration(currentSplitMillis)
        val qIndex = String.format("Q%02d", sessionManager.nextQuestionNumber)

        binding.tvActivePacing.text = String.format(
            Locale.US,
            "Active %s • Split: %s (Target: %ds)",
            qIndex,
            formattedSplit,
            targetSec
        )

        val isOvertime = currentSplitMillis > (targetSec * 1000L)
        if (isOvertime) {
            binding.tvActivePacing.setTextColor(Color.parseColor("#FF5252"))
        } else {
            binding.tvActivePacing.setTextColor(Color.parseColor("#859990"))
        }
    }

    private fun updateMetricsBanner() {
        val metrics = sessionManager.calculateMetrics()

        binding.tvMetricAccuracy.text = "${metrics.accuracyPercentage}%"
        binding.tvMetricAvgCorrect.text = metrics.formattedAvgCorrect
        binding.tvMetricAvgIncorrect.text = metrics.formattedAvgIncorrect

        binding.tvMetricBreakdown.text = String.format(
            Locale.US,
            "%d Solved • %d Correct • %d Wrong • %d Skipped",
            metrics.totalAnswered,
            metrics.totalCorrect,
            metrics.totalIncorrect,
            metrics.totalSkipped
        )
    }

    // ==========================================
    // COUNTDOWN LOGIC
    // ==========================================

    private fun cycleCountdownPreset() {
        countdownPresetIndex = (countdownPresetIndex + 1) % countdownPresets.size
        countdownTotalMillis = countdownPresets[countdownPresetIndex]
        resetCountdown()

        val mins = countdownTotalMillis / 60000
        Toast.makeText(this, "Countdown set to ${mins}m", Toast.LENGTH_SHORT).show()
    }

    private fun startCountdown() {
        if (countdownRemainingMillis <= 0L) {
            countdownRemainingMillis = countdownTotalMillis
        }

        isRunning = true
        baseStartTime = SystemClock.elapsedRealtime()
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)

        tickerHandler.post(tickerRunnable)
    }

    private fun pauseCountdown() {
        if (!isRunning) return

        isRunning = false
        val elapsed = SystemClock.elapsedRealtime() - baseStartTime
        countdownRemainingMillis -= elapsed
        if (countdownRemainingMillis < 0L) countdownRemainingMillis = 0L

        tickerHandler.removeCallbacks(tickerRunnable)
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
    }

    private fun resetCountdown() {
        isRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)

        countdownRemainingMillis = countdownTotalMillis
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        binding.tvTimerDisplay.text = formatCountdownTime(countdownRemainingMillis)
    }

    private fun updateCountdownTicker() {
        val elapsed = SystemClock.elapsedRealtime() - baseStartTime
        val remaining = countdownRemainingMillis - elapsed

        if (remaining <= 0L) {
            countdownRemainingMillis = 0L
            isRunning = false
            binding.tvTimerDisplay.text = "00:00:00.0"
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            triggerHapticFeedback()
            Toast.makeText(this, "Countdown Finished!", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvTimerDisplay.text = formatCountdownTime(remaining)
        }
    }

    private fun formatCountdownTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val tenths = (millis % 1000) / 100
        return String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
    }

    // ==========================================
    // WALL CLOCK LOGIC
    // ==========================================

    private fun updateWallClockDisplay() {
        val now = Date()
        val pattern = if (is24HourFormat) "HH:mm:ss" else "hh:mm:ss"
        val timeFormatter = SimpleDateFormat(pattern, Locale.getDefault())
        binding.tvTimerDisplay.text = timeFormatter.format(now)

        val dateFormatter = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        binding.tvTimerSubtext.text = dateFormatter.format(now)
    }

    // ==========================================
    // UTILITIES
    // ==========================================

    private fun triggerHapticFeedback() {
        val enabled = prefs.getBoolean("pref_haptic_feedback", true)
        if (!enabled) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(35)
                }
            }
        } catch (_: Exception) {}
    }

    private fun formatFullStopwatchTime(millis: Long, showMillis: Boolean): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val tenths = (millis % 1000) / 100

        return if (showMillis) {
            if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
            } else {
                String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
            }
        } else {
            if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerHandler.removeCallbacks(tickerRunnable)
        if (::binding.isInitialized && binding.root.windowToken != null) {
            windowManager.removeView(binding.root)
        }
    }
}
