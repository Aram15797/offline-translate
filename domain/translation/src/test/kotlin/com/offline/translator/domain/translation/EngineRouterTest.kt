package com.offline.translator.domain.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineRouterTest {
    private val request = TranslationRequest("hello", Language.English, Language.Armenian)

    @Test
    fun offlineOnlyNeverCallsCloudEvenWithConsentAndNetwork() = runTest {
        var calls = 0
        val router = EngineRouter(
            stub(TranslationOutcome.ModelRequired),
            object : Translator {
                override suspend fun translate(request: TranslationRequest): TranslationOutcome {
                    calls++
                    return TranslationOutcome.OnlineUnavailable
                }
            },
            { TranslationPolicy(offlineOnly = true, cloudConsent = true) },
            { true },
        )
        assertEquals(TranslationOutcome.ModelRequired, router.translate(request))
        assertEquals(0, calls)
    }

    @Test
    fun cloudRequiresExplicitConsent() = runTest {
        val router = EngineRouter(
            stub(TranslationOutcome.ModelRequired),
            stub(TranslationOutcome.OnlineUnavailable),
            { TranslationPolicy(offlineOnly = false) },
            { true },
        )
        assertEquals(TranslationOutcome.ModelRequired, router.translate(request))
    }

    @Test
    fun localSuccessTakesPriorityOverCloud() = runTest {
        val local = success(TranslationOrigin.Phrasebook)
        val router = EngineRouter(stub(local), stub(success(TranslationOrigin.Online)),
            { TranslationPolicy(false, true) }, { true })
        assertEquals(local, router.translate(request))
    }

    @Test
    fun consentingUserCanFallbackToCloud() = runTest {
        val online = success(TranslationOrigin.Online)
        val router = EngineRouter(stub(TranslationOutcome.ModelRequired), stub(online),
            { TranslationPolicy(false, true) }, { true })
        assertEquals(online, router.translate(request))
    }

    @Test
    fun missingNetworkReportsUnavailable() = runTest {
        val router = EngineRouter(stub(TranslationOutcome.ModelRequired), stub(success(TranslationOrigin.Online)),
            { TranslationPolicy(false, true) }, { false })
        assertEquals(TranslationOutcome.OnlineUnavailable, router.translate(request))
    }

    @Test
    fun consentingUserCanUseCloudDetectionWhenLocalScriptDetectionFails() = runTest {
        val router = EngineRouter(stub(TranslationOutcome.LanguageUndetected), stub(success(TranslationOrigin.Online)),
            { TranslationPolicy(false, true) }, { true })
        assertEquals(success(TranslationOrigin.Online), router.translate(request))
    }

    @Test
    fun unknownScriptDoesNotLeaveDeviceWithoutConsent() = runTest {
        val local = TranslationOutcome.LanguageUndetected
        val router = EngineRouter(stub(local), object : Translator {
            override suspend fun translate(request: TranslationRequest): TranslationOutcome = error("Unexpected cloud request")
        }, { TranslationPolicy(false, false) }, { true })
        assertEquals(local, router.translate(request))
    }

    private fun success(origin: TranslationOrigin) = TranslationOutcome.Success(
        TranslationResult("hello", "Բարև", Language.English, Language.Armenian, origin)
    )

    private fun stub(outcome: TranslationOutcome) = object : Translator {
        override suspend fun translate(request: TranslationRequest) = outcome
    }
}
