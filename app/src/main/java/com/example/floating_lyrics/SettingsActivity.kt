package com.example.floating_lyrics

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        preferencesManager = PreferencesManager(this)
        if (preferencesManager.getBoolean(PreferencesManager.KEY_DARK_MODE, false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        // Set the theme before super.onCreate()
        val themeId = preferencesManager.getInt(PreferencesManager.KEY_COLOR_SCHEME, R.style.Theme_Floating_lyrics_App)
        setTheme(themeId)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val useDarkModeSwitch = findViewById<SwitchMaterial>(R.id.use_dark_mode_switch)
        useDarkModeSwitch.isChecked = preferencesManager.getBoolean(PreferencesManager.KEY_DARK_MODE, false)
        useDarkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.saveBoolean(PreferencesManager.KEY_DARK_MODE, isChecked)
            recreate()
        }

        val colorSchemeLayout = findViewById<View>(R.id.color_scheme_layout)
        val colorSchemeView = findViewById<View>(R.id.color_scheme_view)

        val typedValue = TypedValue()
        val theme: Resources.Theme = getTheme()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        val savedColor = typedValue.data

        colorSchemeView.setBackgroundColor(savedColor)

        colorSchemeLayout.setOnClickListener {
            showColorPalette()
        }

        val bugReportLayout = findViewById<View>(R.id.bug_report_layout)
        bugReportLayout.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://github.com/dhaval2404/colorpicker/issues")
            startActivity(intent)
        }

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.selectedItemId = R.id.navigation_settings
        bottomNavigationView.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.navigation_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun showColorPalette() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_color_palette, null)
        val colorGrid = dialogView.findViewById<GridLayout>(R.id.color_palette_grid)

        val colors = mapOf(
            R.color.catppuccin to "Catppuccin",
            R.color.green_apple to "Green Apple",
            R.color.lavender to "Lavender",
            R.color.midnight_dusk to "Midnight Dusk",
            R.color.nord to "Nord",
            R.color.strawberry_daiquiri to "Strawberry Daiquiri",
            R.color.tako to "Tako",
            R.color.teal_turquoise to "Teal & Turquoise",
            R.color.tidal_wave to "Tidal Wave",
            R.color.yin_yang to "Yin & Yang",
            R.color.yotsuba to "Yotsuba",
            R.color.monochrom to "Monochrom"
        )

        for ((colorResId, colorName) in colors) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.grid_item_color, colorGrid, false)
            val colorView = itemView.findViewById<ImageView>(R.id.color_view)
            val colorNameView = itemView.findViewById<TextView>(R.id.color_name)

            val color = ContextCompat.getColor(this, colorResId)
            colorView.setBackgroundColor(color)
            colorNameView.text = colorName

            itemView.setOnClickListener {
                val themeId = getThemeForColor(colorResId)
                preferencesManager.saveInt(PreferencesManager.KEY_COLOR_SCHEME, themeId)
                preferencesManager.saveInt(PreferencesManager.KEY_APP_COLOR, color) // Save the selected color
                preferencesManager.saveBoolean(PreferencesManager.KEY_USE_APP_COLOR, true) // Also enable "Use App Color"
                recreate()
            }
            colorGrid.addView(itemView)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getThemeForColor(colorResId: Int): Int {
        return when (colorResId) {
            R.color.catppuccin -> R.style.Theme_Floating_lyrics_App_Catppuccin
            R.color.green_apple -> R.style.Theme_Floating_lyrics_App_GreenApple
            R.color.lavender -> R.style.Theme_Floating_lyrics_App_Lavender
            R.color.midnight_dusk -> R.style.Theme_Floating_lyrics_App_MidnightDusk
            R.color.nord -> R.style.Theme_Floating_lyrics_App_Nord
            R.color.strawberry_daiquiri -> R.style.Theme_Floating_lyrics_App_StrawberryDaiquiri
            R.color.tako -> R.style.Theme_Floating_lyrics_App_Tako
            R.color.teal_turquoise -> R.style.Theme_Floating_lyrics_App_TealTurquoise
            R.color.tidal_wave -> R.style.Theme_Floating_lyrics_App_TidalWave
            R.color.yin_yang -> R.style.Theme_Floating_lyrics_App_YinYang
            R.color.yotsuba -> R.style.Theme_Floating_lyrics_App_Yotsuba
            R.color.monochrom -> R.style.Theme_Floating_lyrics_App_Monochrom
            else -> R.style.Theme_Floating_lyrics_App
        }
    }
}
