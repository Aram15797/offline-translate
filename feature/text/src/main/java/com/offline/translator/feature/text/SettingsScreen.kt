package com.offline.translator.feature.text

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.CloudConfiguration

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsScreen(
    settings: TranslatorSettings,
    onUpdate: (TranslatorSettings) -> Unit,
    onVoiceSettings: () -> Unit,
    cloudCheck: CloudCheckState,
    startAtCloud: Boolean,
    onCheckCloud: (CloudConfiguration) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (startAtCloud) 3 else 0)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("settings_screen"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                ) {
                    stickyHeader {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                            TextButton(onClick = onClose, modifier = Modifier.testTag("settings_close")) { Text(stringResource(R.string.close)) }
                        }
                    }
                    item {
                        SettingsCard(R.string.interface_language) {
                            Text(stringResource(R.string.interface_language_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SettingsChoice(stringResource(R.string.theme_system), settings.interfaceLanguage == null, "locale_system") { onUpdate(settings.copy(interfaceLanguage = null)) }
                            Language.entries.forEach { language ->
                                SettingsChoice(languageLabel(language), settings.interfaceLanguage == language, "locale_${language.code}") { onUpdate(settings.copy(interfaceLanguage = language)) }
                            }
                        }
                    }
                    item {
                        SettingsCard(R.string.appearance) {
                            AppTheme.entries.forEach { theme ->
                                SettingsChoice(stringResource(themeLabel(theme)), settings.theme == theme, "theme_${theme.name}") { onUpdate(settings.copy(theme = theme)) }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(stringResource(R.string.dynamic_colors), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.dynamic_colors_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = settings.dynamicColors, onCheckedChange = { onUpdate(settings.copy(dynamicColors = it)) }, modifier = Modifier.testTag("dynamic_colors"))
                                }
                            }
                        }
                    }
                    item { CloudSettingsCard(settings, onUpdate, cloudCheck, onCheckCloud) }
                    item {
                        SettingsCard(R.string.speech_settings) {
                            var rate by remember(settings.speechRate) { mutableFloatStateOf(settings.speechRate) }
                            Text(stringResource(R.string.speech_rate, rate), style = MaterialTheme.typography.titleMedium)
                            Slider(
                                value = rate,
                                onValueChange = { rate = it },
                                onValueChangeFinished = { onUpdate(settings.copy(speechRate = rate)) },
                                valueRange = 0.5f..1.5f,
                                steps = 3,
                                modifier = Modifier.testTag("speech_rate"),
                            )
                            Text(stringResource(R.string.offline_voice_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = onVoiceSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.system_voice_settings)) }
                        }
                    }
                    item {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.privacy_description), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: Int, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SettingsChoice(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Surface(onClick = onSelect, modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun themeLabel(theme: AppTheme) = when (theme) {
    AppTheme.System -> R.string.theme_system
    AppTheme.Light -> R.string.theme_light
    AppTheme.Dark -> R.string.theme_dark
    AppTheme.Amoled -> R.string.theme_amoled
}

@Composable
private fun languageLabel(language: Language) = stringResource(when (language) {
    Language.Armenian -> R.string.native_armenian
    Language.Russian -> R.string.native_russian
    Language.English -> R.string.native_english
})
