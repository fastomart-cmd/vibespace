package com.vibespace.app.data.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** In-app language switch (English / Hindi) using AppCompat per-app locales (persisted automatically). */
object LanguageManager {
    const val ENGLISH = "en"
    const val HINDI = "hi"

    fun current(): String {
        val app = AppCompatDelegate.getApplicationLocales()
        val tag = if (!app.isEmpty) app[0]?.language else Locale.getDefault().language
        return if (tag == HINDI) HINDI else ENGLISH
    }

    fun set(language: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
    }
}
