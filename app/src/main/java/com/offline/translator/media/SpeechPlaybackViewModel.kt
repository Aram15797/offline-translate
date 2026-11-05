package com.offline.translator.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.translator.R
import com.offline.translator.data.cloud.CloudAudioReply
import com.offline.translator.data.cloud.CloudGateway
import com.offline.translator.domain.translation.Language
import com.offline.translator.feature.text.TranslatorSettings
import com.offline.translator.feature.text.cloudFailureMessage
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeechPlaybackViewModel(
    context: Context,
    private val settings: () -> TranslatorSettings,
    private val gateway: CloudGateway,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) viewModelScope.launch { stop() } }.build()
    private var focusHeld = false
    @Volatile
    private var ready = false
    private var engine: TextToSpeech? = TextToSpeech(applicationContext) { ready = it == TextToSpeech.SUCCESS }
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var job: Job? = null
    private var utterance: String? = null
    private var sequence = 0L
    private val mutableSpeaking = MutableStateFlow(false)
    val speaking = mutableSpeaking.asStateFlow()
    private val mutableMessage = MutableStateFlow<Int?>(null)
    val message = mutableMessage.asStateFlow()

    init {
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { viewModelScope.launch { if (utterance == utteranceId) mutableSpeaking.value = true } }
            override fun onDone(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?) = finish(utteranceId, true)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    fun consumeMessage(value: Int) { if (mutableMessage.value == value) mutableMessage.value = null }

    fun speak(text: String, language: Language) {
        stop()
        focusHeld = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) { mutableMessage.value = com.offline.translator.feature.text.R.string.audio_busy; return }
        val voice = if (ready) engine?.voices?.filter {
            it.locale.language == Locale.forLanguageTag(language.code).language && !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }?.maxByOrNull { it.quality } else null
        val local = engine
        if (voice == null || local == null) {
            if (settings().cloud.enabled) speakCloud(text, language) else fail(R.string.speech_unavailable)
            return
        }
        val id = "translation-${++sequence}"
        utterance = id
        if (local.setAudioAttributes(attributes) == TextToSpeech.ERROR || local.setVoice(voice) == TextToSpeech.ERROR ||
            local.setSpeechRate(settings().speechRate) == TextToSpeech.ERROR || local.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR
        ) fail(R.string.speech_unavailable)
    }

    fun stop() {
        utterance = null
        job?.cancel()
        player?.release()
        player = null
        file?.delete()
        file = null
        mutableSpeaking.value = false
        engine?.stop()
        if (focusHeld) { focusHeld = false; audioManager.abandonAudioFocusRequest(focus) }
    }

    private fun finish(id: String?, failed: Boolean) {
        viewModelScope.launch {
            if (id != null && utterance == id) {
                stop()
                if (failed) mutableMessage.value = R.string.speech_unavailable
            }
        }
    }

    private fun fail(message: Int) { stop(); mutableMessage.value = message }

    private fun speakCloud(text: String, language: Language) {
        mutableSpeaking.value = true
        job = viewModelScope.launch {
            when (val response = gateway.speech(text, language, settings().speechRate)) {
                is CloudAudioReply.Failure -> fail(cloudFailureMessage(response.reason))
                is CloudAudioReply.Success -> {
                    var temporary: File? = null
                    try {
                        val audio = withContext(Dispatchers.IO) {
                            File.createTempFile("speech-", ".mp3", applicationContext.cacheDir).also { temporary = it; it.writeBytes(response.bytes) }
                        }
                        file = audio
                        val playback = MediaPlayer()
                        player = playback
                        playback.setAudioAttributes(attributes)
                        playback.setOnCompletionListener { completed -> if (player === completed) stop() }
                        playback.setOnErrorListener { failed, _, _ -> if (player === failed) fail(R.string.speech_unavailable); true }
                        playback.setOnPreparedListener { prepared -> if (player === prepared) runCatching { prepared.start() }.onFailure { fail(R.string.speech_unavailable) } }
                        playback.setDataSource(audio.absolutePath)
                        playback.prepareAsync()
                    } catch (cancelled: CancellationException) { temporary?.delete(); throw cancelled }
                    catch (_: Exception) { temporary?.delete(); fail(R.string.speech_unavailable) }
                }
            }
        }
    }

    override fun onCleared() { stop(); engine?.shutdown(); engine = null }
}
