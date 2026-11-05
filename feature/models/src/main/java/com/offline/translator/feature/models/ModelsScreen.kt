package com.offline.translator.feature.models

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offline.translator.domain.models.ModelEntry
import com.offline.translator.domain.models.ModelFailure
import com.offline.translator.domain.models.ModelStatus

@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
    onImportCatalog: () -> Unit
) {
    var unmeteredOnly by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(state.canRefreshCatalog) {
        if (state.canRefreshCatalog) onAction(ModelsAction.RefreshCatalog)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.models_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.models_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.models_unmetered),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = unmeteredOnly,
                        onCheckedChange = { unmeteredOnly = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                if (state.canRefreshCatalog) {
                    FilledTonalButton(
                        onClick = { onAction(ModelsAction.RefreshCatalog) },
                        enabled = !state.busy && !state.manager.loading,
                        modifier = Modifier.fillMaxWidth().testTag("models_refresh"),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.models_refresh)) }
                }
                TextButton(
                    onClick = onImportCatalog,
                    enabled = !state.busy && !state.manager.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.models_import), fontWeight = FontWeight.Medium)
                }
            }
        }

        state.manager.failure?.let { failure ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = failureText(failure),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (state.manager.loading || state.busy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.manager.catalogRevision?.let { revision ->
            Text(
                text = stringResource(R.string.models_revision, revision),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!state.manager.loading && state.manager.entries.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("models_empty"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = stringResource(R.string.models_empty),
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.manager.entries.forEach { entry ->
            ModelCard(entry, state.busy, unmeteredOnly, onAction)
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    busy: Boolean,
    unmeteredOnly: Boolean,
    onAction: (ModelsAction) -> Unit
) {
    var confirmRemoval by rememberSaveable(entry.model.storageKey) { mutableStateOf(false) }
    val model = entry.model

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val (badgeBg, badgeFg) = when (entry.status) {
                    ModelStatus.Installed -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    is ModelStatus.Downloading, ModelStatus.Queued, ModelStatus.Verifying, ModelStatus.Unpacking ->
                        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    is ModelStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = when (val status = entry.status) {
                            ModelStatus.Available -> stringResource(R.string.models_available)
                            ModelStatus.Queued -> stringResource(R.string.models_queued)
                            is ModelStatus.Downloading -> stringResource(R.string.models_downloading, (status.bytes * 100 / model.archiveBytes).toInt())
                            ModelStatus.Verifying -> stringResource(R.string.models_verifying)
                            ModelStatus.Unpacking -> stringResource(R.string.models_unpacking)
                            ModelStatus.Installed -> stringResource(R.string.models_installed)
                            is ModelStatus.Paused -> stringResource(R.string.models_paused, (status.bytes * 100 / model.archiveBytes).toInt())
                            is ModelStatus.Failed -> failureText(status.failure)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.models_details,
                    model.version,
                    model.languages.joinToString(" / "),
                    model.archiveBytes / 1024.0 / 1024.0
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val active = entry.status is ModelStatus.Downloading || entry.status == ModelStatus.Queued ||
                    entry.status == ModelStatus.Verifying || entry.status == ModelStatus.Unpacking
            val downloading = entry.status as? ModelStatus.Downloading

            if (downloading != null) {
                val progressTarget = (downloading.bytes.toFloat() / model.archiveBytes).coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(
                    targetValue = progressTarget,
                    animationSpec = tween(durationMillis = 300),
                    label = "DownloadProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
            } else if (entry.status == ModelStatus.Verifying || entry.status == ModelStatus.Unpacking) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (active) {
                    FilledTonalButton(
                        onClick = { onAction(ModelsAction.Pause(model.id)) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.models_pause))
                    }
                } else if (entry.status != ModelStatus.Installed) {
                    Button(
                        onClick = { onAction(ModelsAction.Install(model.id, unmeteredOnly)) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(
                                if (entry.status is ModelStatus.Paused) R.string.models_resume
                                else R.string.models_download
                            )
                        )
                    }
                }

                if (entry.status != ModelStatus.Available) {
                    OutlinedButton(
                        onClick = { confirmRemoval = true },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.models_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = {
                Text(
                    stringResource(R.string.models_remove_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    stringResource(R.string.models_remove_message, model.name),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoval = false
                        onAction(ModelsAction.Remove(model.id))
                    }
                ) {
                    Text(
                        stringResource(R.string.models_remove),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) {
                    Text(stringResource(R.string.models_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun failureText(failure: ModelFailure) = stringResource(
    when (failure) {
        ModelFailure.InvalidCatalog -> R.string.models_error_catalog
        ModelFailure.UntrustedSignature -> R.string.models_error_signature
        ModelFailure.ExpiredCatalog -> R.string.models_error_expired
        ModelFailure.CatalogRollback -> R.string.models_error_rollback
        ModelFailure.Network -> R.string.models_error_network
        ModelFailure.InvalidResponse -> R.string.models_error_response
        ModelFailure.InsufficientStorage -> R.string.models_error_storage
        ModelFailure.ChecksumMismatch -> R.string.models_error_checksum
        ModelFailure.UnsafeArchive -> R.string.models_error_archive
        ModelFailure.Io -> R.string.models_error_io
    }
)
