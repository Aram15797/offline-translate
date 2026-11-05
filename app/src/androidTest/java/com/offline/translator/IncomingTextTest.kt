package com.offline.translator

import android.content.Intent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.offline.translator.feature.text.TranslatorSettings
import org.junit.Rule
import org.junit.Test

class IncomingTextTest {
    @get:Rule
    val compose = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(
        activityRule = ActivityScenarioRule<MainActivity>(shareIntent("Hello")),
        activityProvider = { rule -> activityFrom(rule.scenario) },
    )

    @Test
    fun incomingShareOpensEditorAndDoesNotReplayAfterRecreation() {
        compose.runOnIdle { (compose.activity.application as LinguaApplication).preferences.update(TranslatorSettings()) }
        compose.onNodeWithTag("translation_input").assertTextEquals("Hello")
        val history = compose.activity.getString(com.offline.translator.feature.text.R.string.tab_history)
        compose.onNodeWithText(history).performClick()
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(shareIntent("Thank you").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("translation_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("translation_input").assertTextEquals("Thank you")
        compose.onNodeWithText(history).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("translation_input").assertDoesNotExist()
    }

    private fun shareIntent(text: String) = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun activityFrom(scenario: ActivityScenario<MainActivity>): MainActivity {
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        return activity
    }
}
