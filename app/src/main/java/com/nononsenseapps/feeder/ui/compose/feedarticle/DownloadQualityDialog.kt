package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.youtube.DownloadOption
import com.nononsenseapps.feeder.ui.compose.settings.formatBytes
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

private sealed interface Qualities {
    data object Loading : Qualities

    data class Ready(
        val options: List<DownloadOption>,
    ) : Qualities

    data class Failed(
        val message: String,
    ) : Qualities
}

/**
 * Download video: pick a quality (codec, height, rough size) before the video is saved, looked
 * up with yt-dlp. When the lookup fails, the usual 720p download is still one tap away.
 */
@Composable
fun DownloadQualityDialog(
    link: String,
    onPick: (formatId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val offlineVideos: OfflineVideos by LocalDI.current.instance()
    val qualities by produceState<Qualities>(Qualities.Loading, link) {
        value =
            try {
                val options = offlineVideos.videoDetails(link).downloadOptions
                if (options.isEmpty()) Qualities.Failed("no qualities found") else Qualities.Ready(options)
            } catch (e: Exception) {
                Qualities.Failed(e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e.toString())
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_pick_quality)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.downloads_pick_quality_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val q = qualities) {
                    Qualities.Loading ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(stringResource(R.string.downloads_quality_loading))
                        }

                    is Qualities.Failed ->
                        Text(
                            stringResource(R.string.downloads_quality_failed, q.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )

                    is Qualities.Ready ->
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 420.dp),
                        ) {
                            items(q.options, key = { it.formatId }) { option ->
                                QualityRow(option) { onPick(option.formatId) }
                            }
                        }
                }
            }
        },
        confirmButton = {
            if (qualities is Qualities.Failed) {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.downloads_quality_default)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun QualityRow(
    option: DownloadOption,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${option.codec} ${option.height}p", style = MaterialTheme.typography.titleMedium)
            if (option.approxBytes > 0) {
                Text(
                    stringResource(R.string.downloads_quality_size, formatBytes(option.approxBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (option.height >= 720) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.downloads_quality_hd),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
