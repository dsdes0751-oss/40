package com.tuna.proj_01

import android.app.Application

class Proj01Application : Application() {
    override fun onCreate() {
        super.onCreate()
        AppThemeManager.applyPersistedTheme(this)
        AppLanguageManager.applyPersistedLanguage(this)
    }
}
