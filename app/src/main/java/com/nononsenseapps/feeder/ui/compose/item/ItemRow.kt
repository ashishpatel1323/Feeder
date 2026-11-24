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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
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

/**
 * What a row can do. The row itself shows only the main action (Play/Resume/Watch for episodes and
 * videos); the rest are reached by swipe, multi-select and TalkBack actions in lists, and as labeled
 * buttons on the article screen. Null hides one (for example Delete where deleting is not offered).
 */
@Immutable
class ItemRowActions(
    val onPrimary: () -> Unit,
    val onToggleSave: () -> Unit,
    val onPlaylist: (() -> Unit)?,
    val onToggleRead: () -> Unit,
    val onDelete: (() -> Unit)?,
)

/**
 * The one item row, a Material 3 list item: square thumbnail with the length (and progress) on it,
 * a two-line title, one meta line (feed, age, length), and for episodes and videos one round
 * Play/Resume button at the end. Surface-specific buttons (playlist reordering, Delete download)
 * go in [extras], a compact line under the text.
 *
 * [onOpen] null = the caller handles taps on the row itself (the article list, for swipes).
 * [onContentLayout] gets the left, top, width and height (px) of the thumbnail and text, so the
 * list's swipe layer can cover them and leave the Play button free.
 * [accessibilityActions] adds Save / Playlist / Mark read / Delete as TalkBack actions; the article
 * list adds its own instead.
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
    onContentLayout: ((left: Int, top: Int, width: Int, height: Int) -> Unit)? = null,
    accessibilityActions: Boolean = true,
    // After the Play button, e.g. a drag handle
    trailing: @Composable () -> Unit = {},
    extras: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val length = lengthText(model.kind, model.lengthSeconds, model.progress)
    val mainLabel = primaryLabel ?: primaryLabel(model.kind, model.progress)
    val saveLabel = stringResource(if (model.saved) R.string.action_saved else R.string.action_save)
    val playlistLabel = stringResource(R.string.playlist_add_button)
    val readLabel = markReadLabel(model.kind, model.read)
    val deleteLabel = stringResource(R.string.action_delete_article)
    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .then(
                    if (accessibilityActions) {
                        Modifier.semantics {
                            customActions =
                                listOfNotNull(
                                    CustomAccessibilityAction(saveLabel) {
                                        actions.onToggleSave()
                                        true
                                    },
                                    actions.onPlaylist?.let {
                                        CustomAccessibilityAction(playlistLabel) {
                                            it()
                                            true
                                        }
                                    },
                                    CustomAccessibilityAction(readLabel) {
                                        actions.onToggleRead()
                                        true
                                    },
                                    actions.onDelete?.let {
                                        CustomAccessibilityAction(deleteLabel) {
                                            it()
                                            true
                                        }
                                    },
                                )
                        }
                    } else {
                        Modifier
                    },
                ).padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.sm, top = FeederTokens.Space.md, bottom = FeederTokens.Space.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.lg),
                modifier =
                    Modifier
                        .weight(1f)
                        .then(
                            if (onContentLayout != null) {
                                Modifier.onGloballyPositioned {
                                    val p = it.positionInParent()
                                    onContentLayout(p.x.toInt(), p.y.toInt(), it.size.width, it.size.height)
                                }
                            } else {
                                Modifier
                            },
                        ).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            ) {
                ItemThumbnail(model = model)
                Column(
                    verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = model.title.ifBlank { stringResource(R.string.item_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (model.read) FontWeight.Normal else FontWeight.Medium,
                        color = if (model.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta =
                        listOfNotNull(
                            model.kind.takeIf { it == ItemKind.ARTICLE }?.displayName(),
                            // First, so a long feed name never cuts it off
                            percentPlayedLabel(model.kind, model.progress, model.lengthSeconds),
                            model.feedTitle.ifBlank { null },
                            model.age.ifBlank { null },
                            length?.label(),
                        )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (model.saved) {
                            Icon(
                                saveIcon(true),
                                contentDescription = stringResource(R.string.action_saved),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp).padding(end = 2.dp),
                            )
                        }
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (model.kind != ItemKind.ARTICLE) {
                PlainTooltipBox(tooltip = { Text(mainLabel) }) {
                    FilledTonalIconButton(
                        onClick = actions.onPrimary,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(primaryIcon ?: Icons.Default.PlayArrow, contentDescription = mainLabel)
                    }
                }
            } else {
                // Keeps titles aligned with media rows
                Spacer(Modifier.width(FeederTokens.Space.sm))
            }
            trailing()
        }
        if (extras != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
                modifier = Modifier.fillMaxWidth().padding(start = THUMB_SIZE + FeederTokens.Space.lg),
            ) {
                extras()
            }
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

/** Square rounded thumbnail with the length badge and, when partly played, a progress bar. */
@Composable
fun ItemThumbnail(
    model: ItemRowModel,
    modifier: Modifier = Modifier,
) {
    val badge = lengthBadgeText(model.kind, model.lengthSeconds)
    Box(
        modifier =
            modifier
                .size(THUMB_SIZE)
                .clip(RoundedCornerShape(FeederTokens.Radius.md))
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

private val THUMB_SIZE = 72.dp
