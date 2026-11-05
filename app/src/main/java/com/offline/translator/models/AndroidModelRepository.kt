package com.offline.translator.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.offline.translator.data.models.CatalogStore
import com.offline.translator.data.models.HttpsModelTransport
import com.offline.translator.data.models.RemoteCatalog
import com.offline.translator.data.models.ModelInstaller
import com.offline.translator.data.models.SignedCatalogReader
import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelManagerState
import com.offline.translator.domain.models.ModelEntry
import com.offline.translator.domain.models.ModelPackage
import com.offline.translator.domain.models.ModelRepository
import com.offline.translator.domain.models.ModelStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidModelRepository(private val context: Context) : ModelRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = WorkManager.getInstance(context)
    private val metadata = context.getSharedPreferences("model-work", Context.MODE_PRIVATE)
    private val refresh = MutableStateFlow(0L)
    private val catalogState = MutableStateFlow(ModelManagerState())
    private val catalogMutex = Mutex()
    private val commandMutex = Mutex()
    private val reader = SignedCatalogReader(mapOf(context.assets.open("model-catalog-key-id.txt").bufferedReader().use { it.readText().trim() } to context.assets.open("model-catalog-public.der").use { it.readBytes() }))
    private val store = CatalogStore(File(context.filesDir, "models/catalog.json"), reader)
    val installer = ModelInstaller(File(context.filesDir, "models/packages"), HttpsModelTransport())

    private val catalogUrl = context.assets.open("model-catalog-url.txt").bufferedReader().use { it.readText().trim() }
    override val canRefreshCatalog: Boolean get() = catalogUrl.isNotBlank()
    private val remoteCatalog = RemoteCatalog(HttpsModelTransport(), store)

    private val initialization = scope.launch {
        catalogMutex.withLock {
            try {
                val result = store.read() ?: store.replace(context.assets.open("model-catalog.json").use { it.readBytes() })
                applyCatalog(result)
            } catch (_: Exception) {
                catalogState.value = ModelManagerState(loading = false, failure = ModelFailure.Io)
            }
        }
    }

    override suspend fun refreshCatalog(): CatalogOutcome {
        initialization.join()
        if (!canRefreshCatalog) return CatalogOutcome.Rejected(ModelFailure.InvalidCatalog)
        return catalogMutex.withLock {
            val result = remoteCatalog.refresh(catalogUrl)
            if (result is CatalogOutcome.Verified) applyCatalog(result)
            result
        }
    }

    override fun observe() = combine(catalogState, work.getWorkInfosByTagFlow(WorkTag), refresh) { base, jobs, _ ->
        base.copy(entries = base.entries.map { entry ->
            val model = entry.model
            val lastId = metadata.getString(model.storageKey, null)
            val job = jobs.find { it.id.toString() == lastId }
            entry.copy(status = status(model, job))
        })
    }.flowOn(Dispatchers.IO)

    override suspend fun importCatalog(bytes: ByteArray): CatalogOutcome = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            val result = store.replace(bytes)
            if (result is CatalogOutcome.Verified) applyCatalog(result)
            result
        }
    }

    override suspend fun install(id: String, unmeteredOnly: Boolean): Unit = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val model = catalogState.value.entries.find { it.model.id == id }?.model ?: return@withContext
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf("storageKey" to model.storageKey))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(
                    if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                ).build())
                .addTag(WorkTag)
                .addTag("model-id:${model.id}")
                .build()
            val jobs = work.getWorkInfosForUniqueWork(workName(model)).get()
            val active = jobs.find { !it.state.isFinished }
            if (active != null) {
                check(metadata.edit().putString(model.storageKey, active.id.toString()).commit())
                refresh.value++
                return@withContext
            }
            check(metadata.edit().putString(model.storageKey, request.id.toString()).commit())
            work.enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request).result.get()
            refresh.value++
            Unit
        }
    }

    override suspend fun pause(id: String): Unit = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            work.cancelAllWorkByTag("model-id:$id").result.get()
            refresh.value++
            Unit
        }
    }

    override suspend fun remove(id: String): Unit = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val model = catalogState.value.entries.find { it.model.id == id }?.model ?: return@withContext
            work.cancelAllWorkByTag("model-id:$id").result.get()
            installer.remove(model)
            metadata.edit().remove(model.storageKey).apply()
            refresh.value++
            Unit
        }
    }

    suspend fun readCatalog(): CatalogOutcome? = withContext(Dispatchers.IO) { catalogMutex.withLock { store.read() } }

    private fun applyCatalog(result: CatalogOutcome) {
        catalogState.value = when (result) {
            is CatalogOutcome.Verified -> ModelManagerState(
                entries = result.catalog.packages.map { ModelEntry(it, ModelStatus.Available) },
                loading = false,
                catalogRevision = result.catalog.revision,
            )
            is CatalogOutcome.Rejected -> ModelManagerState(loading = false, failure = result.failure)
        }
    }

    private fun status(model: ModelPackage, job: WorkInfo?): ModelStatus {
        if (installer.isInstalled(model)) return ModelStatus.Installed
        return when (job?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelStatus.Queued
            WorkInfo.State.RUNNING -> when (job.progress.getString("stage")) {
                "queued" -> ModelStatus.Queued
                "verify" -> ModelStatus.Verifying
                "unpack" -> ModelStatus.Unpacking
                else -> ModelStatus.Downloading(job.progress.getLong("bytes", installer.downloadedBytes(model)))
            }
            WorkInfo.State.FAILED -> ModelStatus.Failed(
                ModelFailure.entries.find { it.name == job.outputData.getString("failure") } ?: ModelFailure.Io
            )
            WorkInfo.State.CANCELLED -> ModelStatus.Paused(installer.downloadedBytes(model))
            else -> if (installer.downloadedBytes(model) > 0) ModelStatus.Paused(installer.downloadedBytes(model)) else ModelStatus.Available
        }
    }

    private fun workName(model: ModelPackage) = "install:${model.storageKey}"
    companion object { const val WorkTag = "model-download" }
}
