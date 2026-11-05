package com.offline.translator.data.models

import com.offline.translator.domain.models.InstallOutcome
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelPackage
import com.offline.translator.domain.models.ModelStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelInstaller(
    private val root: File,
    private val transport: ModelTransport,
    private val availableBytes: () -> Long = { root.usableSpace },
    private val reserveBytes: Long = 64L * 1024 * 1024,
) {
    private val mutex = Mutex()

    suspend fun install(model: ModelPackage, onProgress: suspend (ModelStatus) -> Unit): InstallOutcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val staging = staging(model)
                try {
                    if (isInstalled(model)) return@withLock InstallOutcome.Installed
                    root.mkdirs()
                    val partial = partial(model)
                    if (partial.length() > model.archiveBytes) partial.delete()
                    val required = model.archiveBytes - partial.length() + model.unpackedBytes + reserveBytes
                    if (availableBytes() < required) return@withLock InstallOutcome.Failed(ModelFailure.InsufficientStorage)
                    onProgress(ModelStatus.Downloading(partial.length()))
                    download(model, partial, onProgress)
                    onProgress(ModelStatus.Verifying)
                    if (sha256(partial) != model.sha256) {
                        partial.delete()
                        return@withLock InstallOutcome.Failed(ModelFailure.ChecksumMismatch)
                    }
                    onProgress(ModelStatus.Unpacking)
                    staging.deleteRecursively()
                    check(staging.mkdirs())
                    unpack(partial, staging, model.unpackedBytes)
                    File(staging, VerificationMarker).writeText(model.sha256)
                    currentCoroutineContext().ensureActive()
                    val destination = installedDirectory(model)
                    if (destination.exists()) destination.deleteRecursively()
                    Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    partial.delete()
                    InstallOutcome.Installed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: DownloadException) {
                    if (error.failure == ModelFailure.UnsafeArchive || error.failure == ModelFailure.InvalidResponse) {
                        partial(model).delete()
                    }
                    InstallOutcome.Failed(error.failure)
                } catch (_: IOException) {
                    InstallOutcome.Failed(ModelFailure.Io)
                } catch (_: Exception) {
                    InstallOutcome.Failed(ModelFailure.Io)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    fun isInstalled(model: ModelPackage): Boolean = try {
        File(installedDirectory(model), VerificationMarker).readText() == model.sha256
    } catch (_: IOException) { false }

    fun downloadedBytes(model: ModelPackage): Long = partial(model).length().coerceAtMost(model.archiveBytes)

    suspend fun remove(model: ModelPackage) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(model.id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
            val pattern = Regex("${Regex.escape(model.id)}-[0-9]+(?:\\.[0-9]+){0,3}-[a-f0-9]{64}(?:\\.part|\\.staging)?")
            root.listFiles().orEmpty().filter { it.name.matches(pattern) }.forEach { check(it.deleteRecursively()) }
        }
    }

    private suspend fun download(model: ModelPackage, partial: File, onProgress: suspend (ModelStatus) -> Unit) {
        if (partial.length() == model.archiveBytes) return
        val offset = partial.length()
        val response = try { transport.open(model.url, offset) } catch (error: IOException) {
            throw DownloadException(ModelFailure.Network)
        }
        response.use {
            val append = when (response.code) {
                200 -> false
                206 -> {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.contentRange.orEmpty())
                        ?: throw DownloadException(ModelFailure.InvalidResponse)
                    val start = range.groupValues[1].toLongOrNull()
                    val end = range.groupValues[2].toLongOrNull()
                    val total = range.groupValues[3].toLongOrNull()
                    if (start != offset || end != model.archiveBytes - 1 || total != model.archiveBytes) {
                        throw DownloadException(ModelFailure.InvalidResponse)
                    }
                    true
                }
                else -> throw DownloadException(ModelFailure.InvalidResponse)
            }
            var count = if (append) offset else 0L
            var lastProgressAt = 0L
            partial.outputStreamForAppend(append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = try { response.stream.read(buffer) } catch (_: IOException) {
                        throw DownloadException(ModelFailure.Network)
                    }
                    if (read < 0) break
                    if (count + read > model.archiveBytes) {
                        throw DownloadException(ModelFailure.InvalidResponse)
                    }
                    output.write(buffer, 0, read)
                    count += read
                    val now = System.nanoTime()
                    if (now - lastProgressAt >= 250_000_000 || count == model.archiveBytes) {
                        onProgress(ModelStatus.Downloading(count))
                        lastProgressAt = now
                    }
                }
                output.fd.sync()
            }
            if (count != model.archiveBytes) throw DownloadException(ModelFailure.Network)
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun unpack(archive: File, directory: File, expectedBytes: Long) {
        val destinationRoot = directory.toPath().toAbsolutePath().normalize()
        var total = 0L
        var entries = 0
        var files = 0
        val paths = mutableSetOf<String>()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > 10000 || entry.name.contains('\\') || entry.name.contains(':')) {
                    throw DownloadException(ModelFailure.UnsafeArchive)
                }
                val target = destinationRoot.resolve(entry.name).normalize()
                if (!target.startsWith(destinationRoot) || target == destinationRoot ||
                    target.startsWith(destinationRoot.resolve(VerificationMarker)) || !paths.add(target.toString())
                ) throw DownloadException(ModelFailure.UnsafeArchive)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    files++
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > expectedBytes) throw DownloadException(ModelFailure.UnsafeArchive)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (files == 0 || total != expectedBytes) throw DownloadException(ModelFailure.UnsafeArchive)
    }

    private fun File.outputStreamForAppend(append: Boolean) = java.io.FileOutputStream(this, append)
    private fun partial(model: ModelPackage): File = safeFile("${model.storageKey}.part")
    private fun staging(model: ModelPackage): File = safeFile("${model.storageKey}.staging")
    private fun installedDirectory(model: ModelPackage): File = safeFile(model.storageKey)
    private fun safeFile(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9.-]{1,180}")))
        return File(root, name)
    }

    companion object { private const val VerificationMarker = ".verified" }
}
