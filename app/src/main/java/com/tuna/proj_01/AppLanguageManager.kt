package com.tuna.proj_01

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {
    private const val PREF_NAME = "app_language_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"
    private const val DEFAULT_LANGUAGE_TAG = "ko"

    private val supportedTags = arrayOf("ko", "ja", "en")

    fun getSupportedLanguageTags(): Array<String> = supportedTags.copyOf()

    fun getSelectedLanguageTag(context: Context): String {
        val stored = prefs(context).getString(KEY_LANGUAGE_TAG, null)
        if (!stored.isNullOrBlank()) return normalizeTag(stored)

        val current = currentAppliedTag(context)
        return if (current.isBlank()) DEFAULT_LANGUAGE_TAG else normalizeTag(current)
    }

    fun setLanguage(context: Context, languageTag: String, applyNow: Boolean = true): Boolean {
        val normalized = normalizeTag(languageTag)
        val current = getSelectedLanguageTag(context)
        if (current == normalized) return false

        prefs(context).edit().putString(KEY_LANGUAGE_TAG, normalized).commit()
        if (applyNow) {
            applyLanguage(context.applicationContext, normalized)
        }
        return true
    }

    fun applyPersistedLanguage(context: Context) {
        applyLanguage(context.applicationContext, getSelectedLanguageTag(context))
    }

    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base

        val languageTag = getSelectedLanguageTag(base)
        return createLocalizedContext(base, languageTag, setAsDefault = true)
    }

    fun createLocalizedContext(base: Context, languageTag: String): Context {
        return createLocalizedContext(base, languageTag, setAsDefault = false)
    }

    private fun applyLanguage(context: Context, languageTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                val localeList = LocaleList.forLanguageTags(languageTag)
                if (localeManager.applicationLocales.toLanguageTags() != localeList.toLanguageTags()) {
                    localeManager.applicationLocales = localeList
                }
            }
        } else {
            val compatLocales = LocaleListCompat.forLanguageTags(languageTag)
            if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != compatLocales.toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(compatLocales)
            }
        }
    }

    private fun currentAppliedTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val managerTag = localeManager?.applicationLocales?.toLanguageTags().orEmpty()
            if (managerTag.isNotBlank()) return managerTag
        } else {
            val compatTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (compatTag.isNotBlank()) return compatTag
        }

        return Locale.getDefault().toLanguageTag()
    }

    private fun normalizeTag(tag: String): String {
        val lowered = tag.lowercase(Locale.ROOT)
        return supportedTags.firstOrNull { lowered.startsWith(it) } ?: DEFAULT_LANGUAGE_TAG
    }

    @VisibleForTesting
    internal fun createLocalizedContext(base: Context, languageTag: String, setAsDefault: Boolean): Context {
        val normalized = normalizeTag(languageTag)
        val locale = Locale.forLanguageTag(normalized)
        if (setAsDefault) {
            Locale.setDefault(locale)
        }

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
