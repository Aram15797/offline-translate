package com.offline.translator.data.models

import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelFailure
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val reader = SignedCatalogReader(mapOf("test" to keys.public.encoded)) { 1000 }
    private val packageJson = """{"id":"mt-hy","version":"1.0","name":"Translation","kind":"Translation","languages":["hy","ru","en"],"url":"https://example.com/model.zip","archiveBytes":1024,"unpackedBytes":2048,"sha256":"${"a".repeat(64)}"}"""

    @Test fun acceptsValidSignedPackage() {
        val result = reader.read(envelope(payload("[$packageJson]"))) as CatalogOutcome.Verified
        assertEquals("mt-hy", result.catalog.packages.single().id)
        assertEquals(2L, result.catalog.revision)
    }

    @Test fun rejectsTamperedPayload() {
        val signed = envelope(payload())
        val envelope = Json.parseToJsonElement(signed.toString(Charsets.UTF_8)) as JsonObject
        val changed = JsonObject(envelope + ("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload(revision = 5).toByteArray()))))
        assertEquals(CatalogOutcome.Rejected(ModelFailure.UntrustedSignature), reader.read(changed.toString().toByteArray()))
    }

    @Test fun rejectsUnknownSigningKey() {
        val bytes = envelope(payload()).toString(Charsets.UTF_8).replace("\"test\"", "\"unknown\"").toByteArray()
        assertEquals(CatalogOutcome.Rejected(ModelFailure.UntrustedSignature), reader.read(bytes))
    }

    @Test fun rejectsExpiredCatalog() {
        assertEquals(CatalogOutcome.Rejected(ModelFailure.ExpiredCatalog), reader.read(envelope(payload(expires = 999))))
    }

    @Test fun rejectsUnsignedOrOversizedCatalog() {
        assertEquals(CatalogOutcome.Rejected(ModelFailure.InvalidCatalog), reader.read(payload().toByteArray()))
        assertEquals(CatalogOutcome.Rejected(ModelFailure.InvalidCatalog), reader.read(ByteArray(SignedCatalogReader.MaxCatalogBytes + 1)))
    }

    @Test fun rejectsUnsafeIdsUrlsAndInvalidSizes() {
        val invalid = listOf(
            packageJson.replace("mt-hy", "../escape"),
            packageJson.replace("https://", "http://"),
            packageJson.replace("example.com/", "user@example.com/"),
            packageJson.replace("archiveBytes\":1024", "archiveBytes\":-1"),
            packageJson.replace("version\":\"1.0\"", "version\":\"../1\""),
            packageJson.replace("kind\":\"Translation\"", "kind\":\"Unknown\""),
        )
        invalid.forEach { data ->
            assertEquals(CatalogOutcome.Rejected(ModelFailure.InvalidCatalog), reader.read(envelope(payload("[$data]"))))
        }
    }

    @Test fun rejectsDuplicatePackages() {
        assertEquals(CatalogOutcome.Rejected(ModelFailure.InvalidCatalog), reader.read(envelope(payload("[$packageJson,$packageJson]"))))
    }

    @Test fun failedImportKeepsPreviousCatalog() {
        val file = File(folder.root, "catalog.json")
        val store = CatalogStore(file, reader)
        val original = envelope(payload())
        assertTrue(store.replace(original) is CatalogOutcome.Verified)
        assertTrue(store.replace("invalid".toByteArray()) is CatalogOutcome.Rejected)
        assertTrue(file.readBytes().contentEquals(original))
    }

    @Test fun rejectsRollbackEvenWhenStoredCatalogHasExpired() {
        val file = File(folder.root, "catalog.json")
        file.writeBytes(envelope(payload(revision = 8, expires = 900)))
        val store = CatalogStore(file, reader)
        assertEquals(CatalogOutcome.Rejected(ModelFailure.CatalogRollback), store.replace(envelope(payload(revision = 7))))
    }

    @Test fun acceptsNewerRevisionAtomically() {
        val store = CatalogStore(File(folder.root, "catalog.json"), reader)
        store.replace(envelope(payload()))
        assertTrue(store.replace(envelope(payload(revision = 3))) is CatalogOutcome.Verified)
        assertEquals(3L, (store.read() as CatalogOutcome.Verified).catalog.revision)
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun remoteRefreshAcceptsIdenticalCatalogButRejectsChangedSameRevision() = runBlocking {
        val store = CatalogStore(File(folder.root, "catalog.json"), reader)
        val original = envelope(payload())
        store.replace(original)
        val unchanged = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long) = DownloadResponse(200, null, original.inputStream())
        }, store)
        assertTrue(unchanged.refresh("https://example.com/catalog.json") is CatalogOutcome.Verified)
        val modified = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long) = DownloadResponse(200, null, envelope(payload("[$packageJson]")).inputStream())
        }, store)
        assertEquals(CatalogOutcome.Rejected(ModelFailure.CatalogRollback), modified.refresh("https://example.com/catalog.json"))
        assertTrue(File(folder.root, "catalog.json").readBytes().contentEquals(original))
    }

    @Test fun remoteRefreshInstallsNewSignedRevisionAndClosesResponse() = runBlocking {
        val store = CatalogStore(File(folder.root, "catalog.json"), reader)
        store.replace(envelope(payload()))
        var closed = false
        val updater = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse {
                assertEquals(0L, offset)
                return DownloadResponse(200, null, envelope(payload("[$packageJson]", revision = 3)).inputStream()) { closed = true }
            }
        }, store)
        val result = updater.refresh("https://example.com/catalog.json") as CatalogOutcome.Verified
        assertEquals(3L, result.catalog.revision)
        assertEquals("mt-hy", result.catalog.packages.single().id)
        assertTrue(closed)
        assertEquals(result, store.read())
    }

    @Test fun remoteRefreshRejectsOversizedOrPartialResponseWithoutReplacingCache() = runBlocking {
        val file = File(folder.root, "catalog.json")
        val store = CatalogStore(file, reader)
        val original = envelope(payload())
        store.replace(original)
        for (code in listOf(200, 206)) {
            var closed = false
            val updater = RemoteCatalog(object : ModelTransport {
                override fun open(url: String, offset: Long) = DownloadResponse(code, null,
                    ByteArray(SignedCatalogReader.MaxCatalogBytes + 1).inputStream()) { closed = true }
            }, store)
            val expected = if (code == 200) ModelFailure.InvalidCatalog else ModelFailure.InvalidResponse
            assertEquals(CatalogOutcome.Rejected(expected), updater.refresh("https://example.com/catalog.json"))
            assertTrue(file.readBytes().contentEquals(original))
            assertTrue(closed)
        }
    }

    @Test fun remoteNetworkFailurePreservesCachedCatalog() = runBlocking {
        val store = CatalogStore(File(folder.root, "catalog.json"), reader)
        val original = envelope(payload())
        store.replace(original)
        val updater = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse = throw java.io.IOException()
        }, store)
        assertEquals(CatalogOutcome.Rejected(ModelFailure.Network), updater.refresh("https://example.com/catalog.json"))
        assertTrue(store.read() is CatalogOutcome.Verified)
    }

    @Test fun unchangedRemoteCatalogStillRequiresValidExpiry() = runBlocking {
        val file = File(folder.root, "catalog.json")
        val expired = envelope(payload(expires = 999))
        file.writeBytes(expired)
        val updater = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long) = DownloadResponse(200, null, expired.inputStream())
        }, CatalogStore(file, reader))
        assertEquals(CatalogOutcome.Rejected(ModelFailure.ExpiredCatalog), updater.refresh("https://example.com/catalog.json"))
    }

    @Test fun remoteCancellationPropagatesWithoutReplacingCatalog() = runBlocking {
        val store = CatalogStore(File(folder.root, "catalog.json"), reader)
        store.replace(envelope(payload()))
        val updater = RemoteCatalog(object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse = throw kotlinx.coroutines.CancellationException()
        }, store)
        var cancelled = false
        try { updater.refresh("https://example.com/catalog.json") }
        catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(store.read() is CatalogOutcome.Verified)
    }

    private fun payload(packages: String = "[]", revision: Long = 2, expires: Long = 2000) =
        """{"schemaVersion":1,"revision":$revision,"expiresAtEpochSeconds":$expires,"packages":$packages}"""

    private fun envelope(payload: String): ByteArray {
        val bytes = payload.toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(bytes); sign() }
        return JsonObject(mapOf(
            "keyId" to JsonPrimitive("test"),
            "payload" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)),
        )).toString().toByteArray()
    }
}
