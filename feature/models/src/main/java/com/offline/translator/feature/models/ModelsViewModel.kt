package com.offline.translator.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelManagerState
import com.offline.translator.domain.models.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(val manager: ModelManagerState = ModelManagerState(), val busy: Boolean = false, val canRefreshCatalog: Boolean = false)

sealed interface ModelsAction {
    data object RefreshCatalog : ModelsAction
    data class Install(val id: String, val unmeteredOnly: Boolean) : ModelsAction
    data class Pause(val id: String) : ModelsAction
    data class Remove(val id: String) : ModelsAction
}

class ModelsViewModel(private val repository: ModelRepository) : ViewModel() {
    private val failure = MutableStateFlow<ModelFailure?>(null)
    private val busy = MutableStateFlow(false)
    val state = combine(repository.observe(), failure, busy) { manager, error, running ->
        ModelsUiState(manager.copy(failure = error ?: manager.failure), running, repository.canRefreshCatalog)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelsUiState())

    fun accept(action: ModelsAction) = runOperation {
        when (action) {
            ModelsAction.RefreshCatalog -> {
                val result = repository.refreshCatalog()
                if (result is CatalogOutcome.Rejected) failure.value = result.failure
            }
            is ModelsAction.Install -> repository.install(action.id, action.unmeteredOnly)
            is ModelsAction.Pause -> repository.pause(action.id)
            is ModelsAction.Remove -> repository.remove(action.id)
        }
    }

    fun importCatalog(bytes: ByteArray) = runOperation {
        val result = repository.importCatalog(bytes)
        if (result is CatalogOutcome.Rejected) failure.value = result.failure
    }

    fun reportImportFailure() { failure.value = ModelFailure.InvalidCatalog }

    private fun runOperation(operation: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        failure.value = null
        viewModelScope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure.value = ModelFailure.Io }
            finally { busy.value = false }
        }
    }
}
