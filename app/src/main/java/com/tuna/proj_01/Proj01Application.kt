package com.tuna.proj_01

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class Proj01Application : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppThemeManager.getThemeMode(this))
    }
}
