package com.offline.translator

import androidx.test.platform.app.InstrumentationRegistry
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationResult
import com.offline.translator.history.EncryptedTranslationHistory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedHistoryTest {
    @Test
    fun encryptedHistorySurvivesReloadDeduplicatesAndClearsFavorites() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = EncryptedTranslationHistory(context)
        history.clear()
        try {
            val result = TranslationResult("Hello", "Բարև", Language.English, Language.Armenian, TranslationOrigin.Phrasebook)
            history.record(result)
            history.toggleFavorite(history.entries.value.single().id)
            history.record(result)
            assertEquals(1, history.entries.value.size)
            assertTrue(history.entries.value.single().favorite)
            val bytes = File(context.noBackupFilesDir, "translations.enc").readBytes()
            assertFalse(String(bytes, Charsets.UTF_8).contains("Hello"))
            val restored = EncryptedTranslationHistory(context)
            restored.load()
            assertEquals(history.entries.value, restored.entries.value)
            restored.clear()
            val empty = EncryptedTranslationHistory(context)
            empty.load()
            assertTrue(empty.entries.value.isEmpty())
        } finally { history.clear() }
    }
}
