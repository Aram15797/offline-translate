package com.offline.translator.feature.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.offline.translator.domain.translation.HistoryEntry
import com.offline.translator.domain.translation.TranslationHistory
import kotlinx.coroutines.flow.MutableStateFlow
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import com.offline.translator.domain.translation.TranslationResult
import com.offline.translator.domain.translation.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextTranslatorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun rapidTypingOnlyTranslatesLatestInputAfterDebounce() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest): TranslationOutcome {
                calls.add(request.text)
                return success(request)
            }
        })
        model.accept(TextTranslatorAction.EditText("H"))
        runCurrent()
        advanceTimeBy(200)
        model.accept(TextTranslatorAction.EditText("Hello"))
        runCurrent()
        advanceTimeBy(299)
        assertTrue(calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("Hello"), calls)
        assertFalse(model.state.value.translating)
    }

    @Test
    fun newInputCancelsInFlightTranslationAndClearsResult() = runTest(dispatcher) {
        var cancelled = false
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest): TranslationOutcome {
                if (request.text == "old") {
                    try { delay(5000) } catch (error: CancellationException) { cancelled = true; throw error }
                }
                return success(request)
            }
        })
        model.accept(TextTranslatorAction.EditText("old"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        model.accept(TextTranslatorAction.EditText("new"))
        assertNull(model.state.value.outcome)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(cancelled)
        assertEquals("new", (model.state.value.outcome as TranslationOutcome.Success).result.original)
    }

    @Test
    fun clearingInputCancelsWorkAndLeavesIdleState() = runTest(dispatcher) {
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest): TranslationOutcome {
                delay(1000)
                return success(request)
            }
        })
        model.accept(TextTranslatorAction.EditText("Hello"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        model.accept(TextTranslatorAction.EditText(""))
        advanceTimeBy(2000)
        assertFalse(model.state.value.translating)
        assertNull(model.state.value.outcome)
    }

    @Test
    fun swappingAutoDetectedResultUsesDetectedSourceAndTranslatedText() = runTest(dispatcher) {
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest) = success(request)
        })
        model.accept(TextTranslatorAction.EditText("Hello"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        model.accept(TextTranslatorAction.SwapLanguages)
        assertEquals(Language.Armenian, model.state.value.source)
        assertEquals(Language.English, model.state.value.target)
        assertEquals("Բարև", model.state.value.text)
    }

    @Test
    fun savedInputAndDirectionAreRestored() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf("text" to "Привет", "source" to "ru", "target" to "en"))
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest) = success(request)
        }, saved)
        assertEquals("Привет", model.state.value.text)
        assertEquals(Language.Russian, model.state.value.source)
        assertEquals(Language.English, model.state.value.target)
    }

    @Test
    fun successfulTranslationIsRecordedAndHistoryActionsDoNotRetranslate() = runTest(dispatcher) {
        var calls = 0
        val history = MemoryHistory()
        val model = TextTranslatorViewModel(object : Translator {
            override suspend fun translate(request: TranslationRequest): TranslationOutcome {
                calls++
                return success(request)
            }
        }, SavedStateHandle(), history)
        store.put("translator", model)
        model.accept(TextTranslatorAction.EditText("Hello"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, model.state.value.history.size)
        model.accept(TextTranslatorAction.ToggleFavorite("entry"))
        runCurrent()
        assertTrue(model.state.value.history.single().favorite)
        model.accept(TextTranslatorAction.ClearHistory)
        runCurrent()
        assertTrue(model.state.value.history.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun historyFailureDoesNotDiscardSuccessfulTranslation() = runTest(dispatcher) {
        val history = object : TranslationHistory {
            override val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
            override suspend fun record(result: TranslationResult) { error("storage") }
            override suspend fun toggleFavorite(id: String) = Unit
            override suspend fun clear() = Unit
        }
        val model = TextTranslatorViewModel(object : Translator {
            override suspend fun translate(request: TranslationRequest) = success(request)
        }, SavedStateHandle(), history)
        store.put("translator", model)
        model.accept(TextTranslatorAction.EditText("Hello"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(model.state.value.outcome is TranslationOutcome.Success)
        assertTrue(model.state.value.historyFailed)
        assertFalse(model.state.value.failed)
    }

    @Test
    fun restoringHistoryRestoresTextAndDirectionTogether() = runTest(dispatcher) {
        val model = model(object : Translator {
            override suspend fun translate(request: TranslationRequest) = success(request)
        })
        val result = TranslationResult("Привет", "Hello", Language.Russian, Language.English, TranslationOrigin.Phrasebook)
        model.accept(TextTranslatorAction.Restore(HistoryEntry("saved", result, 1)))
        assertEquals("Привет", model.state.value.text)
        assertEquals(Language.Russian, model.state.value.source)
        assertEquals(Language.English, model.state.value.target)
    }

    private class MemoryHistory : TranslationHistory {
        override val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
        override suspend fun record(result: TranslationResult) { entries.value = listOf(HistoryEntry("entry", result, 1)) }
        override suspend fun toggleFavorite(id: String) { entries.value = entries.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }
        override suspend fun clear() { entries.value = emptyList() }
    }

    private fun model(translator: Translator, saved: SavedStateHandle = SavedStateHandle()): TextTranslatorViewModel {
        val model = TextTranslatorViewModel(translator, saved)
        store.put("translator", model)
        return model
    }

    private fun success(request: TranslationRequest) = TranslationOutcome.Success(
        TranslationResult(request.text, "Բարև", request.source ?: Language.English, request.target, TranslationOrigin.Phrasebook)
    )
}
