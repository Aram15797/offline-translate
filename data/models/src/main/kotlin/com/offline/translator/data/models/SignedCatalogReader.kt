package com.offline.translator.data.models

import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelCatalog
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelKind
import com.offline.translator.domain.models.ModelPackage
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class SignedCatalogReader(
    private val trustedKeys: Map<String, ByteArray>,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun read(envelopeBytes: ByteArray, acceptExpired: Boolean = false): CatalogOutcome {
        if (envelopeBytes.size > MaxCatalogBytes) return CatalogOutcome.Rejected(ModelFailure.InvalidCatalog)
        return try {
            val envelope = Json.parseToJsonElement(envelopeBytes.toString(Charsets.UTF_8)) as JsonObject
            val keyBytes = trustedKeys[envelope.string("keyId")]
                ?: return CatalogOutcome.Rejected(ModelFailure.UntrustedSignature)
            val payload = Base64.getDecoder().decode(envelope.string("payload"))
            val signature = Base64.getDecoder().decode(envelope.string("signature"))
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val valid = Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
            if (!valid) return CatalogOutcome.Rejected(ModelFailure.UntrustedSignature)
            val root = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as JsonObject
            require(root.number("schemaVersion") == 1L)
            val revision = root.number("revision").also { require(it > 0) }
            val expiresAt = root.number("expiresAtEpochSeconds")
            if (!acceptExpired && expiresAt <= nowEpochSeconds()) return CatalogOutcome.Rejected(ModelFailure.ExpiredCatalog)
            val packages = (root.getValue("packages") as JsonArray).also { require(it.size <= 100) }
                .map { parsePackage(it as JsonObject) }
            require(packages.map { it.id }.distinct().size == packages.size)
            CatalogOutcome.Verified(ModelCatalog(revision, expiresAt, packages))
        } catch (_: Exception) {
            CatalogOutcome.Rejected(ModelFailure.InvalidCatalog)
        }
    }

    private fun parsePackage(value: JsonObject): ModelPackage {
        val id = value.string("id").also { require(it.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) }
        val version = value.string("version").also { require(it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,3}")) && it.length <= 32) }
        val name = value.string("name").also { require(it.isNotBlank() && it.length <= 128) }
        val kind = ModelKind.valueOf(value.string("kind"))
        val languages = (value.getValue("languages") as JsonArray).map {
            (it as JsonPrimitive).content.also { language -> require(language.matches(Regex("[a-z]{2,3}(?:-[A-Z]{2})?"))) }
        }.also { require(it.isNotEmpty() && it.size <= 200 && it.distinct().size == it.size) }
        val url = value.string("url").also {
            val uri = URI(it)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
            require(uri.port == -1 || uri.port == 443)
        }
        val archiveBytes = value.number("archiveBytes").also { require(it in 1..20L * 1024 * 1024 * 1024) }
        val unpackedBytes = value.number("unpackedBytes").also { require(it in 1..40L * 1024 * 1024 * 1024) }
        val hash = value.string("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        return ModelPackage(id, version, name, kind, languages, url, archiveBytes, unpackedBytes, hash)
    }

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    private fun JsonObject.number(key: String): Long = (getValue(key) as JsonPrimitive).also { require(!it.isString) }.longOrNull
        ?: error(key)

    companion object { const val MaxCatalogBytes = 1024 * 1024 }
}
