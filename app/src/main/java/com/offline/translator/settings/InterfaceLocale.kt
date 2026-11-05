package com.offline.translator.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.offline.translator.domain.translation.Language
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

fun Context.withInterfaceLanguage(language: Language?): Context {
    if (language == null) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(language.code)))
    }
    return createConfigurationContext(configuration)
}

@Composable
fun InterfaceLocale(language: Language?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(context, configuration, language) { context.withInterfaceLanguage(language) }
    CompositionLocalProvider(
        LocalResources provides localized.resources,
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}
