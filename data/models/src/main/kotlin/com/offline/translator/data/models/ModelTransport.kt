package com.offline.translator.data.models

import com.offline.translator.domain.models.ModelFailure
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

interface ModelTransport {
    fun open(url: String, offset: Long): DownloadResponse
}

class DownloadResponse(
    val code: Int,
    val contentRange: String?,
    val stream: InputStream,
    private val release: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        try { stream.close() } finally { release() }
    }
}

class DownloadException(val failure: ModelFailure) : Exception()

class HttpsModelTransport : ModelTransport {
    override fun open(url: String, offset: Long): DownloadResponse {
        var current = URI(url)
        repeat(5) {
            if (current.scheme != "https" || current.host == null || current.userInfo != null ||
                current.fragment != null || current.port !in listOf(-1, 443)
            ) throw DownloadException(ModelFailure.InvalidResponse)
            val connection = current.toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw DownloadException(ModelFailure.InvalidResponse)
                    current = current.resolve(location)
                    connection.disconnect()
                } else {
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        val failure = if (code in 500..599 || code == 429) ModelFailure.Network else ModelFailure.InvalidResponse
                        throw DownloadException(failure)
                    }
                    if (connection.getHeaderField("Content-Encoding")?.let { it != "identity" } == true) {
                        throw DownloadException(ModelFailure.InvalidResponse)
                    }
                    return DownloadResponse(code, connection.getHeaderField("Content-Range"), connection.inputStream) {
                        connection.disconnect()
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw DownloadException(ModelFailure.InvalidResponse)
    }
}
