package com.offline.translator.domain.models

import kotlinx.coroutines.flow.Flow

enum class ModelKind { Translation, SpeechRecognition, SpeechSynthesis, Ocr, Diarization }

data class ModelPackage(
    val id: String,
    val version: String,
    val name: String,
    val kind: ModelKind,
    val languages: List<String>,
    val url: String,
    val archiveBytes: Long,
    val unpackedBytes: Long,
    val sha256: String,
) {
    val storageKey: String get() = "$id-$version-$sha256"
}

data class ModelCatalog(val revision: Long, val expiresAtEpochSeconds: Long, val packages: List<ModelPackage>)

enum class ModelFailure {
    InvalidCatalog, UntrustedSignature, ExpiredCatalog, CatalogRollback,
    Network, InvalidResponse, InsufficientStorage, ChecksumMismatch, UnsafeArchive, Io,
}

sealed interface CatalogOutcome {
    data class Verified(val catalog: ModelCatalog) : CatalogOutcome
    data class Rejected(val failure: ModelFailure) : CatalogOutcome
}

sealed interface InstallOutcome {
    data object Installed : InstallOutcome
    data class Failed(val failure: ModelFailure) : InstallOutcome
}

sealed interface ModelStatus {
    data object Available : ModelStatus
    data object Queued : ModelStatus
    data class Downloading(val bytes: Long) : ModelStatus
    data object Verifying : ModelStatus
    data object Unpacking : ModelStatus
    data object Installed : ModelStatus
    data class Paused(val bytes: Long) : ModelStatus
    data class Failed(val failure: ModelFailure) : ModelStatus
}

data class ModelEntry(val model: ModelPackage, val status: ModelStatus)

data class ModelManagerState(
    val entries: List<ModelEntry> = emptyList(),
    val loading: Boolean = true,
    val catalogRevision: Long? = null,
    val failure: ModelFailure? = null,
)

interface ModelRepository {
    val canRefreshCatalog: Boolean
    suspend fun refreshCatalog(): CatalogOutcome
    fun observe(): Flow<ModelManagerState>
    suspend fun importCatalog(bytes: ByteArray): CatalogOutcome
    suspend fun install(id: String, unmeteredOnly: Boolean)
    suspend fun pause(id: String)
    suspend fun remove(id: String)
}
