package com.offline.translator.domain.translation

enum class Language(val code: String) {
    Armenian("hy"), Russian("ru"), English("en")
}

data class TranslationRequest(
    val text: String,
    val source: Language?,
    val target: Language,
)

enum class TranslationOrigin { Phrasebook, OfflineModel, Online, Identity }

data class TranslationResult(
    val original: String,
    val translated: String,
    val source: Language,
    val target: Language,
    val origin: TranslationOrigin,
)

sealed interface TranslationOutcome {
    data class Success(val result: TranslationResult) : TranslationOutcome
    data object EmptyInput : TranslationOutcome
    data object LanguageUndetected : TranslationOutcome
    data object ModelRequired : TranslationOutcome
    data object OnlineUnavailable : TranslationOutcome
    data class CloudFailure(val reason: CloudFailureReason) : TranslationOutcome
}

interface Translator {
    suspend fun translate(request: TranslationRequest): TranslationOutcome
}

data class TranslationPolicy(
    val offlineOnly: Boolean = true,
    val cloudConsent: Boolean = false,
)

class EngineRouter(
    private val offline: Translator,
    private val online: Translator? = null,
    private val policy: () -> TranslationPolicy = { TranslationPolicy() },
    private val isNetworkAvailable: () -> Boolean = { false },
) : Translator {
    override suspend fun translate(request: TranslationRequest): TranslationOutcome {
        val local = offline.translate(request)
        if (local != TranslationOutcome.ModelRequired && local != TranslationOutcome.LanguageUndetected) return local
        val currentPolicy = policy()
        if (currentPolicy.offlineOnly || !currentPolicy.cloudConsent) return local
        if (!isNetworkAvailable()) return TranslationOutcome.OnlineUnavailable
        return online?.translate(request) ?: TranslationOutcome.OnlineUnavailable
    }
}

interface Phrasebook {
    val phrases: List<Phrase>
}

data class Phrase(
    val id: String,
    val texts: Map<Language, String>,
    val category: PhraseCategory = PhraseCategory.Essentials,
) {
    fun text(language: Language): String = texts.getValue(language)
}
