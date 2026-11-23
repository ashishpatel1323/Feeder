package com.nononsenseapps.feeder.ui.compose.item

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.ItemProgress
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.lengthText
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/** What one row shows about an article, episode or video. The same on every list. */
@Immutable
data class ItemRowModel(
    val id: Long,
    val title: String,
    val feedTitle: String,
    val age: String,
    val kind: ItemKind,
    // Real length: episode/video duration, or reading time for an article. Null = not known
    val lengthSeconds: Long?,
    val read: Boolean,
    val saved: Boolean,
    // A URL string or a local File; null = the type's icon on a plain tile
    val image: Any?,
    val progress: ItemProgress? = null,
)

/** Where an episode or video was left: the playlist/podcast positions, then YouTube's. */
@Composable
fun rememberItemProgress(
    itemId: Long,
    videoKey: String = "",
): ItemProgress? {
    val di = LocalDI.current
    val playlistStore: PlaylistStore by di.instance()
    val videoPositions: VideoPositions by di.instance()
    val positions by playlistStore.positions.collectAsState()
    val videos by videoPositions.all.collectAsState()
    positions[itemId]?.let { (pos, len) -> if (pos > 0) return ItemProgress(pos, len) }
    if (videoKey.isNotEmpty()) {
        videos[videoKey]?.let { return ItemProgress(it.t.toLong(), it.d.toLong()) }
    }
    return null
}

/** Actions every row offers. Null hides one (for example Delete where deleting is not offered). */
@Immutable
class ItemRowActions(
    val onPrimary: () -> Unit,
    val onToggleSave: () -> Unit,
    val onPlaylist: (() -> Unit)?,
    val onToggleRead: () -> Unit,
    val onDelete: (() -> Unit)?,
)

/**
 * The one item row: thumbnail on the left with the length badge (and progress) on it, the title,
 * a meta line (type, feed, age, length), then always-visible labeled buttons. Surface-specific
 * buttons go in [extras], after the shared ones.
 *
 * [onOpen] null = the caller handles taps on the row itself (the article list, for swipes).
 * [onContentLayout] gets the top and height (px) of the thumbnail and text, so the list's swipe
 * layer can cover them and leave the buttons free.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    model: ItemRowModel,
    actions: ItemRowActions,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    highlighted: Boolean = false,
    // Shown in place of the primary label, e.g. "Pause" while it plays
    primaryLabel: String? = null,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onContentLayout: ((top: Int, height: Int) -> Unit)? = null,
    extras: @Composable FlowRowScope.() -> Unit = {},
) {
    val length = lengthText(model.kind, model.lengthSeconds, model.progress)
    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.md),
    ) {
        Box(
            modifier =
                if (onContentLayout != null) {
                    Modifier.onGloballyPositioned { onContentLayout(it.positionInParent().y.toInt(), it.size.height) }
                } else {
                    Modifier
                },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            ) {
                ItemThumbnail(model = model)
                Column(
                    verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = model.title.ifBlank { stringResource(R.string.item_untitled) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (model.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ItemMetaLine(
                        kind = model.kind,
                        parts = listOfNotNull(model.feedTitle.ifBlank { null }, model.age.ifBlank { null }, length?.label()),
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ItemActionButton(
                icon = primaryIcon ?: primaryIcon(model.kind),
                label = primaryLabel ?: primaryLabel(model.kind, model.progress),
                onClick = actions.onPrimary,
                primary = true,
            )
            ItemActionButton(
                icon = saveIcon(model.saved),
                label = stringResource(if (model.saved) R.string.action_saved else R.string.action_save),
                onClick = actions.onToggleSave,
            )
            actions.onPlaylist?.let { onPlaylist ->
                ItemActionButton(
                    icon = PlaylistIcon,
                    label = stringResource(R.string.playlist_add_button),
                    onClick = onPlaylist,
                )
            }
            ItemActionButton(
                icon = markReadIcon(model.read),
                label = markReadLabel(model.kind, model.read),
                onClick = actions.onToggleRead,
            )
            actions.onDelete?.let { onDelete ->
                ItemActionButton(
                    icon = DeleteIcon,
                    label = stringResource(R.string.action_delete_article),
                    onClick = onDelete,
                    danger = true,
                )
            }
            extras()
        }
    }
}

/** Type badge, then feed · age · length, wrapping when narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemMetaLine(
    kind: ItemKind,
    parts: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        TypeBadge(kind)
        if (parts.isNotEmpty()) {
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 16:10 rounded thumbnail with the length badge and, when partly played, a progress bar. */
@Composable
fun ItemThumbnail(
    model: ItemRowModel,
    modifier: Modifier = Modifier,
) {
    val badge = lengthBadgeText(model.kind, model.lengthSeconds)
    Box(
        modifier =
            modifier
                .width(THUMB_WIDTH)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(FeederTokens.Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            model.kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(28.dp),
        )
        if (model.image != null) {
            AsyncImage(
                model = model.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(FeederTokens.Space.xs)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(FeederTokens.Radius.xs))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        val progress = model.progress
        if (progress != null && progress.partlyPlayed && progress.lengthSeconds > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private val THUMB_WIDTH = 112.dp
