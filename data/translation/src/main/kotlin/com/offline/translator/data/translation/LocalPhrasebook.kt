package com.offline.translator.data.translation

import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.Phrase
import com.offline.translator.domain.translation.PhraseCategory
import com.offline.translator.domain.translation.Phrasebook
import com.offline.translator.domain.translation.TranslationOrigin
import com.offline.translator.domain.translation.TranslationOutcome
import com.offline.translator.domain.translation.TranslationRequest
import com.offline.translator.domain.translation.TranslationResult
import com.offline.translator.domain.translation.Translator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalPhrasebook : Phrasebook, Translator {
    override val phrases: List<Phrase> = listOf(
        phrase("hello", "Բարև", "Привет", "Hello"),
        phrase("thank_you", "Շնորհակալություն", "Спасибо", "Thank you"),
        phrase("please", "Խնդրում եմ", "Пожалуйста", "Please"),
        phrase("yes", "Այո", "Да", "Yes"),
        phrase("no", "Ոչ", "Нет", "No"),
        phrase("good_morning", "Բարի լույս", "Доброе утро", "Good morning"),
        phrase("good_evening", "Բարի երեկո", "Добрый вечер", "Good evening"),
        phrase("goodbye", "Ցտեսություն", "До свидания", "Goodbye"),
        phrase("how_are_you", "Ինչպե՞ս եք", "Как дела?", "How are you?"),
        phrase("help", "Օգնեք ինձ", "Помогите мне", "Help me", PhraseCategory.Health),
        phrase("price", "Որքա՞ն արժե", "Сколько стоит?", "How much does it cost?", PhraseCategory.Travel),
        phrase("water", "Ջուր եմ ուզում", "Я хочу воды", "I would like some water", PhraseCategory.Restaurant),
        phrase("airport", "Որտե՞ղ է օդանավակայանը", "Где аэропорт?", "Where is the airport?", PhraseCategory.Travel),
        phrase("taxi", "Ինձ տաքսի է պետք", "Мне нужно такси", "I need a taxi", PhraseCategory.Travel),
        phrase("hotel", "Որտե՞ղ է հյուրանոցը", "Где гостиница?", "Where is the hotel?", PhraseCategory.Travel),
        phrase("menu", "Խնդրում եմ, ճաշացանկը", "Меню, пожалуйста", "The menu, please", PhraseCategory.Restaurant),
        phrase("bill", "Հաշիվը, խնդրում եմ", "Счёт, пожалуйста", "The bill, please", PhraseCategory.Restaurant),
        phrase("doctor", "Ինձ բժիշկ է պետք", "Мне нужен врач", "I need a doctor", PhraseCategory.Health),
        phrase("pharmacy", "Որտե՞ղ է դեղատունը", "Где аптека?", "Where is the pharmacy?", PhraseCategory.Health),
        phrase("understand", "Ես չեմ հասկանում", "Я не понимаю", "I do not understand"),
    )

    override suspend fun translate(request: TranslationRequest): TranslationOutcome =
        withContext(Dispatchers.Default) {
            val text = request.text.trim()
            if (text.isEmpty()) return@withContext TranslationOutcome.EmptyInput
            val source = request.source ?: detectLanguage(text)
                ?: return@withContext TranslationOutcome.LanguageUndetected
            if (source == request.target) {
                return@withContext TranslationOutcome.Success(
                    TranslationResult(text, text, source, request.target, TranslationOrigin.Identity)
                )
            }
            val entry = phrases.firstOrNull { normalize(it.text(source)) == normalize(text) }
                ?: return@withContext TranslationOutcome.ModelRequired
            TranslationOutcome.Success(
                TranslationResult(text, entry.text(request.target), source, request.target, TranslationOrigin.Phrasebook)
            )
        }

    private fun detectLanguage(text: String): Language? {
        val scripts = text.filter(Char::isLetter).map { Character.UnicodeScript.of(it.code) }.toSet()
        return when {
            scripts == setOf(Character.UnicodeScript.ARMENIAN) -> Language.Armenian
            scripts == setOf(Character.UnicodeScript.CYRILLIC) -> Language.Russian
            scripts == setOf(Character.UnicodeScript.LATIN) -> Language.English
            else -> null
        }
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun phrase(id: String, hy: String, ru: String, en: String, category: PhraseCategory = PhraseCategory.Essentials): Phrase = Phrase(
        id,
        mapOf(Language.Armenian to hy, Language.Russian to ru, Language.English to en),
        category,
    )
}
