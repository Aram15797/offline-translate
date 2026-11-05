package com.offline.translator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.offline.translator.data.cloud.CloudGateway
import com.offline.translator.settings.UserPreferences
import com.offline.translator.models.AndroidModelRepository
import com.offline.translator.history.EncryptedTranslationHistory

class LinguaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val startedAt = System.currentTimeMillis()
        applicationScope.launch {
            cacheDir.listFiles()?.filter { (it.name.startsWith("voice-") || it.name.startsWith("speech-")) && it.lastModified() < startedAt }?.forEach { it.delete() }
            File(cacheDir, "captures/source.jpg").takeIf { it.lastModified() < startedAt - 86400000 }?.delete()
        }
    }

    val preferences by lazy { UserPreferences(this) }
    val cloud by lazy { CloudGateway({ preferences.settings.value.cloud }) }
    val history by lazy { EncryptedTranslationHistory(this) }
    val models by lazy { AndroidModelRepository(this) }
}
