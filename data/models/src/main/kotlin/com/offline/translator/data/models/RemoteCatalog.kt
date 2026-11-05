package com.offline.translator.data.models

import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RemoteCatalog(private val transport: ModelTransport, private val store: CatalogStore) {
    suspend fun refresh(url: String): CatalogOutcome = withContext(Dispatchers.IO) {
        try {
            transport.open(url, 0).use { response ->
                if (response.code != 200) return@withContext CatalogOutcome.Rejected(ModelFailure.InvalidResponse)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = response.stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > SignedCatalogReader.MaxCatalogBytes) {
                        return@withContext CatalogOutcome.Rejected(ModelFailure.InvalidCatalog)
                    }
                    output.write(buffer, 0, count)
                }
                currentCoroutineContext().ensureActive()
                store.refresh(output.toByteArray())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: DownloadException) { CatalogOutcome.Rejected(error.failure) }
        catch (_: IOException) { CatalogOutcome.Rejected(ModelFailure.Network) }
        catch (_: Exception) { CatalogOutcome.Rejected(ModelFailure.InvalidResponse) }
    }
}
