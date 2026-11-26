package com.nononsenseapps.feeder.ui.compose.youtube

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import java.time.Instant

/*
 * Pieces shared by the YouTube tab's screens, after Flow's video cards (MediaVideoCard):
 * thumbnails with a length badge and a watched bar, channel avatars, and the meta line.
 */

/** YouTube's own 16:9 thumbnail of a video; the feed's is 4:3 with black bars. */
fun youtubeThumbnail(
    videoId: String,
    large: Boolean = false,
): String = "https://i.ytimg.com/vi/$videoId/${if (large) "hqdefault" else "mqdefault"}.jpg"

/** Over thumbnails, like YouTube: always dark, whatever the theme. */
private val BadgeColor = Color.Black.copy(alpha = 0.8f)
private val PlayedBar = Color(0xFFFF0033)

/**
 * A video thumbnail ([url]) in its frame, with a length badge ([badge]) and how much was
 * watched ([progress]) as a red line along the bottom.
 */
@Composable
fun VideoThumbnail(
    url: String?,
    badge: String?,
    progress: Float,
    modifier: Modifier = Modifier,
    corner: Dp = FeederTokens.Radius.md,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!badge.isNullOrBlank()) {
            Surface(
                color = BadgeColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(FeederTokens.Radius.xs),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.35f)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(PlayedBar),
            )
        }
        content()
    }
}

/** A channel's picture, or its first letter when there is none. */
@Composable
fun ChannelAvatar(
    name: String,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).uppercase(),
                style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        }
    }
}

/** "12:34", "LIVE", or null when the length is not known. */
@Composable
fun durationBadge(row: VideoRow): String? =
    when {
        row.isLive -> stringResource(R.string.youtube_live)
        row.duration > 0 -> VideoPositions.formatClock(row.duration)
        else -> null
    }

/** "1.2M views · 3 days ago" (views left out until they are known). */
@Composable
fun viewsAndAge(row: VideoRow): String =
    listOfNotNull(
        row.views?.let { stringResource(R.string.video_views, VideoDetails.compactCount(it)) },
        ageText(row.item.pubDate?.toInstant() ?: row.item.sortTime),
    ).joinToString(" · ")

fun ageText(time: Instant): String =
    DateUtils
        .getRelativeTimeSpanString(time.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        .toString()
