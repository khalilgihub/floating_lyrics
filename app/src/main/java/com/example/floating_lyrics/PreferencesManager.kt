package com.example.floating_lyrics

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun saveBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun saveInt(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun saveFloat(key: String, value: Float) {
        sharedPreferences.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return sharedPreferences.getFloat(key, defaultValue)
    }

    fun saveLong(key: String, value: Long) {
        sharedPreferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }

    fun saveString(key: String, value: String?) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String?): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    companion object {
        const val PREFS_NAME = "floating_lyrics_prefs"
        const val KEY_USE_APP_COLOR = "use_app_color"
        const val KEY_COLOR_SCHEME = "color_scheme"
        const val KEY_APP_COLOR = "app_color"
        const val KEY_CUSTOM_BACKGROUND_COLOR = "custom_background_color"
        const val KEY_WINDOW_OPACITY = "window_opacity"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LYRICS_FONT_SIZE = "lyrics_font_size"
        const val KEY_LYRICS_BOLD = "lyrics_bold" // New Key
        const val KEY_CUSTOM_TEXT_COLOR = "custom_text_color"
        const val KEY_SHOW_PROGRESS_BAR = "show_progress_bar"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_HIDE_MILLISECONDS = "hide_milliseconds"
        const val KEY_SHOW_NO_LYRICS_TEXT = "show_no_lyrics_text"
        const val KEY_HIDE_LINE_2 = "hide_line_2"
        const val KEY_ENABLE_ANIMATION = "enable_animation"
        const val KEY_ANIMATION_TYPE = "animation_type" // NEW: "none", "fade", "slide", "typewriter"
        const val KEY_IGNORE_TOUCH = "ignore_touch"
        const val KEY_TOUCH_THROUGH = "touch_through"
        const val KEY_IS_SHRUNKEN = "is_shrunken"
        const val KEY_LYRICS_OFFSET = "lyrics_offset"
        const val KEY_AUTO_OFFSET = "auto_offset"

        // Animation types
        const val ANIMATION_NONE = "none"
        const val ANIMATION_FADE = "fade"
        const val ANIMATION_SLIDE = "slide"
        const val ANIMATION_TYPEWRITER = "typewriter"
    }
}
