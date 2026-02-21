package com.tuna.proj_01

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

abstract class LocalizedActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val tag = AppLanguageManager.getSelectedLanguageTag(newBase)
        val localizedContext = AppLanguageManager.createLocalizedContext(newBase, tag)
        super.attachBaseContext(localizedContext)
    }
}
