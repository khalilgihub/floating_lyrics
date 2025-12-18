package com.example.floating_lyrics

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class FloatingLyricsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var titleBarContainer: RelativeLayout
    private lateinit var trackTitle: TextView
    private lateinit var lyricsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var currentTime: TextView
    private lateinit var totalDuration: TextView
    private lateinit var lockButton: ImageView
    private lateinit var minimizeButton: ImageView
    private lateinit var closeButton: ImageView
    private lateinit var progressBarContainer: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentLine: String? = null
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var gestureDetector: GestureDetector

    // Animation
    private var typewriterHandler: Handler? = null
    private var typewriterRunnable: Runnable? = null
    private var currentTypewriterIndex = 0
    private var targetText = ""

    // Smart offset detection
    private var currentPlayerPackage: String? = null
    private val DEFAULT_OFFSET = -200L

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("SetTextI18n", "InflateParams", "ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        LyricsRepository.setFloatingLyricsActive(true)

        preferencesManager = PreferencesManager(this)
        preferencesManager.registerOnSharedPreferenceChangeListener(this)

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "floating_lyrics_channel")
            .setContentTitle("Floating Lyrics")
            .setContentText("Lyrics are floating")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_lyrics_layout, null)
        titleBarContainer = floatingView.findViewById(R.id.title_bar_container)
        trackTitle = floatingView.findViewById(R.id.track_title)
        lyricsText = floatingView.findViewById(R.id.lyrics_text)
        progressBar = floatingView.findViewById(R.id.progress_bar)
        progressBarContainer = floatingView.findViewById(R.id.progress_bar_container)
        currentTime = floatingView.findViewById(R.id.current_time)
        totalDuration = floatingView.findViewById(R.id.total_duration)
        lockButton = floatingView.findViewById(R.id.lock_button)
        minimizeButton = floatingView.findViewById(R.id.minimize_button)
        closeButton = floatingView.findViewById(R.id.close_button)

        lyricsText.text = "Waiting for music..."

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        applyPreferences()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val isShrunken = !preferencesManager.getBoolean(PreferencesManager.KEY_IS_SHRUNKEN, false)
                preferencesManager.saveBoolean(PreferencesManager.KEY_IS_SHRUNKEN, isShrunken)
                applyShrunkenState(isShrunken)
                return true
            }
        })

        // The main collection logic with Cloud Romaji Conversion
        serviceScope.launch {
            combine(
                LyricsRepository.nowPlaying,
                LyricsRepository.currentPosition,
                LyricsRepository.isPlaying
            ) { nowPlaying, position, isPlaying ->
                Triple(nowPlaying, position, isPlaying)
            }.collect { (nowPlaying, position, isPlaying) ->
                if (nowPlaying == null) {
                    trackTitle.text = ""
                    updateLyricsText("Waiting for music...")
                    progressBar.max = 0
                    progressBar.progress = 0
                    currentTime.text = formatMillis(0)
                    totalDuration.text = formatMillis(0)
                    return@collect
                }

                trackTitle.text = "${nowPlaying.title} - ${nowPlaying.artist}"
                totalDuration.text = formatMillis(nowPlaying.duration)
                currentTime.text = formatMillis(position)
                progressBar.max = nowPlaying.duration.toInt()
                progressBar.progress = position.toInt()

                if (nowPlaying.lyrics == null) {
                    if (isPlaying) {
                        updateLyricsText("Searching for lyrics...")
                    } else {
                        updateLyricsText("Paused")
                    }
                    return@collect
                }

                val lyricsData = nowPlaying.lyrics

                if (lyricsData.lines.isEmpty()) {
                    updateLyricsText(lyricsData.fullText)
                    return@collect
                }

                // Apply smart offset
                val manualOffset = preferencesManager.getLong(PreferencesManager.KEY_LYRICS_OFFSET, 0L)
                val autoOffset = getAutoOffsetForPlayer()
                val totalOffset = manualOffset + autoOffset
                val adjustedPosition = position + totalOffset

                val activeLine = lyricsData.lines.lastOrNull { it.time <= adjustedPosition }?.text

                if (activeLine != null && activeLine != currentLine) {
                    currentLine = activeLine

                    // --- CLOUD CONVERSION LOGIC ---
                    serviceScope.launch {
                        // 1. Fetch High-Accuracy Romaji from your Server
                        val finalLyric = LyricsRepository.getRomajiFromCloud(activeLine)

                        // 2. Update UI
                        updateLyricsText(finalLyric)
                    }
                }
            }
        }

        // Dragging logic
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialY = 0
            private var initialTouchY = 0f

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (gestureDetector.onTouchEvent(event)) {
                    return true
                }

                if (preferencesManager.getBoolean(PreferencesManager.KEY_IGNORE_TOUCH, false)) {
                    return false
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params.y
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        lockButton.setOnClickListener {
            val ignoreTouch = !preferencesManager.getBoolean(PreferencesManager.KEY_IGNORE_TOUCH, false)
            preferencesManager.saveBoolean(PreferencesManager.KEY_IGNORE_TOUCH, ignoreTouch)
            applyPreferences()
        }

        minimizeButton.setOnClickListener {
            val isShrunken = !preferencesManager.getBoolean(PreferencesManager.KEY_IS_SHRUNKEN, false)
            preferencesManager.saveBoolean(PreferencesManager.KEY_IS_SHRUNKEN, isShrunken)
            applyShrunkenState(isShrunken)
        }

        closeButton.setOnClickListener {
            stopSelf()
        }
    }

    private fun updateLyricsText(newText: String) {
        val enableAnimation = preferencesManager.getBoolean(PreferencesManager.KEY_ENABLE_ANIMATION, false)
        val animationType = preferencesManager.getString(
            PreferencesManager.KEY_ANIMATION_TYPE,
            PreferencesManager.ANIMATION_NONE
        )

        // Cancel any ongoing animations
        cancelTypewriterAnimation()

        if (!enableAnimation || animationType == PreferencesManager.ANIMATION_NONE) {
            lyricsText.text = newText
            return
        }

        when (animationType) {
            PreferencesManager.ANIMATION_FADE -> {
                animateFade(newText)
            }
            PreferencesManager.ANIMATION_SLIDE -> {
                animateSlide(newText)
            }
            PreferencesManager.ANIMATION_TYPEWRITER -> {
                animateTypewriter(newText)
            }
            else -> {
                lyricsText.text = newText
            }
        }
    }

    private fun animateFade(newText: String) {
        lyricsText.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                lyricsText.text = newText
                lyricsText.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }

    private fun animateSlide(newText: String) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()

        // Slide out to left
        lyricsText.animate()
            .translationX(-screenWidth)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                lyricsText.text = newText
                lyricsText.translationX = screenWidth

                // Slide in from right
                lyricsText.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun animateTypewriter(newText: String) {
        targetText = newText
        currentTypewriterIndex = 0
        lyricsText.text = ""

        if (typewriterHandler == null) {
            typewriterHandler = Handler(Looper.getMainLooper())
        }

        typewriterRunnable = object : Runnable {
            override fun run() {
                if (currentTypewriterIndex <= targetText.length) {
                    lyricsText.text = targetText.substring(0, currentTypewriterIndex)
                    currentTypewriterIndex++

                    // Adjust speed: faster for longer texts
                    val delay = if (targetText.length > 50) 20L else 35L
                    typewriterHandler?.postDelayed(this, delay)
                }
            }
        }

        typewriterHandler?.post(typewriterRunnable!!)
    }

    private fun cancelTypewriterAnimation() {
        typewriterRunnable?.let {
            typewriterHandler?.removeCallbacks(it)
        }
        typewriterRunnable = null
    }

    private fun getAutoOffsetForPlayer(): Long {
        val packageKey = "offset_$currentPlayerPackage"
        val savedOffset = preferencesManager.getLong(packageKey, Long.MIN_VALUE)

        return if (savedOffset == Long.MIN_VALUE) {
            preferencesManager.saveLong(packageKey, DEFAULT_OFFSET)
            DEFAULT_OFFSET
        } else {
            savedOffset
        }
    }

    fun setPlayerPackage(packageName: String) {
        currentPlayerPackage = packageName
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Floating Lyrics"
            val descriptionText = "Channel for floating lyrics notification"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("floating_lyrics_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LyricsRepository.setFloatingLyricsActive(false)
        cancelTypewriterAnimation()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        preferencesManager.unregisterOnSharedPreferenceChangeListener(this)
        serviceScope.cancel()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        applyPreferences()
    }

    private fun applyPreferences() {
        val useAppColor = preferencesManager.getBoolean(PreferencesManager.KEY_USE_APP_COLOR, true)
        val appColor = preferencesManager.getInt(PreferencesManager.KEY_APP_COLOR, ContextCompat.getColor(this, R.color.catppuccin))
        val customBackgroundColor = preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_BACKGROUND_COLOR, Color.BLACK)
        val windowOpacity = preferencesManager.getFloat(PreferencesManager.KEY_WINDOW_OPACITY, 100f)
        val fontFamily = preferencesManager.getString(PreferencesManager.KEY_FONT_FAMILY, "Roboto")
        val lyricsFontSize = preferencesManager.getFloat(PreferencesManager.KEY_LYRICS_FONT_SIZE, 20f)
        val customTextColor = preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_TEXT_COLOR, Color.WHITE)
        val showProgressBar = preferencesManager.getBoolean(PreferencesManager.KEY_SHOW_PROGRESS_BAR, true)
        val ignoreTouch = preferencesManager.getBoolean(PreferencesManager.KEY_IGNORE_TOUCH, false)
        val touchThrough = preferencesManager.getBoolean(PreferencesManager.KEY_TOUCH_THROUGH, false)
        val isShrunken = preferencesManager.getBoolean(PreferencesManager.KEY_IS_SHRUNKEN, false)
        val isBold = preferencesManager.getBoolean(PreferencesManager.KEY_LYRICS_BOLD, false)
        val hideMilliseconds = preferencesManager.getBoolean(PreferencesManager.KEY_HIDE_MILLISECONDS, false)

        applyShrunkenState(isShrunken)

        val backgroundColor: Int
        val textColor: Int

        if (useAppColor) {
            val hsv = FloatArray(3)
            Color.colorToHSV(appColor, hsv)

            hsv[2] *= 0.8f
            backgroundColor = Color.HSVToColor(hsv)

            Color.colorToHSV(appColor, hsv)
            hsv[1] *= 0.2f
            hsv[2] = 1.0f
            textColor = Color.HSVToColor(hsv)
        } else {
            backgroundColor = customBackgroundColor
            textColor = customTextColor
        }

        val alphaFraction = windowOpacity / 100f
        val alpha = (alphaFraction * 255).toInt()

        val red = Color.red(backgroundColor)
        val green = Color.green(backgroundColor)
        val blue = Color.blue(backgroundColor)
        val finalBackgroundColor = Color.argb(alpha, red, green, blue)

        val background = floatingView.findViewById<View>(R.id.floating_lyrics_root).background
        if (background is GradientDrawable) {
            background.setColor(finalBackgroundColor)
        } else {
            val newBackground = GradientDrawable()
            newBackground.setColor(finalBackgroundColor)
            floatingView.findViewById<View>(R.id.floating_lyrics_root).background = newBackground
        }

        trackTitle.setTextColor(textColor)
        lyricsText.textSize = lyricsFontSize
        lyricsText.setTextColor(textColor)

        // Force reset typeface before applying new style
        lyricsText.typeface = Typeface.DEFAULT

        try {
            val style = if (isBold) Typeface.BOLD else Typeface.NORMAL
            lyricsText.typeface = Typeface.create(fontFamily, style)
        } catch (e: Exception) {
            // Fallback
             lyricsText.setTypeface(null, if (isBold) Typeface.BOLD else Typeface.NORMAL)
        }

        progressBarContainer.visibility = if (showProgressBar) View.VISIBLE else View.GONE
        currentTime.setTextColor(textColor)
        totalDuration.setTextColor(textColor)
        
        if (hideMilliseconds) {
            currentTime.visibility = View.GONE
            totalDuration.visibility = View.GONE
        } else {
            currentTime.visibility = View.VISIBLE
            totalDuration.visibility = View.VISIBLE
        }

        if (ignoreTouch) {
            lockButton.setImageResource(R.drawable.ic_lock)
        } else {
            lockButton.setImageResource(R.drawable.ic_lock_open)
        }

        params.flags = if (touchThrough) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        }

        if (ignoreTouch) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

        windowManager.updateViewLayout(floatingView, params)
    }

    private fun applyShrunkenState(isShrunken: Boolean) {
        if (isShrunken) {
            titleBarContainer.visibility = View.GONE
        } else {
            titleBarContainer.visibility = View.VISIBLE
        }
        lyricsText.visibility = View.VISIBLE
        val showProgressBar = preferencesManager.getBoolean(PreferencesManager.KEY_SHOW_PROGRESS_BAR, true)
        progressBarContainer.visibility = if (showProgressBar) View.VISIBLE else View.GONE
    }

    private fun formatMillis(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}
