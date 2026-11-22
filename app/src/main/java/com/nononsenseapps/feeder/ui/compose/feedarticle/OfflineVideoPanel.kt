package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.SavedVideo
import com.nononsenseapps.feeder.model.offline.VideoPhase
import com.nononsenseapps.feeder.model.offline.isYouTubeLink
import com.nononsenseapps.feeder.ui.compose.settings.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * Save video offline, for YouTube articles: a button that saves the video on the phone
 * (yt-dlp), its progress, and once saved a player for the saved copy with Remove. Shows
 * nothing for other articles.
 */
@Composable
fun OfflineVideoPanel(
    itemId: Long,
    link: String?,
    modifier: Modifier = Modifier,
    showSaveButton: Boolean = true,
) {
    if (link == null || !isYouTubeLink(link)) return
    val di = LocalDI.current
    val videos: OfflineVideos by di.instance()
    val repository: Repository by di.instance()
    val coroutineScope = rememberCoroutineScope()

    val jobs by videos.jobs.collectAsState()
    val changes by videos.changes.collectAsState()
    val offlineMode by repository.offlineMode.collectAsState()
    val saved by produceState<SavedVideo?>(null, itemId, changes) {
        value = withContext(Dispatchers.IO) { videos.savedFiles(itemId) }
    }
    val job = jobs[itemId]

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        val current = saved
        when {
            current != null -> {
                SavedVideoPlayer(current)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_video_saved, formatBytes(current.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { coroutineScope.launch { videos.remove(itemId) } }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(stringResource(R.string.offline_video_remove), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            job != null && job.phase == VideoPhase.ERROR -> {
                Text(
                    stringResource(R.string.offline_video_failed, job.error ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { videos.save(itemId, link) }, enabled = !offlineMode) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.offline_video_retry), modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { videos.dismissError(itemId) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }

            job != null && job.phase == VideoPhase.PAUSED -> {
                LinearProgressIndicator(progress = { job.overall / 100f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.downloads_paused_percent, job.overall.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { videos.resume(itemId) }, enabled = !offlineMode) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.downloads_resume), modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = { videos.cancel(itemId) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.offline_stop), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            job != null -> {
                val label =
                    when (job.phase) {
                        VideoPhase.PREPARING -> stringResource(R.string.offline_video_preparing)
                        VideoPhase.VIDEO -> stringResource(R.string.offline_video_saving_video, job.percent.toInt())
                        VideoPhase.AUDIO -> stringResource(R.string.offline_video_saving_audio, job.percent.toInt())
                        else -> stringResource(R.string.offline_video_saving_subtitles)
                    }
                if (job.phase == VideoPhase.VIDEO || job.phase == VideoPhase.AUDIO) {
                    LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { videos.cancel(itemId) }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.offline_stop), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            showSaveButton -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { videos.save(itemId, link) }, enabled = !offlineMode) {
                        Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                        Text(stringResource(R.string.offline_video_save), modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.offline_video_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Plays a saved video: picture and sound files together, with saved subtitles. */
@OptIn(UnstableApi::class)
@Composable
private fun SavedVideoPlayer(
    saved: SavedVideo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player =
        remember(saved) {
            ExoPlayer.Builder(context).build().apply {
                val factory = DefaultMediaSourceFactory(context)
                val videoItem =
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(saved.video))
                        .setSubtitleConfigurations(
                            saved.subtitles.map { file ->
                                MediaItem.SubtitleConfiguration
                                    .Builder(Uri.fromFile(file))
                                    .setMimeType(MimeTypes.TEXT_VTT)
                                    .setLanguage(file.name.split('.').getOrNull(2) ?: "en")
                                    .build()
                            },
                        ).build()
                val videoSource = factory.createMediaSource(videoItem)
                val source =
                    saved.audio?.let { audio ->
                        MergingMediaSource(videoSource, factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(audio))))
                    } ?: videoSource
                setMediaSource(source)
                prepare()
            }
        }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowSubtitleButton(true)
            }
        },
        update = { it.player = player },
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
    )
}
