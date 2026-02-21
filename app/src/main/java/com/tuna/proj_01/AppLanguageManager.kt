package com.tuna.proj_01

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLanguageManager {

    private const val PREF_NAME = "app_prefs"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"
    private val SUPPORTED_TAGS = listOf("ko", "ja", "en")

    fun getSupportedLanguageTags(): List<String> = SUPPORTED_TAGS

    fun getSelectedLanguageTag(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE_TAG, null)
        if (saved != null) return saved
        val systemLang = Locale.getDefault().language
        return if (SUPPORTED_TAGS.contains(systemLang)) systemLang else "ko"
    }

    fun setLanguage(context: Context, languageTag: String, applyNow: Boolean): Boolean {
        val current = getSelectedLanguageTag(context)
        if (current == languageTag) return false
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, languageTag)
            .apply()
        return true
    }

    fun createLocalizedContext(context: Context, languageTag: String): Context {
        val locale = Locale(languageTag)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
