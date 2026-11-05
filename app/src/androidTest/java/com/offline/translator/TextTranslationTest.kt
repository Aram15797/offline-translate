package com.offline.translator

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Rule
import com.offline.translator.feature.text.TranslatorSettings
import org.junit.Test

class TextTranslationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSettings() {
        runBlocking { (compose.activity.application as LinguaApplication).history.clear() }
        compose.runOnIdle { (compose.activity.application as LinguaApplication).preferences.update(TranslatorSettings()) }
    }

    @After
    fun restoreSettings() {
        compose.runOnIdle { (compose.activity.application as LinguaApplication).preferences.update(TranslatorSettings()) }
    }

    @Test
    fun switchesInterfaceLanguageWithoutLosingInputOrRestartingActivity() {
        compose.onNodeWithTag("translation_input").performTextInput("Hello")
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("locale_ru").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Язык интерфейса").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Язык интерфейса").assertTextEquals("Язык интерфейса")
        compose.onNodeWithTag("settings_close").performClick()
        compose.onNodeWithTag("translation_input").assertTextEquals("Hello")
        compose.onNodeWithText("История").assertTextEquals("История")
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("locale_hy").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Միջերեսի լեզու").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Միջերեսի լեզու").assertTextEquals("Միջերեսի լեզու")
        compose.onNodeWithTag("locale_system").performClick()
        compose.onNodeWithTag("settings_close").performClick()
        compose.onNodeWithTag("translation_input").assertTextEquals("Hello")
    }

    @Test
    fun savesTranslationAndPracticesItFromFavorites() {
        fun label(id: Int) = compose.activity.getString(id)
        compose.onNodeWithTag("translation_input").performTextInput("Hello")
        val save = label(com.offline.translator.feature.text.R.string.save)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("translation_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("translation_result").performScrollTo()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(save).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.onNodeWithText(save).performScrollTo().performClick()
        val saved = label(com.offline.translator.feature.text.R.string.saved)
        compose.waitUntil(5000) { compose.onAllNodesWithText(saved).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(com.offline.translator.feature.text.R.string.tab_history)).performClick()
        compose.onNodeWithText(label(com.offline.translator.feature.text.R.string.favorites)).performClick()
        val practice = label(com.offline.translator.feature.text.R.string.learn)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(practice).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.onNodeWithText(practice).performClick()
        compose.onNodeWithText(label(com.offline.translator.feature.text.R.string.reveal)).performClick()
        compose.onAllNodesWithText("Բարև").fetchSemanticsNodes().also { nodes ->
            org.junit.Assert.assertTrue(nodes.isNotEmpty())
        }
        compose.onNodeWithText(label(com.offline.translator.feature.text.R.string.close)).performClick()
    }

    @Test
    fun voiceCannotStartWithoutCloudConsent() {
        compose.onNodeWithTag("voice_input").performClick()
        val message = compose.activity.getString(com.offline.translator.feature.text.R.string.cloud_consent_required)
        compose.onNodeWithText(message).assertTextEquals(message)
        val recording = compose.activity.getString(com.offline.translator.feature.text.R.string.recording_note)
        compose.onNodeWithText(recording).assertDoesNotExist()
    }

    @Test
    fun modelsScreenShowsVerifiedEmptyCatalog() {
        val tab = compose.activity.getString(com.offline.translator.feature.text.R.string.tab_models)
        compose.onNodeWithText(tab).performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("models_empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("models_empty").performScrollTo()
    }

    @Test
    fun translatesLocalPhraseAndClearsStaleResultForUnknownText() {
        compose.onNodeWithTag("translation_input").performTextInput("Hello")
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("translation_result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("translation_result").assertTextEquals("Բարև")
        compose.onNodeWithTag("translation_input").performTextReplacement("An arbitrary sentence")
        val expected = compose.activity.getString(com.offline.translator.feature.text.R.string.model_required)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(expected).performScrollTo()
    }
}
