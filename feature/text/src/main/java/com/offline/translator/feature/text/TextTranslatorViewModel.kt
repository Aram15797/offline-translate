package com.offline.translator.feature.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import com.offline.translator.domain.translation.TranslationHistory
import com.offline.translator.domain.translation.HistoryEntry
import com.offline.translator.domain.translation.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TextTranslatorState(
    val text: String = "",
    val source: Language? = null,
    val target: Language = Language.Armenian,
    val translating: Boolean = false,
    val outcome: TranslationOutcome? = null,
    val failed: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val historyFailed: Boolean = false,
)

sealed interface TextTranslatorAction {
    data class EditText(val text: String) : TextTranslatorAction
    data class SelectSource(val language: Language?) : TextTranslatorAction
    data class SelectTarget(val language: Language) : TextTranslatorAction
    data object SwapLanguages : TextTranslatorAction
    data object Retry : TextTranslatorAction
    data object ConfigurationChanged : TextTranslatorAction
    data class Restore(val entry: HistoryEntry) : TextTranslatorAction
    data class ToggleFavorite(val id: String) : TextTranslatorAction
    data object ClearHistory : TextTranslatorAction
}

class TextTranslatorViewModel(
    private val translator: Translator,
    private val savedState: SavedStateHandle,
    private val history: TranslationHistory? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        TextTranslatorState(
            text = savedState["text"] ?: "",
            source = savedState.get<String>("source")?.let { code -> Language.entries.find { it.code == code } },
            target = Language.entries.find { it.code == savedState.get<String>("target") } ?: Language.Armenian,
        )
    )
    val state = mutableState.asStateFlow()
    private var translationJob: Job? = null
    private var revision = 0L

    init {
        scheduleTranslation()
        if (history != null) viewModelScope.launch {
            history.entries.collect { entries -> mutableState.value = mutableState.value.copy(history = entries) }
        }
    }

    fun cancelTranslation() {
        translationJob?.cancel()
        revision++
        mutableState.value = mutableState.value.copy(translating = false, outcome = null)
    }

    fun reportHistoryFailure() { mutableState.value = mutableState.value.copy(historyFailed = true) }

    fun accept(action: TextTranslatorAction) {
        if (action is TextTranslatorAction.ToggleFavorite || action == TextTranslatorAction.ClearHistory) {
            viewModelScope.launch {
                try {
                    if (action is TextTranslatorAction.ToggleFavorite) history?.toggleFavorite(action.id)
                    else history?.clear()
                    mutableState.value = mutableState.value.copy(historyFailed = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.value = mutableState.value.copy(historyFailed = true) }
            }
            return
        }
        val current = mutableState.value
        mutableState.value = when (action) {
            is TextTranslatorAction.EditText -> current.copy(text = action.text)
            is TextTranslatorAction.SelectSource -> current.copy(source = action.language)
            is TextTranslatorAction.SelectTarget -> current.copy(target = action.language)
            TextTranslatorAction.SwapLanguages -> {
                val result = (current.outcome as? TranslationOutcome.Success)?.result
                val newTarget = current.source ?: result?.source ?: return
                current.copy(
                    source = current.target,
                    target = newTarget,
                    text = result?.translated ?: current.text,
                )
            }
            TextTranslatorAction.Retry, TextTranslatorAction.ConfigurationChanged -> current
            is TextTranslatorAction.Restore -> current.copy(text = action.entry.result.original, source = action.entry.result.source, target = action.entry.result.target)
            is TextTranslatorAction.ToggleFavorite, TextTranslatorAction.ClearHistory -> current
        }
        savedState["text"] = mutableState.value.text
        savedState["source"] = mutableState.value.source?.code
        savedState["target"] = mutableState.value.target.code
        scheduleTranslation()
    }

    private fun scheduleTranslation() {
        translationJob?.cancel()
        val currentRevision = ++revision
        val input = mutableState.value
        mutableState.value = input.copy(outcome = null, translating = input.text.isNotBlank(), failed = false)
        if (input.text.isBlank()) return
        translationJob = viewModelScope.launch {
            delay(300)
            try {
                val outcome = translator.translate(TranslationRequest(input.text, input.source, input.target))
                if (currentRevision == revision) {
                    mutableState.value = mutableState.value.copy(outcome = outcome, translating = false)
                    if (outcome is TranslationOutcome.Success) {
                        try {
                            history?.record(outcome.result)
                            mutableState.value = mutableState.value.copy(historyFailed = false)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { mutableState.value = mutableState.value.copy(historyFailed = true) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentRevision == revision) {
                    mutableState.value = mutableState.value.copy(translating = false, failed = true)
                }
            }
        }
    }
}
