package com.offline.translator.feature.text

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.offline.translator.domain.translation.*
import java.text.DateFormat
import java.util.Date

private enum class Screen { Text, Phrasebook, History, Models }

@Composable
fun TextTranslatorScreen(
    state: TextTranslatorState,
    phrases: List<Phrase>,
    onAction: (TextTranslatorAction) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onInsert: ((String) -> Unit)? = null,
    onSpeak: (String, Language) -> Unit = { _, _ -> },
    settings: TranslatorSettings = TranslatorSettings(),
    incomingTextRevision: Int = 0,
    onSettingsChange: (TranslatorSettings) -> Unit = {},
    onVoiceSettings: () -> Unit = {},
    cloudCheck: CloudCheckState = CloudCheckState(),
    onCheckCloud: (CloudConfiguration) -> Unit = {},
    toolsContent: @Composable () -> Unit = {},
    speaking: Boolean = false,
    onStopSpeech: () -> Unit = {},
    modelsContent: @Composable () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Text) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var startAtCloud by rememberSaveable { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    var handledIncomingRevision by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(incomingTextRevision) {
        if (incomingTextRevision > handledIncomingRevision) {
            screen = Screen.Text
            settingsOpen = false
            handledIncomingRevision = incomingTextRevision
        }
    }
    BackHandler(screen != Screen.Text) { screen = Screen.Text }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Screen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { focus.clearFocus(); keyboard?.hide(); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); screen = item },
                        icon = { LinguaIcon(when (item) { Screen.Text -> LinguaSymbol.Translate; Screen.Phrasebook -> LinguaSymbol.Book; Screen.History -> LinguaSymbol.History; Screen.Models -> LinguaSymbol.Download }) },
                        label = { Text(stringResource(screenTitle(item))) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(com.offline.translator.core.designsystem.R.drawable.lingua_mark), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(32.dp)) }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(stringResource(R.string.product_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.product_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val settingsDescription = stringResource(R.string.settings)
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); startAtCloud = false; settingsOpen = true }, modifier = Modifier.testTag("open_settings").semantics { contentDescription = settingsDescription }) {
                    LinguaIcon(LinguaSymbol.Settings)
                }
            }
            AnimatedVisibility(speaking) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    FilledTonalButton(onClick = onStopSpeech, modifier = Modifier.testTag("stop_speech")) { Text(stringResource(R.string.stop_speech)) }
                    if (settings.cloud.enabled) Text(stringResource(R.string.synthetic_voice_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedContent(
                targetState = screen,
                transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 20 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { -it / 20 }) },
                label = "destination",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { destination ->
                screenStates.SaveableStateProvider(destination.name) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        when (destination) {
                            Screen.Text -> Editor(state, onAction, onCopy, onShare, onInsert, onSpeak, incomingTextRevision, settings.cloud.enabled, toolsContent, onConfigureCloud = { startAtCloud = true; settingsOpen = true }, onModels = { screen = Screen.Models })
                            Screen.Phrasebook -> PhrasebookView(phrases, state, onSpeak) { phrase ->
                                onAction(TextTranslatorAction.SelectSource(state.source ?: Language.English))
                                onAction(TextTranslatorAction.EditText(phrase.text(state.source ?: Language.English)))
                                screen = Screen.Text
                            }
                            Screen.History -> HistoryView(state, onAction, onSpeak, onCopy) { entry ->
                                onAction(TextTranslatorAction.Restore(entry)); screen = Screen.Text
                            }
                            Screen.Models -> LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp)) { item { modelsContent() } }
                        }
                    }
                }
            }
        }
    }
    if (settingsOpen) SettingsScreen(
        settings = settings,
        onUpdate = onSettingsChange,
        onVoiceSettings = onVoiceSettings,
        cloudCheck = cloudCheck,
        startAtCloud = startAtCloud,
        onCheckCloud = onCheckCloud,
        onClose = { settingsOpen = false },
    )
}

private fun screenTitle(screen: Screen) = when (screen) {
    Screen.Text -> R.string.tab_text
    Screen.Phrasebook -> R.string.tab_phrasebook
    Screen.History -> R.string.tab_history
    Screen.Models -> R.string.tab_models
}

@Composable
private fun Editor(
    state: TextTranslatorState,
    onAction: (TextTranslatorAction) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onInsert: ((String) -> Unit)?,
    onSpeak: (String, Language) -> Unit,
    incomingTextRevision: Int,
    cloudEnabled: Boolean,
    toolsContent: @Composable () -> Unit,
    onConfigureCloud: () -> Unit,
    onModels: () -> Unit,
) {
    val listState = rememberLazyListState()
    var handledScrollRevision by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(incomingTextRevision) {
        if (incomingTextRevision > handledScrollRevision) {
            listState.scrollToItem(0)
            handledScrollRevision = incomingTextRevision
        }
    }
    val result = (state.outcome as? TranslationOutcome.Success)?.result
    var rotation by remember { mutableFloatStateOf(0f) }
    val angle by animateFloatAsState(rotation, spring(stiffness = 250f), label = "language swap")
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.editor_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { toolsContent() }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LanguageSelector(state.source, true, Modifier.weight(1f)) { onAction(TextTranslatorAction.SelectSource(it)) }
                    val description = stringResource(R.string.swap_languages)
                    FilledIconButton(
                        onClick = { rotation += 180f; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAction(TextTranslatorAction.SwapLanguages) },
                        enabled = state.source != null || result != null,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
                    ) { LinguaIcon(LinguaSymbol.Translate, Modifier.rotate(angle)) }
                    LanguageSelector(state.target, false, Modifier.weight(1f)) { if (it != null) onAction(TextTranslatorAction.SelectTarget(it)) }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.source_text), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onAction(TextTranslatorAction.EditText("")) }, enabled = state.text.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                    }
                    TextField(
                        value = state.text,
                        onValueChange = { onAction(TextTranslatorAction.EditText(it)) },
                        modifier = Modifier.fillMaxWidth().testTag("translation_input"),
                        minLines = 3,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        placeholder = { Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.headlineSmall) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Text(stringResource(R.string.auto_translate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        item {
            val background by animateColorAsState(if (result != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, label = "result color")
            Surface(shape = RoundedCornerShape(28.dp), color = background, modifier = Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.background(Brush.linearGradient(listOf(background, background.copy(alpha = 0.65f)))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(stringResource(R.string.translation), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    AnimatedContent(targetState = Triple(state.translating, state.failed, state.outcome), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "translation") { (translating, failed, outcome) ->
                        val translated = (outcome as? TranslationOutcome.Success)?.result
                        when {
                            translating -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.translating))
                            }
                            failed -> Column {
                                Text(stringResource(R.string.translation_failed))
                                TextButton(onClick = { onAction(TextTranslatorAction.Retry) }) { Text(stringResource(R.string.retry)) }
                            }
                            translated != null -> SelectionContainer {
                                Text(translated.translated, Modifier.testTag("translation_result"), style = MaterialTheme.typography.headlineLarge)
                            }
                            else -> Text(stringResource(when (outcome) {
                                TranslationOutcome.ModelRequired -> R.string.model_required
                                TranslationOutcome.LanguageUndetected -> R.string.language_undetected
                                TranslationOutcome.OnlineUnavailable -> R.string.online_unavailable
                                is TranslationOutcome.CloudFailure -> cloudFailureMessage(outcome.reason)
                                else -> R.string.result_hint
                            }), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (result != null) {
                        Text(stringResource(when (result.origin) {
                            TranslationOrigin.Phrasebook -> R.string.origin_phrasebook
                            TranslationOrigin.Identity -> R.string.origin_identity
                            TranslationOrigin.OfflineModel -> R.string.origin_offline
                            TranslationOrigin.Online -> R.string.origin_online
                        }), style = MaterialTheme.typography.labelSmall)
                        val entry = state.history.find { it.result == result }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ActionChip(R.string.listen) { onSpeak(result.translated, result.target) }
                            ActionChip(if (entry?.favorite == true) R.string.saved else R.string.save, enabled = entry != null) { entry?.let { onAction(TextTranslatorAction.ToggleFavorite(it.id)) } }
                        }
                        ResultActions(result.translated, onCopy, onShare, onInsert)
                    }
                    if (state.outcome is TranslationOutcome.CloudFailure) TextButton(onClick = onConfigureCloud) { Text(stringResource(R.string.cloud_settings)) }
                    if (state.outcome == TranslationOutcome.ModelRequired) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onModels) { Text(stringResource(R.string.tab_models)) }
                            FilledTonalButton(onClick = onConfigureCloud) { Text(stringResource(R.string.cloud_settings)) }
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(if (cloudEnabled) R.string.cloud_badge else R.string.offline_badge), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (cloudEnabled) R.string.cloud_mode_note else R.string.offline_mode_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.historyFailed) item { Text(stringResource(R.string.history_failed), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ActionChip(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(stringResource(label)) })
}

@Composable
private fun PhrasebookView(phrases: List<Phrase>, state: TextTranslatorState, onSpeak: (String, Language) -> Unit, onUse: (Phrase) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<PhraseCategory?>(null) }
    val filtered = phrases.filter { (category == null || it.category == category) && it.texts.values.any { text -> text.contains(query, ignoreCase = true) } }
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading(R.string.tab_phrasebook, R.string.phrasebook_intro) }
        item { SearchField(query) { query = it } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.all)) })
                PhraseCategory.entries.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(stringResource(categoryTitle(value))) }) }
            }
        }
        if (filtered.isEmpty()) item { EmptyState(R.string.no_results, R.string.search_hint) }
        items(filtered, key = { it.id }) { phrase ->
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(categoryTitle(phrase.category)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Language.entries.forEach { language ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(language.code.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                            Text(phrase.text(language), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onSpeak(phrase.text(state.target), state.target) }) { Text(stringResource(R.string.listen)) }
                        FilledTonalButton(onClick = { onUse(phrase) }) { Text(stringResource(R.string.use_phrase)) }
                    }
                }
            }
        }
    }
}

private fun categoryTitle(category: PhraseCategory) = when (category) {
    PhraseCategory.Essentials -> R.string.category_essentials
    PhraseCategory.Travel -> R.string.category_travel
    PhraseCategory.Restaurant -> R.string.category_restaurant
    PhraseCategory.Health -> R.string.category_health
}

@Composable
private fun HistoryView(state: TextTranslatorState, onAction: (TextTranslatorAction) -> Unit, onSpeak: (String, Language) -> Unit, onCopy: (String) -> Unit, onRestore: (HistoryEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var learning by rememberSaveable { mutableStateOf(false) }
    val filtered = state.history.filter { (!favorites || it.favorite) && (it.result.original.contains(query, true) || it.result.translated.contains(query, true)) }
    LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading(R.string.tab_history, R.string.history_subtitle) }
        item { SearchField(query) { query = it } }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = !favorites, onClick = { favorites = false }, label = { Text(stringResource(R.string.all)) })
                FilterChip(selected = favorites, onClick = { favorites = true }, label = { Text(stringResource(R.string.favorites)) })
                ActionChip(R.string.learn, state.history.any { it.favorite }) { learning = true }
                ActionChip(R.string.clear_history, state.history.isNotEmpty() || state.historyFailed) { confirmClear = true }
            }
        }
        if (state.historyFailed) item { Text(stringResource(R.string.history_failed), color = MaterialTheme.colorScheme.error) }
        if (filtered.isEmpty()) item { EmptyState(if (query.isBlank()) R.string.history_empty else R.string.no_results, R.string.history_empty_hint) }
        items(filtered, key = { it.id }) { entry ->
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.history_direction, entry.result.source.code.uppercase(), entry.result.target.code.uppercase(), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.result.original, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.result.translated, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ActionChip(if (entry.favorite) R.string.saved else R.string.save) { onAction(TextTranslatorAction.ToggleFavorite(entry.id)) }
                        ActionChip(R.string.listen) { onSpeak(entry.result.translated, entry.result.target) }
                        ActionChip(R.string.copy) { onCopy(entry.result.translated) }
                        ActionChip(R.string.open_translation) { onRestore(entry) }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.clear_history)) }, text = { Text(stringResource(R.string.clear_history_confirm)) }, confirmButton = { TextButton(onClick = { onAction(TextTranslatorAction.ClearHistory); confirmClear = false }) { Text(stringResource(R.string.clear)) } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.close)) } })
    if (learning) LearningDialog(state.history.filter { it.favorite }, onSpeak) { learning = false }
}

@Composable
private fun LearningDialog(entries: List<HistoryEntry>, onSpeak: (String, Language) -> Unit, onClose: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val entry = entries.getOrNull(index)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.learn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (entry == null) Text(stringResource(R.string.learning_done)) else {
                    Text(stringResource(R.string.learning_progress, index + 1, entries.size), style = MaterialTheme.typography.labelLarge)
                    Text(entry.result.original, style = MaterialTheme.typography.headlineSmall)
                    AnimatedVisibility(revealed) { Text(entry.result.translated, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = { onSpeak(entry.result.translated, entry.result.target) }) { Text(stringResource(R.string.listen)) }
                }
            }
        },
        confirmButton = {
            if (entry != null) TextButton(onClick = { if (revealed) { index++; revealed = false } else revealed = true }) { Text(stringResource(if (revealed) R.string.next_card else R.string.reveal)) }
            else TextButton(onClick = { index = 0; revealed = false }) { Text(stringResource(R.string.repeat_cards)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun PageHeading(title: Int, subtitle: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(title: Int, hint: Int) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(query, onChange, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.search_hint)) }, label = { Text(stringResource(R.string.search)) }, singleLine = true, shape = RoundedCornerShape(20.dp))
}

@Composable
private fun LanguageSelector(language: Language?, allowAuto: Boolean, modifier: Modifier, onSelect: (Language?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(language?.code?.uppercase() ?: stringResource(R.string.auto_label), style = MaterialTheme.typography.labelSmall)
            Text(languageName(language), style = MaterialTheme.typography.titleSmall)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text(stringResource(R.string.select_language)) },
        text = {
            Column {
                if (allowAuto) TextButton(onClick = { onSelect(null); open = false }, modifier = Modifier.fillMaxWidth()) { Text(languageName(null)) }
                Language.entries.forEach { item -> TextButton(onClick = { onSelect(item); open = false }, modifier = Modifier.fillMaxWidth()) { Text(languageName(item)) } }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun languageName(language: Language?): String = stringResource(when (language) {
    Language.Armenian -> R.string.language_armenian
    Language.Russian -> R.string.language_russian
    Language.English -> R.string.language_english
    null -> R.string.language_auto
})

@Composable
private fun ResultActions(text: String, onCopy: (String) -> Unit, onShare: (String) -> Unit, onInsert: ((String) -> Unit)?) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionChip(R.string.copy) { onCopy(text) }
        ActionChip(R.string.share) { onShare(text) }
        ActionChip(R.string.large_text) { expanded = true }
        if (onInsert != null) ActionChip(R.string.insert_translation) { onInsert(text) }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) }
                LazyColumn(Modifier.weight(1f)) { item { SelectionContainer { Text(text, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(vertical = 32.dp)) } } }
            }
        }
    }
}
