package com.offline.translator.data.cloud

import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.domain.translation.CloudFailureReason
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import com.offline.translator.domain.translation.TranslationResult
import com.offline.translator.domain.translation.Translator
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class GatewayResponse(val status: Int, val bytes: ByteArray)

fun interface GatewayTransport {
    suspend fun execute(configuration: CloudConfiguration, path: String, body: JsonObject?): GatewayResponse
}

class HttpsGatewayTransport : GatewayTransport {
    override suspend fun execute(configuration: CloudConfiguration, path: String, body: JsonObject?): GatewayResponse = withContext(Dispatchers.IO) {
        require(configuration.configured && path.startsWith("/v1/"))
        suspendCancellableCoroutine { continuation ->
            val connection = URL(configuration.endpoint.trimEnd('/') + path).openConnection() as HttpsURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = if (path == "/v1/translate") 30000 else 90000
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Authorization", "Bearer " + configuration.accessToken)
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 12 * 1024 * 1024)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val output = ByteArrayOutputStream()
                stream?.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (!continuation.isActive) break
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024)
                        output.write(buffer, 0, count)
                    }
                }
                if (continuation.isActive) continuation.resume(GatewayResponse(status, output.toByteArray()))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { connection.disconnect() }
        }
    }
}

sealed interface CloudAudioReply {
    data class Success(val bytes: ByteArray) : CloudAudioReply
    data class Failure(val reason: CloudFailureReason) : CloudAudioReply
}

sealed interface CloudReply {
    data class Success(val value: JsonObject) : CloudReply
    data class Failure(val reason: CloudFailureReason) : CloudReply
}

class CloudGateway(
    private val configuration: () -> CloudConfiguration,
    private val transport: GatewayTransport = HttpsGatewayTransport(),
) : Translator {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun translate(request: TranslationRequest): TranslationOutcome {
        if (request.text.length > 4000) return TranslationOutcome.CloudFailure(CloudFailureReason.InputTooLong)
        val reply = this.request("/v1/translate", buildJsonObject {
            put("text", request.text)
            request.source?.let { put("source", it.code) }
            put("target", request.target.code)
        })
        return when (reply) {
            is CloudReply.Failure -> TranslationOutcome.CloudFailure(reply.reason)
            is CloudReply.Success -> {
                val source = (reply.value["source"] as? JsonPrimitive)?.content?.let { code -> Language.entries.find { it.code == code } }
                val translated = reply.value["translated"] as? JsonPrimitive
                val target = (reply.value["target"] as? JsonPrimitive)?.content
                if (source == null || translated?.isString != true || translated.content.isBlank() || target != request.target.code ||
                    request.source != null && request.source != source
                ) TranslationOutcome.CloudFailure(CloudFailureReason.InvalidResponse)
                else TranslationOutcome.Success(TranslationResult(request.text.trim(), translated.content, source, request.target, TranslationOrigin.Online))
            }
        }
    }

    suspend fun speech(text: String, language: Language, speed: Float): CloudAudioReply {
        val current = configuration()
        if (current.offlineOnly || !current.consent) return CloudAudioReply.Failure(CloudFailureReason.ConsentRequired)
        if (!current.configured) return CloudAudioReply.Failure(CloudFailureReason.NotConfigured)
        if (text.length > 2000) return CloudAudioReply.Failure(CloudFailureReason.InputTooLong)
        return try {
            val response = transport.execute(current, "/v1/speech", buildJsonObject {
                put("text", text); put("language", language.code); put("speed", speed)
            })
            if (response.status in 200..299 && response.bytes.isNotEmpty()) CloudAudioReply.Success(response.bytes)
            else CloudAudioReply.Failure(if (response.status in 200..299) CloudFailureReason.InvalidResponse else failure(response))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SocketTimeoutException) { CloudAudioReply.Failure(CloudFailureReason.Timeout) }
        catch (_: java.io.IOException) { CloudAudioReply.Failure(CloudFailureReason.Network) }
        catch (_: Exception) { CloudAudioReply.Failure(CloudFailureReason.InvalidResponse) }
    }

    suspend fun request(path: String, body: JsonObject? = null): CloudReply {
        val current = configuration()
        if (current.offlineOnly || !current.consent) return CloudReply.Failure(CloudFailureReason.ConsentRequired)
        if (!current.configured) return CloudReply.Failure(CloudFailureReason.NotConfigured)
        return try {
            val response = transport.execute(current, path, body)
            if (response.status in 200..299) {
                val value = json.parseToJsonElement(response.bytes.toString(Charsets.UTF_8)).jsonObject
                CloudReply.Success(value)
            } else CloudReply.Failure(failure(response))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SocketTimeoutException) { CloudReply.Failure(CloudFailureReason.Timeout) }
        catch (_: java.io.IOException) { CloudReply.Failure(CloudFailureReason.Network) }
        catch (_: Exception) { CloudReply.Failure(CloudFailureReason.InvalidResponse) }
    }

    private fun failure(response: GatewayResponse): CloudFailureReason {
        val reason = runCatching { json.parseToJsonElement(response.bytes.toString(Charsets.UTF_8)).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        return when {
            response.status == 401 || response.status == 403 -> CloudFailureReason.Unauthorized
            response.status == 429 -> CloudFailureReason.RateLimited
            response.status == 504 -> CloudFailureReason.Timeout
            reason == "not_configured" -> CloudFailureReason.NotConfigured
            reason == "no_text" -> CloudFailureReason.NoText
            reason == "language_undetected" -> CloudFailureReason.LanguageUndetected
            response.status == 413 -> CloudFailureReason.InputTooLong
            response.status >= 500 -> CloudFailureReason.Server
            else -> CloudFailureReason.InvalidResponse
        }
    }
}
