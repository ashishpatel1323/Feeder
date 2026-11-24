package com.nononsenseapps.feeder.ui.compose.item

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.ItemProgress
import com.nononsenseapps.feeder.model.playlists.LengthText
import com.nononsenseapps.feeder.model.playlists.badgeMinutes
import com.nononsenseapps.feeder.model.playlists.clockText
import com.nononsenseapps.feeder.model.playlists.percentPlayed
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/** Background and text colour of an item type's badge, from the theme. */
@Immutable
data class KindColors(
    val container: Color,
    val content: Color,
)

@Composable
fun ItemKind.colors(): KindColors =
    when (this) {
        ItemKind.ARTICLE -> KindColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ItemKind.PODCAST -> KindColors(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ItemKind.YOUTUBE -> KindColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }

val ItemKind.icon: ImageVector
    get() =
        when (this) {
            ItemKind.ARTICLE -> Icons.AutoMirrored.Filled.Article
            ItemKind.PODCAST -> Icons.Default.Podcasts
            ItemKind.YOUTUBE -> Icons.Default.SmartDisplay
        }

@Composable
fun ItemKind.displayName(): String =
    stringResource(
        when (this) {
            ItemKind.ARTICLE -> R.string.item_kind_article
            ItemKind.PODCAST -> R.string.item_kind_podcast
            ItemKind.YOUTUBE -> R.string.item_kind_video
        },
    )

/** "Article", "Podcast" or "Video" with its icon, tinted per type. */
@Composable
fun TypeBadge(
    kind: ItemKind,
    modifier: Modifier = Modifier,
) {
    val colors = kind.colors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            modifier
                .background(colors.container, RoundedCornerShape(FeederTokens.Radius.xs))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Icon(kind.icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(12.dp))
        Text(
            text = kind.displayName(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
        )
    }
}

/** "4 min read", "24 min", "19 min left". */
@Composable
fun LengthText.label(): String =
    when (this) {
        is LengthText.Read -> stringResource(R.string.item_length_read, minutes)
        is LengthText.Total -> stringResource(R.string.item_length_total, minutes)
        is LengthText.Left -> stringResource(R.string.item_length_left, minutes)
    }

/** "42% played" for an episode, "42% watched" for a video; null for articles or when not started. */
@Composable
fun percentPlayedLabel(
    kind: ItemKind,
    progress: ItemProgress?,
    lengthSeconds: Long?,
): String? {
    if (kind == ItemKind.ARTICLE) return null
    val percent = percentPlayed(progress, lengthSeconds) ?: return null
    return stringResource(if (kind == ItemKind.YOUTUBE) R.string.item_percent_watched else R.string.item_percent_played, percent)
}

/** The badge on a thumbnail: "mm:ss" for audio and video, "N min" for articles. */
@Composable
fun lengthBadgeText(
    kind: ItemKind,
    seconds: Long?,
): String? {
    if (seconds == null || seconds <= 0) return null
    return if (kind == ItemKind.ARTICLE) stringResource(R.string.item_badge_minutes, badgeMinutes(seconds)) else clockText(seconds)
}

/** "5 min ago", "Yesterday", "Mar 3": how long ago [epochMillis] was. Empty when not known. */
fun ageText(
    epochMillis: Long?,
    now: Long = System.currentTimeMillis(),
): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    return DateUtils
        .getRelativeTimeSpanString(epochMillis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        .toString()
}

/** Label of the type-aware primary button: Read, Play / Resume, Watch. */
@Composable
fun primaryLabel(
    kind: ItemKind,
    progress: ItemProgress?,
): String =
    stringResource(
        when (kind) {
            ItemKind.ARTICLE -> R.string.item_action_read
            ItemKind.PODCAST -> if (progress?.partlyPlayed == true) R.string.item_action_resume else R.string.item_action_play
            ItemKind.YOUTUBE -> R.string.item_action_watch
        },
    )

fun primaryIcon(kind: ItemKind): ImageVector =
    when (kind) {
        ItemKind.ARTICLE -> Icons.AutoMirrored.Filled.Article
        ItemKind.PODCAST -> Icons.Default.Headphones
        ItemKind.YOUTUBE -> Icons.Default.PlayArrow
    }

/** "Mark read", "Mark played" or "Mark watched"; "Mark unread" once it is read. */
@Composable
fun markReadLabel(
    kind: ItemKind,
    read: Boolean,
): String =
    stringResource(
        when {
            read -> R.string.item_action_mark_unread
            kind == ItemKind.PODCAST -> R.string.item_action_mark_played
            kind == ItemKind.YOUTUBE -> R.string.item_action_mark_watched
            else -> R.string.item_action_mark_read
        },
    )

fun markReadIcon(read: Boolean): ImageVector = if (read) Icons.Outlined.MarkEmailUnread else Icons.Outlined.TaskAlt

fun saveIcon(saved: Boolean): ImageVector = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder

val PlaylistIcon: ImageVector get() = Icons.AutoMirrored.Filled.PlaylistAdd

val DeleteIcon: ImageVector get() = Icons.Outlined.Delete

/**
 * A labeled action: icon plus text, always visible. [primary] is the filled tonal one (one per
 * item), [danger] uses the error colour (Delete).
 */
@Composable
fun ItemActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(start = FeederTokens.Space.md, end = FeederTokens.Space.md + 2.dp)
    val shape = RoundedCornerShape(FeederTokens.Radius.sm)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val sized = modifier.height(FeederTokens.controlHeight).defaultMinSize(minWidth = 1.dp)
    if (primary || selected) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = sized,
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors =
                if (danger) {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
            modifier = sized,
        ) { content() }
    }
}
