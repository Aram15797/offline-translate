package com.offline.translator.feature.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorSettingsTest {
    @Test
    fun invalidAndOutOfRangeSpeechRatesAreNormalized() {
        assertEquals(1f, TranslatorSettings(speechRate = Float.NaN).normalized().speechRate)
        assertEquals(1f, TranslatorSettings(speechRate = Float.POSITIVE_INFINITY).normalized().speechRate)
        assertEquals(0.5f, TranslatorSettings(speechRate = 0f).normalized().speechRate)
        assertEquals(1.5f, TranslatorSettings(speechRate = 3f).normalized().speechRate)
        assertEquals(0.75f, TranslatorSettings(speechRate = 0.75f).normalized().speechRate)
    }
}
