package com.offline.translator.data.cloud

import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.domain.translation.CloudFailureReason
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGatewayTest {
    private val configured = CloudConfiguration("https://example.org/api", "test-token", false, true)
    private val input = TranslationRequest("Hello", null, Language.Armenian)

    @Test
    fun policiesPreventAnyNetworkRequestWithoutConsentOrInOfflineMode() = runTest {
        listOf(configured.copy(offlineOnly = true), configured.copy(consent = false), configured.copy(endpoint = "")).forEach { settings ->
            val gateway = CloudGateway({ settings }, GatewayTransport { _, _, _ -> error("Unexpected network request") })
            val result = gateway.translate(input)
            assertTrue(result is TranslationOutcome.CloudFailure)
            assertTrue(gateway.speech("Hello", Language.English, 1f) is CloudAudioReply.Failure)
        }
    }

    @Test
    fun invalidEndpointsAndHeaderInjectionCannotBeConfigured() {
        listOf("http://example.org", "https://name:password@example.org", "https://example.org?q=1", "https://example.org#fragment", "file:///tmp/api").forEach {
            assertFalse(CloudConfiguration.validEndpoint(it))
        }
        assertTrue(configured.configured)
        assertFalse(configured.copy(accessToken = "token\r\nX-Test: yes").configured)
        assertFalse(configured.toString().contains("test-token"))
    }

    @Test
    fun successfulTranslationValidatesLanguagesAndUsesOnlineOrigin() = runTest {
        val gateway = gateway(200, """{"translated":"Բարև","source":"en","target":"hy"}""")
        val result = (gateway.translate(input) as TranslationOutcome.Success).result
        assertEquals("Բարև", result.translated)
        assertEquals(Language.English, result.source)
        assertEquals(TranslationOrigin.Online, result.origin)
    }

    @Test
    fun malformedAndMismatchedResultsCannotBecomeSuccessfulTranslations() = runTest {
        listOf("not json", "[]", "{}", """{"translated":"","source":"en","target":"hy"}""", """{"translated":"Hello","source":"en","target":"ru"}""", """{"translated":"Hello","source":[],"target":"hy"}""").forEach { body ->
            assertEquals(TranslationOutcome.CloudFailure(CloudFailureReason.InvalidResponse), gateway(200, body).translate(input))
        }
    }

    @Test
    fun authorizationRateLimitsTimeoutsAndMissingServerKeysHaveDistinctFailures() = runTest {
        listOf(
            Triple(401, "{}", CloudFailureReason.Unauthorized),
            Triple(429, "{}", CloudFailureReason.RateLimited),
            Triple(503, """{"error":"not_configured"}""", CloudFailureReason.NotConfigured),
            Triple(504, "{}", CloudFailureReason.Timeout),
            Triple(500, "{}", CloudFailureReason.Server),
        ).forEach { (status, body, reason) ->
            assertEquals(TranslationOutcome.CloudFailure(reason), gateway(status, body).translate(input))
        }
    }

    @Test
    fun cancellationIsNotTurnedIntoAnErrorOrRetried() = runTest {
        val gateway = CloudGateway({ configured }, GatewayTransport { _, _, _ -> throw CancellationException("cancelled") })
        var cancelled = false
        try { gateway.translate(input) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun oversizedTextIsRejectedBeforeSending() = runTest {
        val gateway = CloudGateway({ configured }, GatewayTransport { _, _, _ -> error("Unexpected request") })
        assertEquals(TranslationOutcome.CloudFailure(CloudFailureReason.InputTooLong), gateway.translate(input.copy(text = "x".repeat(4001))))
    }

    private fun gateway(status: Int, body: String) = CloudGateway({ configured }, GatewayTransport { _, path, request ->
        assertEquals("/v1/translate", path)
        assertTrue(request?.containsKey("text") == true)
        GatewayResponse(status, body.toByteArray(Charsets.UTF_8))
    })
}
