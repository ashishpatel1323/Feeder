package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Enclosure
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.VideoPhase
import com.nononsenseapps.feeder.ui.compose.settings.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.io.File

/**
 * Download episode, for podcast articles (an audio enclosure): saves the episode on the phone
 * for the Downloads screen's Audio tab, with its progress, Pause/Resume, and Remove once saved.
 * Shows nothing for other articles.
 */
@Composable
fun OfflineAudioPanel(
    itemId: Long,
    enclosure: Enclosure,
    modifier: Modifier = Modifier,
) {
    if (!enclosure.present || !enclosure.type.startsWith("audio/")) return
    val di = LocalDI.current
    val videos: OfflineVideos by di.instance()
    val repository: Repository by di.instance()
    val coroutineScope = rememberCoroutineScope()

    val jobs by videos.jobs.collectAsState()
    val changes by videos.changes.collectAsState()
    val offlineMode by repository.offlineMode.collectAsState()
    val saved by produceState<File?>(null, itemId, changes) {
        value = withContext(Dispatchers.IO) { videos.savedAudio(itemId) }
    }
    val job = jobs[itemId]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        val file = saved
        when {
            file != null ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.downloads_episode_saved, formatBytes(file.length())),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { coroutineScope.launch { videos.remove(itemId) } }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(stringResource(R.string.offline_video_remove), modifier = Modifier.padding(start = 4.dp))
                    }
                }

            job != null && job.phase == VideoPhase.ERROR -> {
                Text(
                    stringResource(R.string.offline_video_failed, job.error ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { videos.retry(itemId) }, enabled = !offlineMode) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.offline_video_retry), modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { videos.cancel(itemId) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }

            job != null -> {
                val paused = job.phase == VideoPhase.PAUSED
                LinearProgressIndicator(progress = { job.overall / 100f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (paused) {
                            stringResource(R.string.downloads_paused_percent, job.overall.toInt())
                        } else {
                            stringResource(R.string.downloads_episode_saving, job.overall.toInt())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (paused) {
                        TextButton(onClick = { videos.resume(itemId) }, enabled = !offlineMode) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(stringResource(R.string.downloads_resume), modifier = Modifier.padding(start = 4.dp))
                        }
                    } else {
                        TextButton(onClick = { videos.pause(itemId) }) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Text(stringResource(R.string.downloads_pause), modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    TextButton(onClick = { videos.cancel(itemId) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.offline_stop), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            else ->
                OutlinedButton(onClick = { videos.saveAudio(itemId, enclosure.link) }, enabled = !offlineMode) {
                    Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                    Text(stringResource(R.string.downloads_save_episode), modifier = Modifier.padding(start = 8.dp))
                }
        }
    }
}
