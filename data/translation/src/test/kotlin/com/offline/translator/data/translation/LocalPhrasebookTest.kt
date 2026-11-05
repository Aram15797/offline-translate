package com.offline.translator.data.translation

import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPhrasebookTest {
    private val translator = LocalPhrasebook()

    @Test
    fun everyPhraseWorksInAllSixDirectionsWithAutoDetection() = runTest {
        translator.phrases.forEach { phrase ->
            Language.entries.forEach { source ->
                Language.entries.filter { it != source }.forEach { target ->
                    val outcome = translator.translate(TranslationRequest(phrase.text(source), null, target))
                    val result = (outcome as TranslationOutcome.Success).result
                    assertEquals(phrase.text(target), result.translated)
                    assertEquals(source, result.source)
                    assertEquals(TranslationOrigin.Phrasebook, result.origin)
                }
            }
        }
    }

    @Test
    fun matchingIgnoresCaseWhitespaceAndPunctuation() = runTest {
        val outcome = translator.translate(TranslationRequest("  THANK   YOU! ", null, Language.Russian))
        assertEquals("Спасибо", (outcome as TranslationOutcome.Success).result.translated)
    }

    @Test
    fun unknownTextRequiresModel() = runTest {
        assertEquals(TranslationOutcome.ModelRequired,
            translator.translate(TranslationRequest("This is an arbitrary sentence", null, Language.Armenian)))
    }

    @Test
    fun mixedScriptsAndNumbersRequireManualLanguage() = runTest {
        listOf("Hello Привет", "12345", "😀").forEach { text ->
            assertEquals(TranslationOutcome.LanguageUndetected,
                translator.translate(TranslationRequest(text, null, Language.Armenian)))
        }
    }

    @Test
    fun sameLanguageReturnsOriginalWithoutModel() = runTest {
        val outcome = translator.translate(TranslationRequest("Բարև աշխարհ", Language.Armenian, Language.Armenian))
        assertEquals("Բարև աշխարհ", (outcome as TranslationOutcome.Success).result.translated)
        assertEquals(TranslationOrigin.Identity, outcome.result.origin)
    }

    @Test
    fun whitespaceOnlyIsEmpty() = runTest {
        assertEquals(TranslationOutcome.EmptyInput,
            translator.translate(TranslationRequest(" \n ", null, Language.English)))
    }
}
