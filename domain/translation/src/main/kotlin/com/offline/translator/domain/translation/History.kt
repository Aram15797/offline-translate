package com.offline.translator.domain.translation

import kotlinx.coroutines.flow.StateFlow

data class HistoryEntry(
    val id: String,
    val result: TranslationResult,
    val timestamp: Long,
    val favorite: Boolean = false,
)

interface TranslationHistory {
    val entries: StateFlow<List<HistoryEntry>>
    suspend fun record(result: TranslationResult)
    suspend fun toggleFavorite(id: String)
    suspend fun clear()
}

enum class PhraseCategory { Essentials, Travel, Restaurant, Health }
