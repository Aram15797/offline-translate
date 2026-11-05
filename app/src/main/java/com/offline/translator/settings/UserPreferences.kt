package com.offline.translator.settings

import android.content.Context
import androidx.core.content.edit
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.feature.text.AppTheme
import com.offline.translator.feature.text.TranslatorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val cipher = TokenCipher()
    private val mutableSettings = MutableStateFlow(read())
    val settings = mutableSettings.asStateFlow()

    @Synchronized
    fun update(settings: TranslatorSettings) {
        val normalized = settings.normalized()
        val encryptedToken = if (normalized.cloud.accessToken == mutableSettings.value.cloud.accessToken) {
            preferences.getString("cloud_token", "") ?: ""
        } else cipher.encrypt(normalized.cloud.accessToken)
        preferences.edit {
            putString("cloud_endpoint", normalized.cloud.endpoint)
            putString("cloud_token", encryptedToken)
            putBoolean("cloud_offline_only", normalized.cloud.offlineOnly)
            putBoolean("cloud_consent", normalized.cloud.consent)
            putString("theme", normalized.theme.name)
            putString("interface_language", normalized.interfaceLanguage?.code)
            putBoolean("dynamic_colors", normalized.dynamicColors)
            putFloat("speech_rate", normalized.speechRate)
            remove("dark")
        }
        mutableSettings.value = normalized
    }

    private fun read(): TranslatorSettings {
        val legacyTheme = if (preferences.contains("dark")) {
            if (preferences.getBoolean("dark", false)) AppTheme.Dark else AppTheme.Light
        } else AppTheme.System
        return TranslatorSettings(
            theme = AppTheme.entries.find { it.name == preferences.getString("theme", null) } ?: legacyTheme,
            interfaceLanguage = Language.entries.find { it.code == preferences.getString("interface_language", null) },
            dynamicColors = preferences.getBoolean("dynamic_colors", false),
            speechRate = preferences.getFloat("speech_rate", 1f),
            cloud = CloudConfiguration(
                endpoint = preferences.getString("cloud_endpoint", "") ?: "",
                accessToken = runCatching { cipher.decrypt(preferences.getString("cloud_token", "") ?: "") }.getOrDefault(""),
                offlineOnly = preferences.getBoolean("cloud_offline_only", true),
                consent = preferences.getBoolean("cloud_consent", false),
            ),
        ).normalized()
    }
}
