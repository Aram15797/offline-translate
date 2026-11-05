package com.offline.translator.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offline.translator.LinguaApplication
import com.offline.translator.R
import com.offline.translator.domain.models.CatalogOutcome
import com.offline.translator.domain.models.InstallOutcome
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelStatus
import kotlinx.coroutines.CancellationException

class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            val repository = (applicationContext as LinguaApplication).models
            val catalog = repository.readCatalog()
            if (catalog is CatalogOutcome.Rejected) return failure(catalog.failure)
            val model = (catalog as? CatalogOutcome.Verified)?.catalog?.packages
                ?.find { it.storageKey == inputData.getString("storageKey") }
                ?: return failure(ModelFailure.InvalidCatalog)
            setForeground(getForegroundInfo())
            setProgress(workDataOf("stage" to "queued"))
            return when (val outcome = repository.installer.install(model) { status ->
                setProgress(when (status) {
                    is ModelStatus.Downloading -> workDataOf("stage" to "download", "bytes" to status.bytes)
                    ModelStatus.Verifying -> workDataOf("stage" to "verify")
                    ModelStatus.Unpacking -> workDataOf("stage" to "unpack")
                    else -> workDataOf()
                })
                val percent = (status as? ModelStatus.Downloading)?.let { (it.bytes * 100 / model.archiveBytes).toInt() }
                val phase = when (status) {
                    ModelStatus.Verifying -> applicationContext.getString(com.offline.translator.feature.models.R.string.models_verifying)
                    ModelStatus.Unpacking -> applicationContext.getString(com.offline.translator.feature.models.R.string.models_unpacking)
                    else -> applicationContext.getString(R.string.model_notification_text)
                }
                setForeground(foregroundInfo(model.name, percent, phase))
            }) {
                InstallOutcome.Installed -> Result.success()
                is InstallOutcome.Failed -> if (outcome.failure == ModelFailure.Network && runAttemptCount < 3) {
                    Result.retry()
                } else failure(outcome.failure)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(ModelFailure.Io)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(
        title: String = applicationContext.getString(R.string.model_notification_title),
        percent: Int? = null,
        phase: String = applicationContext.getString(R.string.model_notification_text),
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelId = "model-downloads"
        manager.createNotificationChannel(NotificationChannel(
            channelId, applicationContext.getString(R.string.model_notification_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(phase)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.model_notification_pause), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id.hashCode(), notification)
    }

    private fun failure(value: ModelFailure) = Result.failure(workDataOf("failure" to value.name))
}
