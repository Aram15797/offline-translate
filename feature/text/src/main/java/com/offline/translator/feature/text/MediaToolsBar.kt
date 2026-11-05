package com.offline.translator.feature.text

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun MediaToolsBar(recording: Boolean, processing: Boolean, level: Float, error: Int?, onRecord: () -> Unit, onChooseImage: () -> Unit, onCamera: () -> Unit, onCancel: () -> Unit) {
    val amplitude by animateFloatAsState(level, label = "microphone level")
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onRecord, enabled = !processing, label = { Text(stringResource(if (recording) R.string.finish_recording else R.string.voice_input)) }, modifier = Modifier.testTag("voice_input"))
            AssistChip(onClick = onChooseImage, enabled = !processing && !recording, label = { Text(stringResource(R.string.photo_input)) })
            AssistChip(onClick = onCamera, enabled = !processing && !recording, label = { Text(stringResource(R.string.camera_input)) })
            if (recording || processing) AssistChip(onClick = onCancel, label = { Text(stringResource(R.string.cancel_media)) })
        }
        AnimatedVisibility(recording) { LinearProgressIndicator(progress = { amplitude }, modifier = Modifier.fillMaxWidth()) }
        if (recording) Text(stringResource(R.string.recording_note), style = MaterialTheme.typography.bodySmall)
        if (processing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
