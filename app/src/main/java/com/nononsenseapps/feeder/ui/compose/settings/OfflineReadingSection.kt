package com.nononsenseapps.feeder.ui.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.offline.OfflineDownloader
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * Settings > Offline reading: the Offline mode switch, and the one-time Download for offline
 * job with its progress, Stop and Remove downloads. Nothing downloads unless asked to here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineReadingSection(modifier: Modifier = Modifier) {
    val di = LocalDI.current
    val repository: Repository by di.instance()
    val downloader: OfflineDownloader by di.instance()
    val dimens = LocalDimens.current
    val coroutineScope = rememberCoroutineScope()

    val offlineMode by repository.offlineMode.collectAsState()
    val limit by repository.offlineDownloadLimit.collectAsState()
    val images by repository.offlineDownloadImages.collectAsState()
    val job by downloader.state.collectAsState()
    val downloadedCount by downloader.downloadedCount.collectAsState(initial = 0)
    var storageBytes by remember { mutableLongStateOf(0L) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(downloadedCount, job.running, job.done) {
        storageBytes = downloader.storageBytes()
    }

    Column(modifier = modifier) {
        SwitchSetting(
            title = stringResource(R.string.offline_mode),
            description = stringResource(R.string.offline_mode_description),
            checked = offlineMode,
            onCheckedChange = { repository.setOfflineMode(it) },
            icon = { Icon(Icons.Default.CloudOff, contentDescription = null) },
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .width(dimens.maxContentWidth)
                    .padding(start = 64.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        ) {
            Text(
                stringResource(R.string.offline_articles_to_download),
                style = MaterialTheme.typography.bodyLarge,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                OfflineDownloader.LIMIT_OPTIONS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == limit,
                        onClick = { repository.setOfflineDownloadLimit(option) },
                        enabled = !job.running,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = OfflineDownloader.LIMIT_OPTIONS.size),
                        icon = {},
                        label = {
                            Text(
                                if (option == 0) stringResource(R.string.offline_all_unread) else option.toString(),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }

        SwitchSetting(
            title = stringResource(R.string.offline_include_images),
            checked = images,
            onCheckedChange = { repository.setOfflineDownloadImages(it) },
            enabled = !job.running,
            icon = null,
            modifier = Modifier.padding(start = 64.dp),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .width(dimens.maxContentWidth)
                    .padding(start = 64.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.running) {
                    Button(
                        onClick = { downloader.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Text(stringResource(R.string.offline_stop), modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    Button(
                        onClick = { downloader.start(limit, images) },
                        enabled = !offlineMode,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Text(stringResource(R.string.offline_download), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = !job.running && (downloadedCount > 0 || storageBytes > 0),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text(stringResource(R.string.offline_remove), modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (job.running || job.total > 0) {
                LinearProgressIndicator(
                    progress = { if (job.total > 0) job.done.toFloat() / job.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.offline_progress, job.done, job.total, job.images, job.failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (job.message.isNotBlank()) {
                Text(job.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (offlineMode && !job.running) {
                Text(
                    stringResource(R.string.offline_turn_off_to_download),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.offline_downloaded_summary, downloadedCount, formatBytes(storageBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.offline_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.offline_remove_confirm_title)) },
            text = { Text(stringResource(R.string.offline_remove_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        coroutineScope.launch {
                            downloader.removeAll()
                            storageBytes = downloader.storageBytes()
                        }
                    },
                ) {
                    Text(stringResource(R.string.offline_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

internal fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return when {
        mb >= 1024 -> "%.1f GB".format(mb / 1024)
        mb >= 10 -> "%.0f MB".format(mb)
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
