package com.offline.translator.feature.text

import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.CloudConfiguration

enum class AppTheme { System, Light, Dark, Amoled }

data class TranslatorSettings(
    val theme: AppTheme = AppTheme.System,
    val interfaceLanguage: Language? = null,
    val dynamicColors: Boolean = false,
    val speechRate: Float = 1f,
    val cloud: CloudConfiguration = CloudConfiguration(),
) {
    fun normalized(): TranslatorSettings = copy(speechRate = if (speechRate.isFinite()) speechRate.coerceIn(0.5f, 1.5f) else 1f)
}
