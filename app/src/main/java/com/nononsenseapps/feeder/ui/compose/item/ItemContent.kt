package com.nononsenseapps.feeder.ui.compose.item

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.ItemProgress
import com.nononsenseapps.feeder.model.playlists.clockText
import com.nononsenseapps.feeder.model.playlists.lengthText
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.transcript.TranscriptPanel

/**
 * The header every item view shares (article screen and Flip): type badge, feed, date and real
 * length on one line, then the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemHeader(
    kind: ItemKind,
    title: String,
    feedTitle: String,
    date: String,
    lengthSeconds: Long?,
    modifier: Modifier = Modifier,
    progress: ItemProgress? = null,
    onFeedClick: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
    titleMaxLines: Int = Int.MAX_VALUE,
    // Title text, e.g. with the spoken words highlighted in Flip
    titleContent: (@Composable () -> Unit)? = null,
) {
    val length = lengthText(kind, lengthSeconds, progress)?.label()
    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
        modifier = modifier,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(kind)
            if (feedTitle.isNotBlank()) {
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onFeedClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onFeedClick != null) Modifier.clickable(onClick = onFeedClick) else Modifier,
                )
            }
            val rest = listOfNotNull(date.ifBlank { null }, length).joinToString(" · ")
            if (rest.isNotBlank()) {
                Text(
                    text = rest,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (titleContent != null) {
            titleContent()
        } else if (title.isNotBlank()) {
            Text(
                text = title,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What the shared action row can do. A null callback hides that button, so each surface offers
 * exactly what it supports, in the same order everywhere.
 */
@Immutable
class ItemContentActions(
    val kind: ItemKind,
    val saved: Boolean,
    val read: Boolean,
    val onToggleSave: () -> Unit,
    val onToggleRead: () -> Unit,
    val onPlaylist: (() -> Unit)? = null,
    val onSummarize: (() -> Unit)? = null,
    // "Summarize", or "Show text" / "Summarizing…" while a summary is shown or loading
    val summarizeLabel: String? = null,
    val onShare: (() -> Unit)? = null,
    val onOriginal: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    // Episodes: show or hide the transcript
    val onTranscript: (() -> Unit)? = null,
    val transcriptShown: Boolean = false,
)

/**
 * The labeled action set of the article screen and Flip: Save, Playlist, Mark read, Summarize,
 * Share, Original, Delete, and Transcript for episodes. Wraps instead of hiding labels.
 * [leading] goes first (Flip's Read / Play / Watch), [trailing] last (the article's tools).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemContentActionRow(
    actions: ItemContentActions,
    modifier: Modifier = Modifier,
    leading: @Composable FlowRowScope.() -> Unit = {},
    trailing: @Composable FlowRowScope.() -> Unit = {},
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
        modifier = modifier,
    ) {
        leading()
        ItemActionButton(
            icon = saveIcon(actions.saved),
            label = stringResource(if (actions.saved) R.string.action_saved else R.string.action_save),
            onClick = actions.onToggleSave,
            selected = actions.saved,
        )
        actions.onPlaylist?.let {
            ItemActionButton(PlaylistIcon, stringResource(R.string.playlist_add_button), it)
        }
        ItemActionButton(
            icon = markReadIcon(actions.read),
            label = markReadLabel(actions.kind, actions.read),
            onClick = actions.onToggleRead,
        )
        if (actions.kind == ItemKind.PODCAST) {
            actions.onTranscript?.let {
                ItemActionButton(
                    icon = Icons.Outlined.ClosedCaption,
                    label = stringResource(R.string.transcript),
                    onClick = it,
                    selected = actions.transcriptShown,
                )
            }
        }
        actions.onSummarize?.let {
            ItemActionButton(Icons.Outlined.AutoAwesome, actions.summarizeLabel ?: stringResource(R.string.summarize), it)
        }
        actions.onShare?.let {
            ItemActionButton(Icons.Default.Share, stringResource(R.string.share), it)
        }
        actions.onOriginal?.let {
            ItemActionButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.item_action_original), it)
        }
        actions.onDelete?.let {
            ItemActionButton(DeleteIcon, stringResource(R.string.action_delete_article), it, danger = true)
        }
        trailing()
    }
}

/**
 * An episode's media block: cover art, where it is (a wavy line while it plays) and one large
 * Play / Resume / Pause button on Feeder's own podcast player. Seeking, speed and the sleep timer
 * are in the mini player and its Now playing sheet. [showTranscript] adds the transcript under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastMediaBlock(
    itemId: Long,
    link: String,
    lengthSeconds: Long?,
    modifier: Modifier = Modifier,
    image: Any? = null,
    progress: ItemProgress? = rememberItemProgress(itemId),
    showTranscript: Boolean = false,
    player: ItemPlayer = rememberItemPlayer(),
) {
    val loaded = player.isLoaded(itemId)
    val state = player.playerState
    val playing = player.isPlaying(itemId)
    val positionSec = if (loaded) state.positionMillis / 1000L else progress?.positionSeconds ?: 0L
    val lengthSec = (if (loaded && state.durationMillis > 0) state.durationMillis / 1000L else null) ?: lengthSeconds ?: progress?.lengthSeconds ?: 0L
    val fraction = if (lengthSec > 0) (positionSec.toFloat() / lengthSec).coerceIn(0f, 1f) else 0f
    val length = lengthText(ItemKind.PODCAST, lengthSec.takeIf { it > 0 }, progress)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
            modifier = Modifier.padding(FeederTokens.Space.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(FeederTokens.Radius.md))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Icon(
                        ItemKind.PODCAST.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (image != null) {
                        AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs)) {
                    when {
                        loaded && (state.isLoading || state.isBuffering) ->
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        playing ->
                            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else ->
                            LinearProgressIndicator(
                                progress = { fraction },
                                drawStopIndicator = {},
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                    }
                    Text(
                        text =
                            listOfNotNull(
                                if (lengthSec > 0) "${clockText(positionSec)} / ${clockText(lengthSec)}" else clockText(positionSec),
                                length?.takeIf { progress?.partlyPlayed == true }?.label(),
                            ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.errorMessage?.takeIf { loaded }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Button(
                onClick = { player.togglePodcast(itemId, link, progress) },
                shape = RoundedCornerShape(if (playing) 16.dp else 24.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(FeederTokens.Space.sm))
                Text(
                    if (playing) stringResource(R.string.item_action_pause) else primaryLabel(ItemKind.PODCAST, progress),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (showTranscript) {
                TranscriptPanel(
                    itemId = itemId,
                    positionMs = if (loaded) state.positionMillis.toLong() else positionSec * 1000,
                    onSeek = { ms -> if (loaded) player.seekTo(ms.toInt()) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
            }
        }
    }
}
