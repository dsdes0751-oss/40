package com.tuna.proj_01

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object AppLanguageManager {

    private const val PREF_NAME = "app_prefs"
    private const val KEY_LANGUAGE_TAG = "language_tag"
    private const val DEFAULT_LANGUAGE_TAG = "en"
    private val SUPPORTED_LANGUAGE_TAGS = listOf("ko", "ja", "en")

    fun getSupportedLanguageTags(): List<String> = SUPPORTED_LANGUAGE_TAGS

    fun getSelectedLanguageTag(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedTag = prefs.getString(KEY_LANGUAGE_TAG, null)
        val fallback = Locale.getDefault().toLanguageTag()
        return normalizeLanguageTag(savedTag ?: fallback)
    }

    fun setLanguage(context: Context, languageTag: String, applyNow: Boolean = true): Boolean {
        val normalizedTag = normalizeLanguageTag(languageTag)
        val current = getSelectedLanguageTag(context)
        if (current == normalizedTag) {
            return false
        }

        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, normalizedTag)
            .apply()

        if (applyNow) {
            applyLocale(normalizedTag)
        }

        return true
    }

    fun createLocalizedContext(base: Context, languageTag: String): Context {
        val normalizedTag = normalizeLanguageTag(languageTag)
        val locale = Locale.forLanguageTag(normalizedTag)
        applyLocale(normalizedTag)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    private fun applyLocale(languageTag: String) {
        Locale.setDefault(Locale.forLanguageTag(normalizeLanguageTag(languageTag)))
    }

    private fun normalizeLanguageTag(languageTag: String?): String {
        val candidate = languageTag?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return DEFAULT_LANGUAGE_TAG
        }

        return SUPPORTED_LANGUAGE_TAGS.firstOrNull { supported ->
            candidate.equals(supported, ignoreCase = true) ||
                candidate.startsWith("$supported-", ignoreCase = true) ||
                candidate.startsWith("${supported}_", ignoreCase = true)
        } ?: DEFAULT_LANGUAGE_TAG
    }
}
