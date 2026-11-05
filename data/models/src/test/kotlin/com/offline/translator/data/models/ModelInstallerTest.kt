package com.offline.translator.data.models

import com.offline.translator.domain.models.InstallOutcome
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelKind
import com.offline.translator.domain.models.ModelPackage
import com.offline.translator.domain.models.ModelStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInstallerTest {
    @get:Rule val folder = TemporaryFolder()
    private val contents = "model weights".toByteArray()

    @Test fun installsVerifiedArchiveAndRemovesPartial() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        val installer = installer(full(archive))
        val progress = mutableListOf<ModelStatus>()
        assertEquals(InstallOutcome.Installed, installer.install(model) { progress.add(it) })
        assertTrue(installer.isInstalled(model))
        assertEquals("model weights", File(folder.root, "${model.storageKey}/model.onnx").readText())
        assertEquals(0L, installer.downloadedBytes(model))
        assertTrue(progress.contains(ModelStatus.Verifying))
        assertTrue(progress.contains(ModelStatus.Unpacking))
    }

    @Test fun resumesInterruptedDownloadUsingRange() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        val midpoint = archive.size / 2
        var calls = 0
        val transport = object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse {
                calls++
                return if (calls == 1) DownloadResponse(200, null, archive.copyOfRange(0, midpoint).inputStream()) else {
                    assertEquals(midpoint.toLong(), offset)
                    DownloadResponse(206, "bytes $midpoint-${archive.size - 1}/${archive.size}", archive.copyOfRange(midpoint, archive.size).inputStream())
                }
            }
        }
        val installer = installer(transport)
        assertEquals(InstallOutcome.Failed(ModelFailure.Network), installer.install(model) {})
        assertEquals(midpoint.toLong(), installer.downloadedBytes(model))
        assertEquals(InstallOutcome.Installed, installer.install(model) {})
    }

    @Test fun restartsWhenServerIgnoresRange() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        File(folder.root, "${model.storageKey}.part").writeBytes(archive.copyOfRange(0, 20))
        val installer = installer(full(archive))
        assertEquals(InstallOutcome.Installed, installer.install(model) {})
    }

    @Test fun rejectsWrongContentRangeAndClearsPartial() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        File(folder.root, "${model.storageKey}.part").writeBytes(archive.copyOfRange(0, 20))
        val installer = installer(object : ModelTransport {
            override fun open(url: String, offset: Long) = DownloadResponse(206, "bytes 0-${archive.size - 1}/${archive.size}", archive.inputStream())
        })
        assertEquals(InstallOutcome.Failed(ModelFailure.InvalidResponse), installer.install(model) {})
        assertFalse(installer.isInstalled(model))
        assertEquals(0L, installer.downloadedBytes(model))
    }

    @Test fun rejectsChecksumMismatchAndClearsPartial() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive).copy(sha256 = "0".repeat(64))
        val installer = installer(full(archive))
        assertEquals(InstallOutcome.Failed(ModelFailure.ChecksumMismatch), installer.install(model) {})
        assertFalse(installer.isInstalled(model))
        assertEquals(0L, installer.downloadedBytes(model))
    }

    @Test fun rejectsTraversalAbsolutePathsAndReservedMarker() = runTest {
        listOf("../escape.onnx", "/tmp/escape.onnx", ".verified", ".verified/model", "C:\\escape.onnx").forEach { name ->
            val archive = zip(name, contents)
            val model = model(archive)
            val installer = installer(full(archive))
            assertEquals(name, InstallOutcome.Failed(ModelFailure.UnsafeArchive), installer.install(model) {})
            assertFalse(installer.isInstalled(model))
            assertFalse(File(folder.root, "${model.storageKey}.staging").exists())
        }
    }

    @Test fun boundsUnpackedSizeToPreventZipBomb() = runTest {
        val archive = zip("model.onnx", ByteArray(10000))
        val model = model(archive).copy(unpackedBytes = 100)
        val installer = installer(full(archive))
        assertEquals(InstallOutcome.Failed(ModelFailure.UnsafeArchive), installer.install(model) {})
        assertFalse(installer.isInstalled(model))
    }

    @Test fun refusesDownloadWhenStorageIsInsufficient() = runTest {
        val archive = zip("model.onnx", contents)
        val installer = ModelInstaller(folder.root, object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse = error("Network must not be called")
        }, { 1 }, reserveBytes = 0)
        assertEquals(InstallOutcome.Failed(ModelFailure.InsufficientStorage), installer.install(model(archive)) {})
    }

    @Test fun cancellationPreservesCompletedDownloadForVerificationOnResume() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        val installer = installer(full(archive))
        var cancelled = false
        try {
            installer.install(model) { if (it == ModelStatus.Verifying) throw CancellationException() }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(installer.isInstalled(model))
        assertEquals(archive.size.toLong(), installer.downloadedBytes(model))
        val resumed = installer(object : ModelTransport {
            override fun open(url: String, offset: Long): DownloadResponse = error("Completed archive should be reused")
        })
        assertEquals(InstallOutcome.Installed, resumed.install(model) {})
    }

    @Test fun removalDeletesInstalledAndPartialFiles() = runTest {
        val archive = zip("model.onnx", contents)
        val model = model(archive)
        val installer = installer(full(archive))
        installer.install(model) {}
        installer.remove(model)
        assertFalse(installer.isInstalled(model))
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun removalClearsAllVersionsWithoutDeletingSimilarModelIds() = runTest {
        val archive = zip("model.onnx", contents)
        val first = model(archive)
        val latest = first.copy(version = "2.0")
        val other = first.copy(id = "mt-hy-2")
        val installer = installer(full(archive))
        installer.install(first) {}
        installer.install(latest) {}
        installer.install(other) {}
        installer.remove(latest)
        assertFalse(installer.isInstalled(first))
        assertFalse(installer.isInstalled(latest))
        assertTrue(installer.isInstalled(other))
    }

    private fun installer(transport: ModelTransport) = ModelInstaller(folder.root, transport, { Long.MAX_VALUE }, reserveBytes = 0)
    private fun full(archive: ByteArray) = object : ModelTransport {
        override fun open(url: String, offset: Long) = DownloadResponse(200, null, archive.inputStream())
    }
    private fun model(archive: ByteArray) = ModelPackage(
        "mt-hy", "1.0", "Translation", ModelKind.Translation, listOf("hy", "ru", "en"),
        "https://example.com/model.zip", archive.size.toLong(), contents.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) },
    )
    private fun zip(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        return output.toByteArray()
    }
}
