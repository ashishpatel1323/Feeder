package com.nononsenseapps.feeder.ui.compose.youtube

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.youtube.PlaybackState
import com.nononsenseapps.feeder.model.youtube.YouTubePlayback
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.delay
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * Flow's mini player, above the bottom bar while a YouTube video plays (or waits, paused) after
 * its page was collapsed: the video itself, title and channel, play/pause and close, with a thin
 * progress line on top. Tapping it opens the video page again.
 */
@OptIn(UnstableApi::class)
@Composable
fun YouTubeMiniPlayer(
    onOpen: (PlaybackState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback: YouTubePlayback by LocalDI.current.instance()
    val state by playback.state.collectAsState()
    val player by playback.player.collectAsState()
    val current = state ?: return
    val exo = player ?: return

    var playing by remember { mutableStateOf(exo.playWhenReady) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(exo) {
        while (true) {
            playing = exo.playWhenReady
            val d = exo.duration
            progress = if (d > 0) (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }

    Surface(
        onClick = { onOpen(current) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.sm, vertical = FeederTokens.Space.xs),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                drawStopIndicator = {},
                gapSize = 0.dp,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier = Modifier.height(64.dp),
            ) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { it.player = exo },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                    )
                    if (current.height == 0 && current.details.thumbnail != null) {
                        // Sound only: the video's picture
                        coil3.compose.AsyncImage(
                            model = current.details.thumbnail,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        current.title.ifBlank { current.details.title },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.details.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { if (exo.playWhenReady) exo.pause() else exo.play() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    )
                }
                IconButton(onClick = { playback.stop() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.youtube_close_player), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
