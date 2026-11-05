package com.offline.translator

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.CloudConfiguration
import org.junit.Assert.assertFalse
import com.offline.translator.feature.text.AppTheme
import com.offline.translator.feature.text.TranslatorSettings
import com.offline.translator.settings.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesTest {
    @Test
    fun migratesLegacyThemeAndPersistsLanguageVoiceAndPalette() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("test-$name", mode)
        }
        val storage = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        storage.edit(commit = true) { clear(); putBoolean("dark", true) }
        try {
            val preferences = UserPreferences(context)
            assertEquals(AppTheme.Dark, preferences.settings.value.theme)
            val updated = TranslatorSettings(AppTheme.Amoled, Language.Russian, true, 0.75f, CloudConfiguration("https://example.org", "sample-service-secret", false, true))
            preferences.update(updated)
            assertEquals(updated, UserPreferences(context).settings.value)
            assertFalse(storage.getString("cloud_token", "").orEmpty().contains("sample-service-secret"))
            assertFalse(updated.toString().contains("sample-service-secret"))
            preferences.update(updated.copy(interfaceLanguage = null, speechRate = 10f))
            val restored = UserPreferences(context).settings.value
            assertEquals(null, restored.interfaceLanguage)
            assertEquals(1.5f, restored.speechRate)
        } finally { storage.edit(commit = true) { clear() } }
    }
}
