package com.offline.translator.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.util.Base64
import com.offline.translator.data.cloud.CloudGateway
import com.offline.translator.data.cloud.CloudReply
import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.domain.translation.CloudFailureReason
import com.offline.translator.domain.translation.Language
import com.offline.translator.feature.text.R
import com.offline.translator.feature.text.cloudFailureMessage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MediaToolsState(val recording: Boolean = false, val processing: Boolean = false, val level: Float = 0f, val error: Int? = null)

class CloudMediaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configuration: () -> CloudConfiguration,
    private val gateway: CloudGateway,
    private val onText: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(MediaToolsState())
    val state = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var job: Job? = null
    private var meter: Job? = null
    private var preparing = false
    private var recordingSession = 0L

    fun allowed(): Boolean {
        val current = configuration()
        val reason = when {
            current.offlineOnly || !current.consent -> CloudFailureReason.ConsentRequired
            !current.configured -> CloudFailureReason.NotConfigured
            else -> null
        }
        if (reason != null) mutableState.value = MediaToolsState(error = cloudFailureMessage(reason))
        return reason == null
    }

    fun permissionDenied() { mutableState.value = MediaToolsState(error = R.string.microphone_denied) }
    fun cameraUnavailable() { mutableState.value = MediaToolsState(error = R.string.camera_unavailable) }

    fun startRecording() {
        if (mutableState.value.processing || mutableState.value.recording || !allowed()) return
        val attempt = ++recordingSession
        preparing = true
        mutableState.value = MediaToolsState(processing = true)
        job = scope.launch {
            var file: File? = null
            var created: MediaRecorder? = null
            var published = false
            try {
                withContext(Dispatchers.IO) {
                    val output = File.createTempFile("voice-", ".m4a", context.cacheDir)
                    file = output
                    val device = MediaRecorder()
                    created = device
                    device.setAudioSource(MediaRecorder.AudioSource.MIC)
                    device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    device.setAudioSamplingRate(16000)
                    device.setAudioEncodingBitRate(64000)
                    device.setMaxDuration(60000)
                    device.setOutputFile(output.absolutePath)
                    device.setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) scope.launch {
                            if (recorder === device && recordingSession == attempt) finishRecording(null)
                        }
                    }
                    device.prepare()
                    device.start()
                }
                if (!allowed()) return@launch
                val device = requireNotNull(created)
                recorder = device
                recordingFile = file
                published = true
                mutableState.value = MediaToolsState(recording = true)
                meter = scope.launch {
                    while (true) {
                        val amplitude = withContext(Dispatchers.IO) { runCatching { device.maxAmplitude }.getOrDefault(0) }
                        mutableState.value = mutableState.value.copy(level = (amplitude / 16000f).coerceIn(0f, 1f))
                        delay(100)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (attempt == recordingSession) mutableState.value = MediaToolsState(error = R.string.recording_failed)
            } finally {
                if (!published) withContext(NonCancellable + Dispatchers.IO) { created?.release(); file?.delete() }
                if (attempt == recordingSession) preparing = false
            }
        }
    }

    fun finishRecording(language: Language?) {
        val active = recorder ?: return
        val file = recordingFile ?: return
        recorder = null
        recordingFile = null
        meter?.cancel()
        mutableState.value = MediaToolsState(processing = true)
        job = scope.launch {
            var released = false
            try {
                val encoded = withContext(Dispatchers.IO) {
                    try { active.stop() }
                    finally { active.release(); released = true }
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                val response = gateway.request("/v1/transcribe", buildJsonObject {
                    put("audio", encoded)
                    put("mediaType", "audio/mp4")
                    language?.let { put("language", it.code) }
                })
                accept(response)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = MediaToolsState(error = R.string.recording_failed) }
            finally { withContext(NonCancellable + Dispatchers.IO) { if (!released) active.release(); file.delete() } }
        }
    }

    fun recognize(uri: Uri, onFinished: () -> Unit = {}) {
        if (mutableState.value.processing || !allowed()) { onFinished(); return }
        cancel()
        mutableState.value = MediaToolsState(processing = true)
        job = scope.launch {
            try {
                val encoded = withContext(Dispatchers.IO) { image(uri) }
                accept(gateway.request("/v1/ocr", buildJsonObject { put("image", encoded); put("mediaType", "image/jpeg") }))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = MediaToolsState(error = R.string.image_failed) }
            finally { onFinished() }
        }
    }

    fun cancelRecording() { if (recorder != null || preparing) cancel() }

    fun cancel() {
        recordingSession++
        preparing = false
        job?.cancel()
        meter?.cancel()
        recorder?.run { runCatching { stop() }; release() }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        mutableState.value = MediaToolsState()
    }

    private fun accept(reply: CloudReply) {
        when (reply) {
            is CloudReply.Failure -> mutableState.value = MediaToolsState(error = cloudFailureMessage(reply.reason))
            is CloudReply.Success -> {
                val text = reply.value["text"] as? JsonPrimitive
                if (text?.isString == true && text.content.isNotBlank()) {
                    mutableState.value = MediaToolsState()
                    onText(text.content)
                } else mutableState.value = MediaToolsState(error = R.string.cloud_no_text)
            }
        }
    }

    private fun image(uri: Uri): String {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 12 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("image")
        val orientation = runCatching { data.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val flattened = if (rotated.hasAlpha()) Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
        } else rotated
        try {
            val output = ByteArrayOutputStream()
            check(flattened.compress(Bitmap.CompressFormat.JPEG, 88, output))
            require(output.size() <= 6 * 1024 * 1024)
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (flattened !== rotated) flattened.recycle()
            if (rotated !== bitmap) rotated.recycle()
            bitmap.recycle()
        }
    }
}
