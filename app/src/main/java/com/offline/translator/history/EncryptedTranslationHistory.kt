package com.offline.translator.history

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.offline.translator.domain.translation.HistoryEntry
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationHistory
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationResult
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class EncryptedTranslationHistory(context: Context) : TranslationHistory {
    private val file = AtomicFile(File(context.noBackupFilesDir, "translations.enc"))
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    override val entries = mutableEntries.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded() }
    }

    override suspend fun record(result: TranslationResult) = mutate { entries ->
        val existing = entries.find { it.result == result }
        val entry = existing?.copy(timestamp = System.currentTimeMillis())
            ?: HistoryEntry(UUID.randomUUID().toString(), result, System.currentTimeMillis())
        listOf(entry) + entries.filter { it.id != entry.id }
    }

    override suspend fun toggleFavorite(id: String) = mutate { entries ->
        entries.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.delete()
            mutableEntries.value = emptyList()
            loaded = true
        }
    }

    private suspend fun mutate(transform: (List<HistoryEntry>) -> List<HistoryEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val updated = transform(mutableEntries.value).sortedByDescending { it.timestamp }
            val entries = (updated.filter { it.favorite } + updated.filterNot { it.favorite }.take(200))
                .sortedByDescending { it.timestamp }
            val json = JSONArray()
            entries.forEach { entry ->
                json.put(JSONObject().apply {
                    put("id", entry.id)
                    put("time", entry.timestamp)
                    put("favorite", entry.favorite)
                    put("original", entry.result.original)
                    put("translated", entry.result.translated)
                    put("source", entry.result.source.name)
                    put("target", entry.result.target.name)
                    put("origin", entry.result.origin.name)
                })
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val output = file.startWrite()
            try {
                output.write(cipher.iv)
                output.write(encrypted)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            mutableEntries.value = entries
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (_: java.io.FileNotFoundException) { loaded = true; return }
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        mutableEntries.value = List(json.length()) { index ->
            val item = json.getJSONObject(index)
            HistoryEntry(item.getString("id"), TranslationResult(
                item.getString("original"), item.getString("translated"),
                Language.valueOf(item.getString("source")), Language.valueOf(item.getString("target")),
                TranslationOrigin.valueOf(item.getString("origin")),
            ), item.getLong("time"), item.getBoolean("favorite"))
        }.sortedByDescending { it.timestamp }
        loaded = true
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("lingua-history-v1", null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("lingua-history-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
