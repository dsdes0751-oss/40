package com.tuna.proj_01

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppThemeManager {
    private const val PREF_NAME = "app_theme_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val DEFAULT_THEME_MODE = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun applyPersistedTheme(context: Context) {
        val mode = getThemeMode(context)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).commit()
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    fun getThemeMode(context: Context): Int {
        val stored = prefs(context).getInt(KEY_THEME_MODE, Int.MIN_VALUE)
        if (stored != Int.MIN_VALUE) return stored

        val current = AppCompatDelegate.getDefaultNightMode()
        return if (current == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            DEFAULT_THEME_MODE
        } else {
            current
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
