package com.tuna.proj_01

import android.content.Context

object TranslationWorkState {

    private const val PREF_NAME = "translation_work_state"
    private const val KEY_MASS_RUNNING = "mass_running"
    private const val KEY_NOVEL_RUNNING = "novel_running"

    fun isMassRunning(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MASS_RUNNING, false)
    }

    fun isNovelRunning(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NOVEL_RUNNING, false)
    }

    fun isAnyTranslationRunning(context: Context): Boolean {
        return isMassRunning(context) || isNovelRunning(context)
    }

    fun runningTaskName(context: Context): String? {
        return when {
            isMassRunning(context) -> "대량번역"
            isNovelRunning(context) -> "소설번역"
            else -> null
        }
    }

    fun setMassRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASS_RUNNING, running).apply()
    }

    fun setNovelRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOVEL_RUNNING, running).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}

