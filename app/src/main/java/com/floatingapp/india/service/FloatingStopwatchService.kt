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
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
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
import com.floatingapp.india.manager.DrillTagRepository
import com.floatingapp.india.manager.PracticeSessionManager
import com.floatingapp.india.model.OptionChoice
import com.floatingapp.india.model.QuestionResponse
import com.floatingapp.india.model.SessionMetrics
import com.floatingapp.india.ui.lap.LapAdapter
import com.floatingapp.india.ui.settings.SettingsActivity
import android.graphics.Rect
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.floatingapp.india.model.VerificationStatus
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

        @Volatile
        var activeService: FloatingStopwatchService? = null
            private set
    }

    interface TimerListener {
        fun onTick(totalElapsedMillis: Long, splitElapsedMillis: Long, mode: ClockMode, isRunning: Boolean)
        fun onStateChanged(isRunning: Boolean)
        fun onResponseLogged()
        fun onReset()
    }

    inner class LocalBinder : Binder() {
        fun getService(): FloatingStopwatchService = this@FloatingStopwatchService
    }

    private val localBinder = LocalBinder()
    private val timerListeners = java.util.concurrent.CopyOnWriteArraySet<TimerListener>()
    private var isOverlayMinimized = false

    enum class ClockMode {
        STOPWATCH,
        COUNTDOWN
    }

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingStopwatchBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var currentMode: ClockMode = ClockMode.STOPWATCH

    // Drag tracking state
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchDownX = 0f
    private var dragTouchDownY = 0f
    private var isDraggingOverlay = false

    private fun resetDragState() {
        dragInitialX = 0
        dragInitialY = 0
        dragTouchDownX = 0f
        dragTouchDownY = 0f
        isDraggingOverlay = false
    }

    // Practice Stopwatch State
    private val sessionManager = PracticeSessionManager()
    private lateinit var drillTagRepo: DrillTagRepository
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
    private var countdownPresetIndex = 2 // default 5 min
    private var countdownTotalMillis: Long = 5 * 60 * 1000L
    private var countdownRemainingMillis: Long = 5 * 60 * 1000L

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
            }
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadPreferences()
        if (::binding.isInitialized) {
            applyPreferences()
            if (!isRunning && sessionManager.responses.isEmpty()) {
                sessionManager.startingQuestionOffset = PracticeSessionManager.getPrefetchedStartingQuestion(this)
                updateActivePacingDisplay(0L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onCreate() {
        super.onCreate()
        activeService = this
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        drillTagRepo = DrillTagRepository(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        loadPreferences()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        startForegroundServiceNotification()
        inflateOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeKey = intent?.getStringExtra(EXTRA_MODE)
            ?: prefs.getString("pref_last_clock_mode", MODE_STOPWATCH)
            ?: MODE_STOPWATCH

        val newMode = when (modeKey) {
            MODE_COUNTDOWN -> ClockMode.COUNTDOWN
            else -> ClockMode.STOPWATCH
        }

        if (newMode != currentMode) {
            currentMode = newMode
            applyModeConfiguration()
        }
        prefs.edit().putString("pref_last_clock_mode", modeKey).apply()

        return START_STICKY
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateOverlayWindow() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_PracticeTimer_Overlay)
        binding = LayoutFloatingStopwatchBinding.inflate(LayoutInflater.from(themedContext))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val keepScreenOn = prefs.getBoolean("pref_keep_screen_on", false)
        val screenOnFlag = if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0
        @Suppress("DEPRECATION")
        val lockScreenFlag = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        val density = resources.displayMetrics.density
        val defaultWidth = (340 * density).toInt()
        val savedWidth = prefs.getInt("pref_overlay_width_px", defaultWidth)

        layoutParams = WindowManager.LayoutParams(
            savedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                screenOnFlag or
                lockScreenFlag,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 160
        }

        setupResizeListener()
        setupDragTouchListener()
        setupLapRecyclerView()
        setupOptionButtons()
        setupControlButtons()
        setupInlinePrompt()
        applyPreferences()
        scaleTypography(savedWidth)

        try {
            windowManager.addView(binding.root, layoutParams)
        } catch (_: Exception) {}
        applyModeConfiguration()
    }

    /**
     * Enables dragging the overlay strictly via the dedicated drag header,
     * window title, and drag handle icon. Interactive child buttons consume
     * their own touch events.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouchListener() {
        val touchListener = View.OnTouchListener { view, event ->
            val isLocked = prefs.getBoolean("pref_lock_position", false)
            if (isLocked) return@OnTouchListener false

            // Two-finger pinch to resize
            if (event.pointerCount > 1) {
                scaleGestureDetector.onTouchEvent(event)
                return@OnTouchListener true
            }

            // If touching the header bar, ensure touches on interactive child buttons are not intercepted
            if (view == binding.layoutDragHeader) {
                val interactiveViews = listOf(
                    binding.btnExpandDeskMode,
                    binding.btnFloatingSettings,
                    binding.btnClose,
                    binding.btnCountdownPresetChip,
                    binding.btnFloatingDrillTag
                )
                val hitRect = Rect()
                for (child in interactiveViews) {
                    if (child.visibility == View.VISIBLE) {
                        child.getHitRect(hitRect)
                        if (hitRect.contains(event.x.toInt(), event.y.toInt())) {
                            return@OnTouchListener false
                        }
                    }
                }
            } else if (view == binding.layoutMinimizedOverlay) {
                val hitRect = Rect()
                if (binding.btnMinimizedRestore.visibility == View.VISIBLE) {
                    binding.btnMinimizedRestore.getHitRect(hitRect)
                    if (hitRect.contains(event.x.toInt(), event.y.toInt())) {
                        return@OnTouchListener false
                    }
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = layoutParams.x
                    dragInitialY = layoutParams.y
                    dragTouchDownX = event.rawX
                    dragTouchDownY = event.rawY
                    isDraggingOverlay = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingOverlay) {
                        layoutParams.x = dragInitialX + (event.rawX - dragTouchDownX).toInt()
                        layoutParams.y = dragInitialY + (event.rawY - dragTouchDownY).toInt()
                        safeUpdateViewLayout()
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (isDraggingOverlay) {
                        isDraggingOverlay = false
                        val shouldSnap = prefs.getBoolean("pref_snap_edges", false)
                        if (shouldSnap) {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val middle = screenWidth / 2
                            layoutParams.x = if (layoutParams.x + (binding.root.width / 2) < middle) 16 else screenWidth - binding.root.width - 16
                            safeUpdateViewLayout()
                        }
                        true
                    } else false
                }
                MotionEvent.ACTION_CANCEL -> {
                    resetDragState()
                    false
                }
                else -> false
            }
        }

        // Attach touch listener STRICTLY to the header, drag handle, and title
        binding.layoutDragHeader.setOnTouchListener(touchListener)
        binding.ivDragHandle.setOnTouchListener(touchListener)
        binding.tvWindowTitle.setOnTouchListener(touchListener)
        binding.layoutMinimizedOverlay.setOnTouchListener(touchListener)
        binding.ivMinimizedDragHandle.setOnTouchListener(touchListener)
        binding.tvMinimizedTimer.setOnTouchListener(touchListener)
    }

    /**
     * Dedicated drag-to-resize handle at the bottom-right corner,
     * plus ScaleGestureDetector for pinch-to-resize.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeListener() {
        var startWidth = 0
        var startHeight = 0
        var startTouchX = 0f
        var startTouchY = 0f

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val minWidth = (220 * density).toInt()
        val maxWidth = (screenWidth * 0.90f).toInt()

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
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                    safeUpdateViewLayout()
                    scaleTypography(newWidth)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit()
                        .putInt("pref_overlay_width_px", layoutParams.width)
                        .remove("pref_overlay_height_px")
                        .apply()
                    true
                }
                else -> false
            }
        }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                val newWidth = (layoutParams.width * scale).toInt().coerceIn(minWidth, maxWidth)
                layoutParams.width = newWidth
                layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT

                safeUpdateViewLayout()
                scaleTypography(newWidth)
                prefs.edit()
                    .putInt("pref_overlay_width_px", newWidth)
                    .remove("pref_overlay_height_px")
                    .apply()
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
        val isNumeric = prefs.getString("pref_option_format", "letters") == "numbers"
        lapAdapter = LapAdapter { questionNumber, newStatus ->
            sessionManager.updateVerification(questionNumber, newStatus)
            updateMetricsBanner()
            lapAdapter.submitList(sessionManager.responses.map { it.copy() })
        }
        lapAdapter.isNumericOptionFormat = isNumeric

        binding.rvLaps.apply {
            layoutManager = LinearLayoutManager(this@FloatingStopwatchService)
            adapter = lapAdapter
            itemAnimator = null
        }
    }

    private fun setupOptionButtons() {
        val optionsLayout = binding.layoutOptionButtons

        optionsLayout.btnOptionA.setOnClickListener { onOptionTapped(OptionChoice.A) }
        optionsLayout.btnOptionB.setOnClickListener { onOptionTapped(OptionChoice.B) }
        optionsLayout.btnOptionC.setOnClickListener { onOptionTapped(OptionChoice.C) }
        optionsLayout.btnOptionD.setOnClickListener { onOptionTapped(OptionChoice.D) }
        optionsLayout.btnOptionE.setOnClickListener { onOptionTapped(OptionChoice.E) }
        optionsLayout.btnOptionF.setOnClickListener { onOptionTapped(OptionChoice.F) }
        optionsLayout.btnOptionSkip.setOnClickListener { onOptionTapped(OptionChoice.SKIP) }

        // Long press immediate override of last lap
        optionsLayout.btnOptionA.setOnLongClickListener { onOptionLongPressed(OptionChoice.A) }
        optionsLayout.btnOptionB.setOnLongClickListener { onOptionLongPressed(OptionChoice.B) }
        optionsLayout.btnOptionC.setOnLongClickListener { onOptionLongPressed(OptionChoice.C) }
        optionsLayout.btnOptionD.setOnLongClickListener { onOptionLongPressed(OptionChoice.D) }
        optionsLayout.btnOptionE.setOnLongClickListener { onOptionLongPressed(OptionChoice.E) }
        optionsLayout.btnOptionF.setOnLongClickListener { onOptionLongPressed(OptionChoice.F) }
    }

    private fun onOptionLongPressed(option: OptionChoice): Boolean {
        val updated = sessionManager.overrideLastLapOption(option)
        if (updated != null) {
            triggerHapticFeedback()
            lapAdapter.submitList(sessionManager.responses.map { it.copy() })
            updateLiveAveragePace()
            Toast.makeText(this, "Overrode Q${updated.questionNumber} to ${option.label}", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun setupControlButtons() {
        binding.btnPlayPause.setOnClickListener {
            when (currentMode) {
                ClockMode.STOPWATCH -> {
                    if (isRunning) pauseStopwatch() else startStopwatchWithPromptCheck()
                }
                ClockMode.COUNTDOWN -> {
                    if (isRunning) pauseCountdown() else startCountdownWithPromptCheck()
                }
            }
        }

        binding.btnReset.setOnClickListener {
            when (currentMode) {
                ClockMode.STOPWATCH -> resetStopwatch()
                ClockMode.COUNTDOWN -> resetCountdown()
            }
        }

        binding.btnToggleLaps.setOnClickListener {
            val isVisible = binding.layoutLapsDrawer.visibility == View.VISIBLE
            binding.layoutLapsDrawer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.btnCountdownPresetChip.setOnClickListener {
            cycleCountdownPreset()
        }

        binding.btnCountdownPreset.setOnClickListener {
            cycleCountdownPreset()
        }

        binding.btnFloatingDrillTag.setOnClickListener {
            val nextNum = drillTagRepo.incrementNumber()
            updateDrillTagBadge()
            Toast.makeText(this, "${drillTagRepo.getActiveTag()} - $nextNum", Toast.LENGTH_SHORT).show()
        }

        binding.btnFloatingDrillTag.setOnLongClickListener {
            val presets = drillTagRepo.getTagPresets()
            if (presets.isNotEmpty()) {
                val currentTag = drillTagRepo.getActiveTag()
                val currentIndex = presets.indexOf(currentTag)
                val nextIndex = if (currentIndex != -1) (currentIndex + 1) % presets.size else 0
                val nextTag = presets[nextIndex]
                drillTagRepo.setActiveTag(nextTag)
                updateDrillTagBadge()
                Toast.makeText(this, "Tag: $nextTag", Toast.LENGTH_SHORT).show()
            }
            true
        }

        binding.btnFloatingSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }

        binding.btnExpandDeskMode.setOnClickListener {
            minimizeOverlay()
            val intent = Intent().setClassName(this, "com.floatingapp.india.ui.desk.DeskModeActivity").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }

        binding.btnMinimizedRestore.setOnClickListener {
            restoreOverlay()
        }

        binding.btnClose.setOnClickListener {
            stopSelf()
        }

        binding.tvActivePacing.setOnClickListener {
            if (!isRunning && sessionManager.responses.isEmpty()) {
                showQuestionNumberPrompt()
            }
        }

        binding.tvActivePacing.setOnLongClickListener {
            if (!isRunning && sessionManager.responses.isEmpty()) {
                PracticeSessionManager.resetStartingQuestionIndex(this)
                sessionManager.startingQuestionOffset = 1
                updateActivePacingDisplay(0L)
                triggerHapticFeedback()
                Toast.makeText(this, "Question index reset to Q1", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInlinePrompt() {
        binding.btnInlineStartConfirm.setOnClickListener {
            confirmQuestionOffset()
        }

        binding.btnInlineCancel.setOnClickListener {
            cancelQuestionOffsetPrompt()
        }

        binding.etInlineQuestionOffset.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                confirmQuestionOffset()
                true
            } else {
                false
            }
        }

        binding.etInlineQuestionOffset.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                setOverlayFocusable(true)
                binding.etInlineQuestionOffset.requestFocus()
                showSoftKeyboard(binding.etInlineQuestionOffset)
            }
            false
        }
    }

    private fun showQuestionNumberPrompt() {
        binding.etInlineQuestionOffset.setText(sessionManager.startingQuestionOffset.toString())
        binding.layoutInlinePrompt.visibility = View.VISIBLE
        setOverlayFocusable(true)
        binding.etInlineQuestionOffset.post {
            binding.etInlineQuestionOffset.requestFocus()
            binding.etInlineQuestionOffset.selectAll()
            showSoftKeyboard(binding.etInlineQuestionOffset)
        }
    }

    private fun hideQuestionNumberPrompt() {
        hideSoftKeyboard(binding.etInlineQuestionOffset)
        binding.etInlineQuestionOffset.clearFocus()
        binding.layoutInlinePrompt.visibility = View.GONE
        setOverlayFocusable(false)
    }

    private fun confirmQuestionOffset() {
        val offsetInput = binding.etInlineQuestionOffset.text.toString().toIntOrNull() ?: 1
        sessionManager.startingQuestionOffset = if (offsetInput > 0) offsetInput else 1
        hideQuestionNumberPrompt()
        updateActivePacingDisplay(0L)

        if (currentMode == ClockMode.COUNTDOWN) {
            startCountdown()
        } else {
            startStopwatch()
        }
    }

    private fun cancelQuestionOffsetPrompt() {
        hideQuestionNumberPrompt()
        resetDragState()
        sessionManager.startingQuestionOffset = PracticeSessionManager.getPrefetchedStartingQuestion(this)
        updateActivePacingDisplay(0L)
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val hasFocusable = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (hasFocusable != focusable) {
            if (focusable) {
                layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            safeUpdateViewLayout()
        }
    }

    private fun showSoftKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSoftKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun applyPreferences() {
        val fontSizeSp = prefs.getString("pref_font_size", "32")?.toFloatOrNull() ?: 32f
        binding.tvTimerDisplay.textSize = fontSizeSp
        binding.cardFloatingContainer.alpha = 1.0f

        updateDrillTagBadge()

        val optionFormat = prefs.getString("pref_option_format", "letters") ?: "letters"
        val isNumeric = optionFormat == "numbers"

        val optionCount = prefs.getString("pref_option_count", "4")?.toIntOrNull() ?: 4

        val optionsLayout = binding.layoutOptionButtons
        optionsLayout.btnOptionE.visibility = if (optionCount >= 5) View.VISIBLE else View.GONE
        optionsLayout.btnOptionF.visibility = if (optionCount >= 6) View.VISIBLE else View.GONE

        optionsLayout.btnOptionA.text = if (isNumeric) "1" else "A"
        optionsLayout.btnOptionB.text = if (isNumeric) "2" else "B"
        optionsLayout.btnOptionC.text = if (isNumeric) "3" else "C"
        optionsLayout.btnOptionD.text = if (isNumeric) "4" else "D"
        optionsLayout.btnOptionE.text = if (isNumeric) "5" else "E"
        optionsLayout.btnOptionF.text = if (isNumeric) "6" else "F"

        if (::lapAdapter.isInitialized) {
            lapAdapter.isNumericOptionFormat = isNumeric
        }
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
        }
    }

    private fun configureStopwatchMode() {
        binding.tvWindowTitle.text = "STOPWATCH"
        binding.tvWindowTitle.setTextColor(Color.parseColor("#859990"))
        binding.btnPlayPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        binding.btnCountdownPresetChip.visibility = View.GONE

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
        binding.btnPlayPause.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFAB00"))

        val mins = (if (countdownTotalMillis > 0L) countdownTotalMillis else countdownPresets[countdownPresetIndex]) / 60000
        binding.btnCountdownPresetChip.visibility = View.VISIBLE
        binding.btnCountdownPresetChip.text = "${mins}m ▾"

        binding.tvTimerSubtext.visibility = View.VISIBLE
        binding.tvTimerSubtext.text = "TIME REMAINING"
        binding.tvTimerSubtext.setTextColor(Color.parseColor("#FFD54F"))

        binding.tvActivePacing.visibility = View.VISIBLE
        val showButtons = prefs.getBoolean("pref_show_practice_buttons", true)
        binding.layoutOptionButtons.root.visibility = if (showButtons) View.VISIBLE else View.GONE

        binding.btnToggleLaps.visibility = View.VISIBLE
        binding.tvLapCountBadge.visibility = View.VISIBLE
        binding.btnCountdownPreset.visibility = View.GONE
        binding.btnWallClockToggle.visibility = View.GONE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.btnReset.visibility = View.VISIBLE

        resetCountdown()
    }

    // ==========================================
    // PRACTICE STOPWATCH LOGIC
    // ==========================================

    private fun onOptionTapped(option: OptionChoice) {
        if (!isRunning) return
        if (currentMode != ClockMode.STOPWATCH && currentMode != ClockMode.COUNTDOWN) return

        triggerHapticFeedback()

        val currentElapsed = calculateCurrentElapsedMillis()
        val recordedResponse = sessionManager.recordLap(option, currentElapsed)
        PracticeSessionManager.saveSessionEndOffset(this, recordedResponse.questionNumber)
        updateLiveAveragePace(currentElapsed)

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
            showQuestionNumberPrompt()
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

        persistSessionEndOffsetIfNeeded()

        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        lapAdapter.isPaused = true
        updateLiveAveragePace()
        updateMetricsBanner()
        binding.layoutAccuracyBanner.visibility = View.VISIBLE
        binding.layoutLapsDrawer.visibility = View.VISIBLE
        binding.rvLaps.scrollToPosition(0)
    }

    private fun resetStopwatch() {
        isRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)
        resetDragState()

        persistSessionEndOffsetIfNeeded()

        totalAccumulatedMillis = 0L
        baseStartTime = 0L
        sessionManager.reset()
        sessionManager.startingQuestionOffset = PracticeSessionManager.getPrefetchedStartingQuestion(this)

        binding.tvTimerDisplay.text = "00:00:00.0"
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        lapAdapter.submitList(emptyList())
        lapAdapter.isPaused = false

        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.cardTopTimeSinks.visibility = View.GONE
        hideQuestionNumberPrompt()
        binding.tvLapCountBadge.text = "0"
        binding.tvNoLaps.visibility = View.VISIBLE

        updateActivePacingDisplay(0L)
        updateLiveAveragePace(0L)
    }

    private fun calculateCurrentElapsedMillis(): Long {
        return when (currentMode) {
            ClockMode.COUNTDOWN -> {
                if (isRunning) {
                    val remaining = countdownRemainingMillis - (SystemClock.elapsedRealtime() - baseStartTime)
                    (countdownTotalMillis - remaining).coerceAtLeast(0L)
                } else {
                    (countdownTotalMillis - countdownRemainingMillis).coerceAtLeast(0L)
                }
            }
            else -> {
                if (isRunning) {
                    totalAccumulatedMillis + (SystemClock.elapsedRealtime() - baseStartTime)
                } else {
                    totalAccumulatedMillis
                }
            }
        }
    }

    private fun updateTimerDisplay() {
        val elapsed = calculateCurrentElapsedMillis()
        val showMillis = prefs.getBoolean("pref_show_milliseconds", true)
        val timeString = formatFullStopwatchTime(elapsed, showMillis)
        binding.tvTimerDisplay.text = timeString
        if (isOverlayMinimized) {
            binding.tvMinimizedTimer.text = formatFullStopwatchTime(elapsed, false)
        }

        val split = elapsed - sessionManager.activeSplitBaseTimestamp
        updateActivePacingDisplay(split)
        for (l in timerListeners) {
            l.onTick(elapsed, split, currentMode, isRunning)
        }
    }

    private fun updateActivePacingDisplay(currentSplitMillis: Long) {
        val targetSec = sessionManager.targetDurationSeconds
        val formattedSplit = QuestionResponse.formatDuration(currentSplitMillis)
        val qIndex = String.format("Q%02d", sessionManager.nextQuestionNumber)

        binding.tvActivePacing.text = String.format(
            Locale.US,
            "Active %s • Split: %s",
            qIndex,
            formattedSplit
        )

        val isOvertime = currentSplitMillis > (targetSec * 1000L)
        if (isOvertime) {
            binding.tvActivePacing.setTextColor(Color.parseColor("#FF5252"))
        } else {
            binding.tvActivePacing.setTextColor(Color.parseColor("#859990"))
        }
    }

    private fun updateLiveAveragePace(currentElapsedMillis: Long = calculateCurrentElapsedMillis()) {
        val count = sessionManager.responses.size
        if (count > 0 && currentElapsedMillis > 0L) {
            val avgMillis = currentElapsedMillis / count
            binding.layoutOptionButtons.tvAvgPaceBadge.text = "Avg: ${SessionMetrics.formatAveragePace(avgMillis)}"
        } else {
            binding.layoutOptionButtons.tvAvgPaceBadge.text = "Avg: --"
        }
    }

    private fun updateDrillTagBadge() {
        val formatted = drillTagRepo.getFormattedSessionTag()
        binding.btnFloatingDrillTag.text = formatted
        binding.tvHeaderStatus.text = formatted
    }

    private fun updateMetricsBanner() {
        val elapsed = calculateCurrentElapsedMillis()
        val metrics = sessionManager.calculateMetrics(elapsed)

        binding.tvMetricAvgPace.text = metrics.formattedAvgPace
        binding.tvMetricAccuracy.text = "${metrics.accuracyPercentage}%"
        binding.tvMetricAvgCorrect.text = metrics.formattedAvgCorrect
        binding.tvMetricAvgIncorrect.text = metrics.formattedAvgIncorrect

        val formattedTotal = QuestionResponse.formatDuration(elapsed)
        val avgPace = metrics.formattedAvgPace
        val totalQCount = metrics.totalAnswered + metrics.totalSkipped

        val enableNetMarks = prefs.getBoolean("pref_enable_net_marks", false)
        if (enableNetMarks) {
            val markCorrect = prefs.getString("pref_mark_correct", "2.0")?.toFloatOrNull() ?: 2.0f
            val markNegative = prefs.getString("pref_mark_negative", "0.5")?.toFloatOrNull() ?: 0.5f
            val netScore = metrics.calculateNetScore(markCorrect, markNegative)
            val maxScore = metrics.calculateMaxPossibleScore(markCorrect)

            binding.tvMetricBreakdown.text = String.format(
                Locale.US,
                "[%s] • %d Qs (Total: %s • Avg: %s) • Score: %.1f/%.1f | Acc: %d%% (%d/%d)",
                drillTagRepo.getFormattedSessionTag(),
                totalQCount,
                formattedTotal,
                avgPace,
                netScore,
                maxScore,
                metrics.accuracyPercentage,
                metrics.totalCorrect,
                metrics.totalAnswered
            )
        } else {
            binding.tvMetricBreakdown.text = String.format(
                Locale.US,
                "[%s] • %d Solved (Total: %s • Avg: %s) • %d Correct • %d Wrong • %d Skipped",
                drillTagRepo.getFormattedSessionTag(),
                totalQCount,
                formattedTotal,
                avgPace,
                metrics.totalCorrect,
                metrics.totalIncorrect,
                metrics.totalSkipped
            )
        }

        // Top Time-Sink Outliers ("Top Bottlenecks")
        if (metrics.topTimeSinks.isNotEmpty()) {
            binding.cardTopTimeSinks.visibility = View.VISIBLE
            binding.layoutTimeSinksContainer.removeAllViews()

            if (metrics.totalFlagged > 0) {
                binding.tvReviewNeededBadge.visibility = View.VISIBLE
                binding.tvReviewNeededBadge.text = "Review Needed: ${metrics.totalFlagged} Qs"
            } else {
                binding.tvReviewNeededBadge.visibility = View.GONE
            }

            for (outlier in metrics.topTimeSinks) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 4)
                }

                val tvLabel = TextView(this).apply {
                    val optLabel = if (outlier.selectedOption == OptionChoice.SKIP) "Skip" else outlier.selectedOption.label
                    text = "Q${outlier.questionNumber} — $optLabel"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvDuration = TextView(this).apply {
                    text = "(${outlier.formattedSplit})"
                    textSize = 12f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#FFB74D"))
                    setPadding(12, 0, 12, 0)
                }

                val tvStatus = TextView(this).apply {
                    when (outlier.verificationStatus) {
                        VerificationStatus.CORRECT -> {
                            text = "✔"
                            setTextColor(Color.parseColor("#00E676"))
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                        }
                        VerificationStatus.INCORRECT -> {
                            text = "✖"
                            setTextColor(Color.parseColor("#FF5252"))
                            textSize = 13f
                            setTypeface(null, Typeface.BOLD)
                        }
                        VerificationStatus.SKIPPED -> {
                            text = "[Skipped]"
                            setTextColor(Color.parseColor("#859990"))
                            textSize = 11f
                        }
                        else -> {
                            text = "—"
                            setTextColor(Color.parseColor("#859990"))
                            textSize = 12f
                        }
                    }
                }

                row.addView(tvLabel)
                row.addView(tvDuration)
                row.addView(tvStatus)
                binding.layoutTimeSinksContainer.addView(row)
            }
        } else {
            binding.cardTopTimeSinks.visibility = View.GONE
        }
    }

    // ==========================================
    // COUNTDOWN LOGIC
    // ==========================================

    private fun cycleCountdownPreset() {
        if (isRunning) return
        countdownPresetIndex = (countdownPresetIndex + 1) % countdownPresets.size
        countdownTotalMillis = countdownPresets[countdownPresetIndex]
        countdownRemainingMillis = countdownTotalMillis
        val mins = countdownTotalMillis / 60000
        binding.btnCountdownPresetChip.text = "${mins}m ▾"
        resetCountdown()
        Toast.makeText(this, "Countdown set to ${mins}m", Toast.LENGTH_SHORT).show()
    }

    private fun startCountdownWithPromptCheck() {
        val shouldPrompt = prefs.getBoolean("pref_prompt_start_question_num", true)

        if (shouldPrompt && (countdownRemainingMillis == countdownTotalMillis || countdownRemainingMillis == 0L) && sessionManager.responses.isEmpty()) {
            showQuestionNumberPrompt()
        } else {
            startCountdown()
        }
    }

    private fun startCountdown() {
        loadPreferences()
        if (countdownRemainingMillis <= 0L) {
            countdownRemainingMillis = if (countdownTotalMillis > 0L) countdownTotalMillis else countdownPresets[countdownPresetIndex]
            countdownTotalMillis = countdownRemainingMillis
        }

        isRunning = true
        baseStartTime = SystemClock.elapsedRealtime()
        binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
        binding.layoutAccuracyBanner.visibility = View.GONE
        binding.layoutInlinePrompt.visibility = View.GONE
        lapAdapter.isPaused = false

        tickerHandler.post(tickerRunnable)
    }

    private fun pauseCountdown() {
        if (!isRunning) return

        isRunning = false
        val elapsed = SystemClock.elapsedRealtime() - baseStartTime
        countdownRemainingMillis -= elapsed
        if (countdownRemainingMillis < 0L) countdownRemainingMillis = 0L

        tickerHandler.removeCallbacks(tickerRunnable)
        persistSessionEndOffsetIfNeeded()
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)

        lapAdapter.isPaused = true
        updateLiveAveragePace()
        updateMetricsBanner()
        binding.layoutAccuracyBanner.visibility = View.VISIBLE
        binding.layoutLapsDrawer.visibility = View.VISIBLE
        binding.rvLaps.scrollToPosition(0)
    }

    private fun resetCountdown() {
        isRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)
        resetDragState()

        persistSessionEndOffsetIfNeeded()

        if (countdownTotalMillis <= 0L) {
            countdownTotalMillis = countdownPresets[countdownPresetIndex]
        }
        countdownRemainingMillis = countdownTotalMillis
        binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
        binding.tvTimerDisplay.text = formatCountdownTime(countdownRemainingMillis)

        sessionManager.reset()
        sessionManager.startingQuestionOffset = PracticeSessionManager.getPrefetchedStartingQuestion(this)
        lapAdapter.submitList(emptyList())
        lapAdapter.isPaused = false

        binding.layoutAccuracyBanner.visibility = View.GONE
        hideQuestionNumberPrompt()
        binding.tvLapCountBadge.text = "0"
        binding.tvNoLaps.visibility = View.VISIBLE

        updateActivePacingDisplay(0L)
        updateLiveAveragePace(0L)
    }

    private fun updateCountdownTicker() {
        val elapsed = SystemClock.elapsedRealtime() - baseStartTime
        val remaining = countdownRemainingMillis - elapsed

        if (remaining <= 0L) {
            countdownRemainingMillis = 0L
            isRunning = false
            persistSessionEndOffsetIfNeeded()
            binding.tvTimerDisplay.text = "00:00:00.0"
            if (isOverlayMinimized) {
                binding.tvMinimizedTimer.text = "00:00.0"
            }
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_arrow)
            triggerHapticFeedback()
            Toast.makeText(this, "Countdown Finished!", Toast.LENGTH_SHORT).show()

            lapAdapter.isPaused = true
            updateLiveAveragePace()
            updateMetricsBanner()
            binding.layoutAccuracyBanner.visibility = View.VISIBLE
            binding.layoutLapsDrawer.visibility = View.VISIBLE
            binding.rvLaps.scrollToPosition(0)
            for (l in timerListeners) {
                l.onTick(countdownTotalMillis, 0L, currentMode, false)
                l.onStateChanged(false)
            }
        } else {
            val formatted = formatCountdownTime(remaining)
            binding.tvTimerDisplay.text = formatted
            if (isOverlayMinimized) {
                binding.tvMinimizedTimer.text = formatted
            }
            val currentElapsed = (countdownTotalMillis - remaining).coerceAtLeast(0L)
            val split = (currentElapsed - sessionManager.activeSplitBaseTimestamp).coerceAtLeast(0L)
            updateActivePacingDisplay(split)
            for (l in timerListeners) {
                l.onTick(currentElapsed, split, currentMode, isRunning)
            }
        }
    }

    private fun formatCountdownTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val tenths = (millis % 1000) / 100
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
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

    private fun safeUpdateViewLayout() {
        try {
            if (::binding.isInitialized && binding.root.isAttachedToWindow) {
                windowManager.updateViewLayout(binding.root, layoutParams)
            }
        } catch (_: Exception) {}
    }

    private fun persistSessionEndOffsetIfNeeded() {
        val highest = sessionManager.getHighestCompletedQuestion()
        if (highest > 0) {
            PracticeSessionManager.saveSessionEndOffset(this, highest)
        }
    }

    fun addTimerListener(listener: TimerListener) {
        timerListeners.add(listener)
        val elapsed = calculateCurrentElapsedMillis()
        val split = elapsed - sessionManager.activeSplitBaseTimestamp
        listener.onTick(elapsed, split, currentMode, isRunning)
        listener.onStateChanged(isRunning)
    }

    fun removeTimerListener(listener: TimerListener) {
        timerListeners.remove(listener)
    }

    fun minimizeOverlay() {
        if (::binding.isInitialized) {
            isOverlayMinimized = true
            binding.layoutContentBody.visibility = View.GONE
            binding.layoutBottomResizeBar.visibility = View.GONE
            binding.layoutMinimizedOverlay.visibility = View.VISIBLE
            layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            safeUpdateViewLayout()
        }
    }

    fun restoreOverlay() {
        if (::binding.isInitialized) {
            isOverlayMinimized = false
            binding.layoutContentBody.visibility = View.VISIBLE
            binding.layoutBottomResizeBar.visibility = View.VISIBLE
            binding.layoutMinimizedOverlay.visibility = View.GONE
            val density = resources.displayMetrics.density
            val defaultWidth = (340 * density).toInt()
            val savedWidth = prefs.getInt("pref_overlay_width_px", defaultWidth)
            layoutParams.width = savedWidth
            safeUpdateViewLayout()
        }
    }

    fun getSessionManager(): PracticeSessionManager = sessionManager
    fun getDrillTagRepo(): DrillTagRepository = drillTagRepo
    fun isRunningState(): Boolean = isRunning
    fun getClockMode(): ClockMode = currentMode

    fun triggerPlayPause() {
        when (currentMode) {
            ClockMode.STOPWATCH -> {
                if (isRunning) pauseStopwatch() else startStopwatchWithPromptCheck()
            }
            ClockMode.COUNTDOWN -> {
                if (isRunning) pauseCountdown() else startCountdownWithPromptCheck()
            }
        }
        for (l in timerListeners) l.onStateChanged(isRunning)
    }

    fun triggerReset() {
        when (currentMode) {
            ClockMode.STOPWATCH -> resetStopwatch()
            ClockMode.COUNTDOWN -> resetCountdown()
        }
        for (l in timerListeners) l.onReset()
    }

    fun triggerOption(option: OptionChoice) {
        onOptionTapped(option)
        for (l in timerListeners) l.onResponseLogged()
    }

    fun triggerFlagToggle(questionNumber: Int) {
        sessionManager.toggleFlag(questionNumber)
        lapAdapter.submitList(sessionManager.responses.map { it.copy() })
        for (l in timerListeners) l.onResponseLogged()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService == this) activeService = null
        persistSessionEndOffsetIfNeeded()
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        tickerHandler.removeCallbacks(tickerRunnable)
        try {
            if (::binding.isInitialized && binding.root.isAttachedToWindow) {
                windowManager.removeView(binding.root)
            }
        } catch (_: Exception) {}
    }
}
