package com.offline.translator.feature.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.offline.translator.domain.translation.CloudConfiguration
import com.offline.translator.domain.translation.CloudFailureReason

data class CloudCheckState(
    val configuration: CloudConfiguration? = null,
    val checking: Boolean = false,
    val ready: Boolean = false,
    val failure: CloudFailureReason? = null,
)

@Composable
internal fun CloudSettingsCard(settings: TranslatorSettings, onUpdate: (TranslatorSettings) -> Unit, check: CloudCheckState, onCheck: (CloudConfiguration) -> Unit) {
    var draft by remember(settings.cloud) { mutableStateOf(settings.cloud) }
    val normalized = draft.copy(endpoint = draft.endpoint.trim().trimEnd('/'), accessToken = draft.accessToken.trim())
    val invalidEndpoint = draft.endpoint.isNotBlank() && !CloudConfiguration.validEndpoint(normalized.endpoint)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cloud_settings), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.cloud_explanation), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.offline_only), Modifier.weight(1f))
                Switch(draft.offlineOnly, onCheckedChange = { draft = draft.copy(offlineOnly = it) }, modifier = Modifier.testTag("cloud_offline_only"))
            }
            OutlinedTextField(
                value = draft.endpoint,
                onValueChange = { draft = draft.copy(endpoint = it) },
                label = { Text(stringResource(R.string.service_address)) },
                modifier = Modifier.fillMaxWidth().testTag("cloud_endpoint"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = invalidEndpoint,
                supportingText = { Text(stringResource(R.string.service_address_hint)) },
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = draft.accessToken,
                onValueChange = { draft = draft.copy(accessToken = it) },
                label = { Text(stringResource(R.string.service_token)) },
                modifier = Modifier.fillMaxWidth().testTag("cloud_token"),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(draft.consent, onCheckedChange = { draft = draft.copy(consent = it) }, modifier = Modifier.testTag("cloud_consent"))
                Text(stringResource(R.string.cloud_consent), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onUpdate(settings.copy(cloud = normalized)) }, enabled = !invalidEndpoint, modifier = Modifier.testTag("cloud_save")) { Text(stringResource(R.string.save_configuration)) }
                TextButton(onClick = { onCheck(normalized) }, enabled = normalized.enabled && !check.checking, modifier = Modifier.testTag("cloud_check")) { Text(stringResource(R.string.check_connection)) }
            }
            if (check.configuration == normalized) {
                if (check.checking) CircularProgressIndicator()
                if (check.ready) Text(stringResource(R.string.connection_ready), color = MaterialTheme.colorScheme.primary)
                check.failure?.let { Text(stringResource(cloudFailureMessage(it)), color = MaterialTheme.colorScheme.error) }
            }
            Text(stringResource(if (settings.cloud.enabled) R.string.cloud_mode_note else R.string.offline_mode_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun cloudFailureMessage(reason: CloudFailureReason): Int = when (reason) {
    CloudFailureReason.NotConfigured -> R.string.cloud_not_configured
    CloudFailureReason.Unauthorized -> R.string.cloud_unauthorized
    CloudFailureReason.RateLimited -> R.string.cloud_rate_limited
    CloudFailureReason.Timeout -> R.string.cloud_timeout
    CloudFailureReason.Network -> R.string.online_unavailable
    CloudFailureReason.InvalidResponse -> R.string.cloud_invalid_response
    CloudFailureReason.Server -> R.string.cloud_server_error
    CloudFailureReason.ConsentRequired -> R.string.cloud_consent_required
    CloudFailureReason.InputTooLong -> R.string.cloud_too_large
    CloudFailureReason.NoText -> R.string.cloud_no_text
    CloudFailureReason.LanguageUndetected -> R.string.language_undetected
}
