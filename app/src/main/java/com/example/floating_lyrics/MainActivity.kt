package com.example.floating_lyrics

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private var isFloatingLyricsServiceRunning = false
    private lateinit var seekbarLyricsOffset: SeekBar
    private lateinit var offsetValueText: TextView

    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingLyricsService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferencesManager = PreferencesManager(this)
        // Set the theme before super.onCreate()
        val themeId = preferencesManager.getInt(PreferencesManager.KEY_COLOR_SCHEME, R.style.Theme_Floating_lyrics_App)
        setTheme(themeId)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.navigation_home -> {
                    // Handle home navigation
                    true
                }
                R.id.navigation_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        val stylingHeader = findViewById<LinearLayout>(R.id.styling_header)
        val stylingContent = findViewById<LinearLayout>(R.id.styling_content_layout)
        val stylingChevron = findViewById<ImageView>(R.id.styling_chevron)

        val elementVisibilitiesHeader = findViewById<LinearLayout>(R.id.element_visibilities_header)
        val elementVisibilitiesContent = findViewById<LinearLayout>(R.id.element_visibilities_content_layout)
        val elementVisibilitiesChevron = findViewById<ImageView>(R.id.element_visibilities_chevron)

        val specialSettingsHeader = findViewById<LinearLayout>(R.id.special_settings_header)
        val specialSettingsContent = findViewById<LinearLayout>(R.id.special_settings_content_layout)
        val specialSettingsChevron = findViewById<ImageView>(R.id.special_settings_chevron)

        val playASongLayout = findViewById<LinearLayout>(R.id.play_a_song_layout)
        val notificationListenerCard = findViewById<MaterialCardView>(R.id.notification_listener_card)
        val windowConfigsButton = findViewById<Button>(R.id.window_configs_button)
        val switchUseAppColor = findViewById<SwitchMaterial>(R.id.switch_use_app_color)
        val customBackgroundColorLayout = findViewById<RelativeLayout>(R.id.custom_background_color_layout)
        val seekbarWindowOpacity = findViewById<SeekBar>(R.id.seekbar_window_opacity)
        val fontFamilyLayout = findViewById<RelativeLayout>(R.id.font_family_layout)
        val fontFamilyValue = findViewById<TextView>(R.id.font_family_value)
        val seekbarLyricsFontSize = findViewById<SeekBar>(R.id.seekbar_lyrics_font_size)
        val customTextColorLayout = findViewById<RelativeLayout>(R.id.custom_text_color_layout)
        val switchHideMilliseconds = findViewById<SwitchMaterial>(R.id.switch_hide_milliseconds)
        val switchShowProgressBar = findViewById<SwitchMaterial>(R.id.switch_show_progress_bar)
        val switchShowNoLyricsText = findViewById<SwitchMaterial>(R.id.switch_show_no_lyrics_text)
        val switchHideLine2 = findViewById<SwitchMaterial>(R.id.switch_hide_line_2)
        val switchEnableAnimation = findViewById<SwitchMaterial>(R.id.switch_enable_animation)
        val switchIgnoreTouch = findViewById<SwitchMaterial>(R.id.switch_ignore_touch)
        val switchTouchThrough = findViewById<SwitchMaterial>(R.id.switch_touch_through)
        val showButton = findViewById<FloatingActionButton>(R.id.show_button)

        // Load preferences
        switchUseAppColor.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_USE_APP_COLOR, true)
        seekbarWindowOpacity.progress = preferencesManager.getFloat(PreferencesManager.KEY_WINDOW_OPACITY, 100f).toInt()
        fontFamilyValue.text = preferencesManager.getString(PreferencesManager.KEY_FONT_FAMILY, "Roboto")
        seekbarLyricsFontSize.progress = preferencesManager.getFloat(PreferencesManager.KEY_LYRICS_FONT_SIZE, 20f).toInt()
        switchHideMilliseconds.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_HIDE_MILLISECONDS, false)
        switchShowProgressBar.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_SHOW_PROGRESS_BAR, false)
        switchShowNoLyricsText.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_SHOW_NO_LYRICS_TEXT, false)
        switchHideLine2.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_HIDE_LINE_2, false)
        switchEnableAnimation.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_ENABLE_ANIMATION, false)
        switchIgnoreTouch.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_IGNORE_TOUCH, false)
        switchTouchThrough.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_TOUCH_THROUGH, false)

        updateColorOptionsState(switchUseAppColor.isChecked)
        updateStylingColors()
        updateShowButton()


        stylingHeader.setOnClickListener {
            if (stylingContent.visibility == View.VISIBLE) {
                stylingContent.visibility = View.GONE
                stylingChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                stylingContent.visibility = View.VISIBLE
                stylingChevron.setImageResource(R.drawable.ic_chevron_up)
                elementVisibilitiesContent.visibility = View.GONE
                elementVisibilitiesChevron.setImageResource(R.drawable.ic_chevron_down)
                specialSettingsContent.visibility = View.GONE
                specialSettingsChevron.setImageResource(R.drawable.ic_chevron_down)
            }
        }

        elementVisibilitiesHeader.setOnClickListener {
            if (elementVisibilitiesContent.visibility == View.VISIBLE) {
                elementVisibilitiesContent.visibility = View.GONE
                elementVisibilitiesChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                elementVisibilitiesContent.visibility = View.VISIBLE
                elementVisibilitiesChevron.setImageResource(R.drawable.ic_chevron_up)
                stylingContent.visibility = View.GONE
                stylingChevron.setImageResource(R.drawable.ic_chevron_down)
                specialSettingsContent.visibility = View.GONE
                specialSettingsChevron.setImageResource(R.drawable.ic_chevron_down)
            }
        }

        specialSettingsHeader.setOnClickListener {
            if (specialSettingsContent.visibility == View.VISIBLE) {
                specialSettingsContent.visibility = View.GONE
                specialSettingsChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                specialSettingsContent.visibility = View.VISIBLE
                specialSettingsChevron.setImageResource(R.drawable.ic_chevron_up)
                stylingContent.visibility = View.GONE
                stylingChevron.setImageResource(R.drawable.ic_chevron_down)
                elementVisibilitiesContent.visibility = View.GONE
                elementVisibilitiesChevron.setImageResource(R.drawable.ic_chevron_down)
            }
        }

        playASongLayout.setOnClickListener {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_MUSIC)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        notificationListenerCard.setOnClickListener {
            checkNotificationPermission()
        }

        windowConfigsButton.setOnClickListener {
            Toast.makeText(this, "Window Configs Clicked", Toast.LENGTH_SHORT).show()
        }

        switchUseAppColor.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_USE_APP_COLOR, isChecked)
            if (isChecked) {
                val typedValue = TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
                preferencesManager.saveInt(PreferencesManager.KEY_APP_COLOR, typedValue.data)
            }
            updateColorOptionsState(isChecked)
            updateStylingColors()
        }

        customBackgroundColorLayout.setOnClickListener {
            ColorPickerDialog
                .Builder(this)
                .setTitle("Pick Background Color")
                .setColorShape(ColorShape.SQAURE)
                .setDefaultColor(preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_BACKGROUND_COLOR, Color.BLACK))
                .setColorListener { color, _ ->
                    preferencesManager.saveInt(PreferencesManager.KEY_CUSTOM_BACKGROUND_COLOR, color)
                    updateStylingColors()
                }
                .show()
        }

        seekbarWindowOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preferencesManager.saveFloat(PreferencesManager.KEY_WINDOW_OPACITY, progress.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        fontFamilyLayout.setOnClickListener {
            val fonts = arrayOf("Roboto", "sans-serif", "monospace")
            AlertDialog.Builder(this)
                .setTitle("Select Font Family")
                .setItems(fonts) { _, which ->
                    val selectedFont = fonts[which]
                    fontFamilyValue.text = selectedFont
                    preferencesManager.saveString(PreferencesManager.KEY_FONT_FAMILY, selectedFont)
                }
                .show()
        }

        seekbarLyricsFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preferencesManager.saveFloat(PreferencesManager.KEY_LYRICS_FONT_SIZE, progress.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // NEW: Lyrics Offset Control
        seekbarLyricsOffset = findViewById(R.id.seekbar_lyrics_offset)
        offsetValueText = findViewById(R.id.offset_value_text)

        // Load current offset (range: -2000ms to +2000ms, center at 0)
        val currentOffset = preferencesManager.getLong(PreferencesManager.KEY_LYRICS_OFFSET, 0L)
        val seekbarValue = ((currentOffset + 2000) / 40).toInt() // Convert -2000~2000 to 0~100
        seekbarLyricsOffset.progress = seekbarValue
        offsetValueText.text = String.format("%.1fs", currentOffset / 1000.0)

        seekbarLyricsOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Convert 0~100 to -2000~2000 milliseconds
                val offsetMs = (progress * 40) - 2000L
                preferencesManager.saveLong(PreferencesManager.KEY_LYRICS_OFFSET, offsetMs)
                offsetValueText.text = String.format("%.1fs", offsetMs / 1000.0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        customTextColorLayout.setOnClickListener {
            ColorPickerDialog
                .Builder(this)
                .setTitle("Pick Text Color")
                .setColorShape(ColorShape.SQAURE)
                .setDefaultColor(preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_TEXT_COLOR, Color.WHITE))
                .setColorListener { color, _ ->
                    preferencesManager.saveInt(PreferencesManager.KEY_CUSTOM_TEXT_COLOR, color)
                    updateStylingColors()
                }
                .show()
        }

        switchHideMilliseconds.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_HIDE_MILLISECONDS, isChecked)
        }

        switchShowProgressBar.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_SHOW_PROGRESS_BAR, isChecked)
        }

        switchShowNoLyricsText.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_SHOW_NO_LYRICS_TEXT, isChecked)
        }

        switchHideLine2.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_HIDE_LINE_2, isChecked)
        }

        // NEW: Animation with type picker
        switchEnableAnimation.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_ENABLE_ANIMATION, isChecked)

            // Show animation type picker when enabled
            if (isChecked) {
                showAnimationTypePicker()
            }
        }

        switchIgnoreTouch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_IGNORE_TOUCH, isChecked)
        }

        switchTouchThrough.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_TOUCH_THROUGH, isChecked)
        }

        showButton.setOnClickListener {
            if (isFloatingLyricsServiceRunning) {
                stopFloatingLyricsService()
            } else {
                checkOverlayPermission()
            }
        }

        checkNotificationPermission()
    }

    private fun showAnimationTypePicker() {
        val animations = arrayOf("Fade In", "Slide", "Typewriter")
        val animationValues = arrayOf(
            PreferencesManager.ANIMATION_FADE,
            PreferencesManager.ANIMATION_SLIDE,
            PreferencesManager.ANIMATION_TYPEWRITER
        )

        val currentType = preferencesManager.getString(
            PreferencesManager.KEY_ANIMATION_TYPE,
            PreferencesManager.ANIMATION_FADE
        )
        val currentIndex = animationValues.indexOf(currentType).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("Select Animation Type")
            .setSingleChoiceItems(animations, currentIndex) { dialog, which ->
                preferencesManager.saveString(PreferencesManager.KEY_ANIMATION_TYPE, animationValues[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStylingColors() {
        val backgroundColorView = findViewById<View>(R.id.background_color_view)
        val textColorView = findViewById<View>(R.id.text_color_view)
        val useAppColor = preferencesManager.getBoolean(PreferencesManager.KEY_USE_APP_COLOR, true)

        if (useAppColor) {
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            val appColor = typedValue.data

            val hsv = FloatArray(3)
            Color.colorToHSV(appColor, hsv)

            // Darker background
            hsv[2] *= 0.8f // Reduce brightness to 80%
            val darkerColor = Color.HSVToColor(hsv)

            // Lighter text
            Color.colorToHSV(appColor, hsv) // Reset HSV
            hsv[1] *= 0.5f // Reduce saturation to 50%
            hsv[2] = 1.0f // Max brightness
            val lighterColor = Color.HSVToColor(hsv)

            backgroundColorView.setBackgroundColor(darkerColor)
            textColorView.setBackgroundColor(lighterColor)
        } else {
            backgroundColorView.setBackgroundColor(preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_BACKGROUND_COLOR, Color.BLACK))
            textColorView.setBackgroundColor(preferencesManager.getInt(PreferencesManager.KEY_CUSTOM_TEXT_COLOR, Color.WHITE))
        }
    }

    private fun updateColorOptionsState(isAppColorUsed: Boolean) {
        val customBackgroundColorLayout = findViewById<RelativeLayout>(R.id.custom_background_color_layout)
        val customTextColorLayout = findViewById<RelativeLayout>(R.id.custom_text_color_layout)

        customBackgroundColorLayout.isEnabled = !isAppColorUsed
        customBackgroundColorLayout.alpha = if (isAppColorUsed) 0.5f else 1.0f
        customTextColorLayout.isEnabled = !isAppColorUsed
        customTextColorLayout.alpha = if (isAppColorUsed) 0.5f else 1.0f
    }

    private fun updateShowButton() {
        val showButton = findViewById<FloatingActionButton>(R.id.show_button)
        if (isFloatingLyricsServiceRunning) {
            showButton.setImageResource(R.drawable.ic_close)
        } else {
            showButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } else {
            startFloatingLyricsService()
        }
    }

    private fun startFloatingLyricsService() {
        val intent = Intent(this, FloatingLyricsService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isFloatingLyricsServiceRunning = true
        updateShowButton()
    }

    private fun stopFloatingLyricsService() {
        val intent = Intent(this, FloatingLyricsService::class.java)
        stopService(intent)
        isFloatingLyricsServiceRunning = false
        updateShowButton()
    }

    private fun checkNotificationPermission() {
        if (!isNotificationServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}