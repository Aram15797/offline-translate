package com.offline.translator.domain.translation

import java.net.URI

data class CloudConfiguration(
    val endpoint: String = "",
    val accessToken: String = "",
    val offlineOnly: Boolean = true,
    val consent: Boolean = false,
) {
    val configured: Boolean get() = validEndpoint(endpoint) && accessToken.isNotBlank() && accessToken.length <= 4096 && accessToken.none { it == '\r' || it == '\n' }
    val enabled: Boolean get() = !offlineOnly && consent && configured

    override fun toString(): String = "CloudConfiguration(configured=$configured, offlineOnly=$offlineOnly, consent=$consent)"

    companion object {
        fun validEndpoint(value: String): Boolean = try {
            val uri = URI(value)
            value.length <= 2048 && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
        } catch (_: Exception) { false }
    }
}

enum class CloudFailureReason { NotConfigured, Unauthorized, RateLimited, Timeout, Network, InvalidResponse, Server, ConsentRequired, InputTooLong, NoText, LanguageUndetected }
