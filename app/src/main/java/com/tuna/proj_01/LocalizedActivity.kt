package com.tuna.proj_01

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

abstract class LocalizedActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeManager.applyPersistedTheme(applicationContext)
        AppLanguageManager.applyPersistedLanguage(applicationContext)
        super.onCreate(savedInstanceState)
    }
}
