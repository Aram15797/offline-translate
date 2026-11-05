package com.offline.translator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import com.offline.translator.core.designsystem.OfflineTranslatorTheme
import com.offline.translator.data.cloud.CloudGateway
import com.offline.translator.data.cloud.CloudReply
import com.offline.translator.data.models.SignedCatalogReader
import com.offline.translator.data.translation.LocalPhrasebook
import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.domain.translation.CloudFailureReason
import com.offline.translator.domain.translation.EngineRouter
import com.offline.translator.domain.translation.Language
import com.offline.translator.domain.translation.TranslationPolicy
import com.offline.translator.feature.models.ModelsAction
import com.offline.translator.feature.models.ModelsScreen
import com.offline.translator.feature.models.ModelsViewModel
import com.offline.translator.feature.text.AppTheme
import com.offline.translator.feature.text.CloudCheckState
import com.offline.translator.feature.text.MediaToolsBar
import com.offline.translator.feature.text.TextTranslatorAction
import com.offline.translator.feature.text.TextTranslatorScreen
import com.offline.translator.feature.text.TextTranslatorViewModel
import com.offline.translator.media.CloudMediaViewModel
import com.offline.translator.media.SpeechPlaybackViewModel
import com.offline.translator.settings.InterfaceLocale
import com.offline.translator.settings.withInterfaceLanguage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class MainActivity : ComponentActivity() {
    private var lastNetworkAvailable = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetwork()
        }
        override fun onLost(network: Network) { updateNetwork() }
    }
    private val phrasebook = LocalPhrasebook()
    private val history get() = (application as LinguaApplication).history
    private val preferences get() = (application as LinguaApplication).preferences
    private val speechModel get() = viewModels[SpeechPlaybackViewModel::class.java]
    private val mediaModel get() = viewModels[CloudMediaViewModel::class.java]
    private val media get() = mediaModel.controller
    private val cameraFile get() = File(cacheDir, "captures/source.jpg")
    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) media.recognize(uri) }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) media.recognize(FileProvider.getUriForFile(this, "$packageName.files", cameraFile)) { cameraFile.delete() }
        else cameraFile.delete()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) media.startRecording() else media.permissionDenied()
    }
    private var cloudCheck by mutableStateOf(CloudCheckState())
    private var checkJob: kotlinx.coroutines.Job? = null
    private var incomingTextRevision by mutableIntStateOf(0)
    private val viewModels by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val model = when (modelClass) {
                    TextTranslatorViewModel::class.java -> TextTranslatorViewModel(EngineRouter(
                        phrasebook,
                        (application as LinguaApplication).cloud,
                        { preferences.settings.value.cloud.let { TranslationPolicy(it.offlineOnly, it.consent) } },
                        { getSystemService(ConnectivityManager::class.java).activeNetwork?.let { network ->
                            getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        } == true },
                    ), extras.createSavedStateHandle(), history)
                    ModelsViewModel::class.java -> ModelsViewModel((application as LinguaApplication).models)
                    SpeechPlaybackViewModel::class.java -> SpeechPlaybackViewModel(applicationContext, { preferences.settings.value }, (application as LinguaApplication).cloud)
                    CloudMediaViewModel::class.java -> CloudMediaViewModel(applicationContext, { preferences.settings.value.cloud }, (application as LinguaApplication).cloud)
                    else -> error(modelClass.name)
                }
                return modelClass.cast(model) ?: error(modelClass.name)
            }
        })
    }
    private val translator get() = viewModels[TextTranslatorViewModel::class.java]
    private val models get() = viewModels[ModelsViewModel::class.java]
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val catalogPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= SignedCatalogReader.MaxCatalogBytes)
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
                if (bytes != null) models.importCatalog(bytes) else models.reportImportFailure()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { models.reportImportFailure() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingTextRevision = savedInstanceState?.getInt("incoming_text_revision") ?: 0
        lastNetworkAvailable = networkAvailable()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        lifecycleScope.launch {
            try { history.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { translator.reportHistoryFailure() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mediaModel.pendingText.collect { text ->
                        if (text != null) {
                            translator.accept(TextTranslatorAction.EditText(text))
                            incomingTextRevision++
                            mediaModel.consume(text)
                        }
                    }
                }
                launch {
                    speechModel.message.collect { message ->
                        if (message != null) {
                            val context = withInterfaceLanguage(preferences.settings.value.interfaceLanguage)
                            Toast.makeText(this@MainActivity, context.getString(message), Toast.LENGTH_LONG).show()
                            speechModel.consumeMessage(message)
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) acceptIncomingText(intent)
        setContent {
            val state by translator.state.collectAsState()
            val modelsState by models.state.collectAsState()
            val settings by preferences.settings.collectAsState()
            val mediaState by media.state.collectAsState()
            val speechActive by speechModel.speaking.collectAsState()
            InterfaceLocale(settings.interfaceLanguage) {
                val darkTheme = when (settings.theme) {
                    AppTheme.System -> isSystemInDarkTheme()
                    AppTheme.Light -> false
                    AppTheme.Dark, AppTheme.Amoled -> true
                }
                val view = LocalView.current
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                OfflineTranslatorTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColors, amoled = settings.theme == AppTheme.Amoled) {
                    TextTranslatorScreen(
                        state = state,
                        phrases = phrasebook.phrases,
                        settings = settings,
                        incomingTextRevision = incomingTextRevision,
                        toolsContent = {
                            MediaToolsBar(mediaState.recording, mediaState.processing, mediaState.level, mediaState.error,
                                onRecord = {
                                    stopSpeech()
                                    if (mediaState.recording) media.finishRecording(state.source)
                                    else if (media.allowed()) {
                                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) media.startRecording()
                                        else microphone.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onChooseImage = { if (media.allowed()) photoPicker.launch("image/*") },
                                onCamera = {
                                    if (media.allowed()) {
                                        cameraFile.parentFile?.mkdirs()
                                        try { camera.launch(FileProvider.getUriForFile(this, "$packageName.files", cameraFile)) }
                                        catch (_: ActivityNotFoundException) { media.cameraUnavailable() }
                                    }
                                },
                                onCancel = mediaModel::cancel,
                            )
                        },
                        onSettingsChange = { value ->
                            if (value.cloud != settings.cloud) {
                                mediaModel.cancel()
                                checkJob?.cancel()
                                translator.cancelTranslation()
                                stopSpeech()
                            }
                            if (value.speechRate != settings.speechRate) stopSpeech()
                            lifecycleScope.launch {
                                val previous = preferences.settings.value
                                try {
                                    withContext(Dispatchers.IO) { preferences.update(value) }
                                    if (previous.cloud != value.cloud) translator.accept(TextTranslatorAction.ConfigurationChanged)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { reportSettingsFailure() }
                            }
                        },
                        cloudCheck = cloudCheck,
                        onCheckCloud = ::checkCloud,
                        onVoiceSettings = ::openVoiceSettings,
                        speaking = speechActive,
                        onStopSpeech = ::stopSpeech,
                        onSpeak = ::speak,
                        onAction = { action ->
                            when (action) {
                                is TextTranslatorAction.EditText, is TextTranslatorAction.SelectSource, is TextTranslatorAction.SelectTarget,
                                is TextTranslatorAction.Restore, TextTranslatorAction.SwapLanguages -> mediaModel.cancel()
                                else -> Unit
                            }
                            translator.accept(action)
                        },
                        onCopy = { text ->
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
                        },
                        onShare = { text ->
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }, getString(R.string.app_name)))
                        },
                        onInsert = if (intent.action == Intent.ACTION_PROCESS_TEXT &&
                            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                        ) {
                            { text ->
                                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
                                finish()
                            }
                        } else null,
                        modelsContent = {
                            ModelsScreen(modelsState, onAction = { action ->
                                if (action is ModelsAction.Install && Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                models.accept(action)
                            }, onImportCatalog = { catalogPicker.launch(arrayOf("application/json", "application/octet-stream")) })
                        },
                    )
                }
            }
        }
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
    }

    private fun updateNetwork() {
        runOnUiThread {
            val available = networkAvailable()
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (lastNetworkAvailable != available) {
                lastNetworkAvailable = available
                if (preferences.settings.value.cloud.enabled) translator.accept(TextTranslatorAction.ConfigurationChanged)
            }
        }
    }

    private fun checkCloud(configuration: CloudConfiguration) {
        checkJob?.cancel()
        cloudCheck = CloudCheckState(configuration, checking = true)
        checkJob = lifecycleScope.launch {
            val result = CloudGateway({ configuration }).request("/v1/status")
            cloudCheck = when (result) {
                is CloudReply.Failure -> CloudCheckState(configuration, failure = result.reason)
                is CloudReply.Success -> if ((result.value["version"] as? JsonPrimitive)?.intOrNull == 1) CloudCheckState(configuration, ready = true)
                    else CloudCheckState(configuration, failure = CloudFailureReason.InvalidResponse)
            }
        }
    }

    private fun speak(text: String, language: Language) = speechModel.speak(text, language)

    private fun stopSpeech() = speechModel.stop()

    private fun reportSettingsFailure() {
        val context = withInterfaceLanguage(preferences.settings.value.interfaceLanguage)
        Toast.makeText(this, context.getString(com.offline.translator.feature.text.R.string.settings_save_failed), Toast.LENGTH_LONG).show()
    }

    private fun openVoiceSettings() {
        stopSpeech()
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    override fun onStop() {
        if (!isChangingConfigurations) { media.cancelRecording(); stopSpeech() }
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("incoming_text_revision", incomingTextRevision)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingText(intent)
    }

    private fun acceptIncomingText(intent: Intent) {
        val text = when (intent.action) {
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT) else null
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }
        if (text != null) {
            mediaModel.cancel()
            stopSpeech()
            translator.accept(TextTranslatorAction.EditText(text.toString()))
            incomingTextRevision++
        }
    }
}
