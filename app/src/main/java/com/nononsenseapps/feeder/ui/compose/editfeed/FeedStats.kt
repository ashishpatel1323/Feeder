package com.nononsenseapps.feeder.ui.compose.editfeed

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.FeedItemForStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val STATS_DAYS = 30
private const val DAY_MS = 86_400_000L

/**
 * Per-feed numbers for the feed page, from the articles stored on this device. The same numbers
 * as the stats panel on the web app's Manage feeds page.
 */
@Immutable
data class FeedStats(
    /** New articles per day for the last [STATS_DAYS] days, oldest first. */
    val perDay: List<Int> = List(STATS_DAYS) { 0 },
    /** Articles published in the window. */
    val recent: Int = 0,
    /** Articles per day: recent articles divided by the days they span (at most the window). */
    val rate: Double = 0.0,
    /** Articles published in the last 7 days. */
    val lastWeek: Int = 0,
    /** Of the articles in the window, how many were read. */
    val recentRead: Int = 0,
    val lastArticle: Instant? = null,
    val unread: Int = 0,
    val saved: Int = 0,
    /** Average words per article (full article when loaded). */
    val avgWords: Int = 0,
    val stored: Int = 0,
    /** First time this device saw an article of this feed. */
    val firstSeen: Instant? = null,
    /** Articles by weekday, Monday first. */
    val byWeekday: List<Int> = List(7) { 0 },
)

fun computeFeedStats(
    rows: List<FeedItemForStats>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): FeedStats {
    // Day buckets end at the end of today (local time)
    val end =
        now
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    val start = end - STATS_DAYS * DAY_MS
    val nowMs = now.toEpochMilli()
    val perDay = IntArray(STATS_DAYS)
    val byWeekday = IntArray(7)
    var recent = 0
    var lastWeek = 0
    var recentRead = 0
    var unread = 0
    var saved = 0
    var words = 0L
    var lastArticle: Long? = null
    var firstSeen: Long? = null
    var oldestRecent = Long.MAX_VALUE

    for (row in rows) {
        val t = row.primarySortTime.toEpochMilli()
        if (row.readTime == null) unread++
        if (row.bookmarked) saved++
        words += if (row.wordCountFull > 0) row.wordCountFull else row.wordCount
        if (lastArticle == null || t > lastArticle) lastArticle = t
        val seen = row.firstSyncedTime.toEpochMilli()
        if (seen > 0 && (firstSeen == null || seen < firstSeen)) firstSeen = seen
        byWeekday[row.primarySortTime.atZone(zone).dayOfWeek.value - 1]++
        if (t in start until end) {
            perDay[((t - start) / DAY_MS).toInt()]++
            recent++
            if (t < oldestRecent) oldestRecent = t
            if (row.readTime != null) recentRead++
            if (t >= nowMs - 7 * DAY_MS) lastWeek++
        }
    }
    val span =
        if (recent == 0) {
            1.0
        } else {
            min(STATS_DAYS.toDouble(), max(1.0, (nowMs - oldestRecent).toDouble() / DAY_MS))
        }
    return FeedStats(
        perDay = perDay.toList(),
        recent = recent,
        rate = recent / span,
        lastWeek = lastWeek,
        recentRead = recentRead,
        lastArticle = lastArticle?.let { Instant.ofEpochMilli(it) },
        unread = unread,
        saved = saved,
        avgWords = if (rows.isEmpty()) 0 else (words / rows.size).toInt(),
        stored = rows.size,
        firstSeen = firstSeen?.let { Instant.ofEpochMilli(it) },
        byWeekday = byWeekday.toList(),
    )
}

@Composable
private fun formatRate(perDay: Double): String =
    when {
        perDay >= 10 -> stringResource(R.string.feed_stats_per_day, perDay.roundToInt().toString())
        perDay >= 1 -> stringResource(R.string.feed_stats_per_day, String.format(Locale.getDefault(), "%.1f", perDay))
        perDay == 0.0 -> stringResource(R.string.feed_stats_none_in_window, STATS_DAYS)
        else -> stringResource(R.string.feed_stats_per_week, String.format(Locale.getDefault(), "%.1f", perDay * 7))
    }

private fun timeAgo(
    t: Instant?,
    never: String,
): String =
    if (t == null || t.toEpochMilli() <= 0) {
        never
    } else {
        DateUtils
            .getRelativeTimeSpanString(
                t.toEpochMilli(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
    }

/**
 * The top of the feed page: the feed's stats, when it last checked, and a button to its articles.
 */
@Composable
fun FeedStatsSection(
    stats: FeedStats,
    lastSync: Instant?,
    onShowArticles: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val never = stringResource(R.string.feed_stats_never)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.feed_stats_checked, timeAgo(lastSync, never)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(onClick = onShowArticles, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (stats.unread > 0) {
                        stringResource(R.string.feed_stats_show_articles_unread, stats.unread)
                    } else {
                        stringResource(R.string.feed_stats_show_articles)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.delete_feed), maxLines = 1)
                }
            }
        }

        Text(
            text = stringResource(R.string.feed_stats_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        val readPct = if (stats.recent > 0) (stats.recentRead * 100.0 / stats.recent).roundToInt() else null
        val since =
            stats.firstSeen?.let {
                DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .format(it.atZone(ZoneId.systemDefault()))
            } ?: "—"
        val tiles =
            listOf(
                Triple(
                    stringResource(R.string.feed_stats_articles_per_day),
                    formatRate(stats.rate),
                    stringResource(R.string.feed_stats_average_last_week, STATS_DAYS, stats.lastWeek),
                ),
                Triple(
                    stringResource(R.string.feed_stats_last_new_article),
                    timeAgo(stats.lastArticle, never),
                    null,
                ),
                Triple(
                    stringResource(R.string.feed_stats_you_read),
                    readPct?.let { "$it%" } ?: "—",
                    stringResource(R.string.feed_stats_read_of, stats.recentRead, stats.recent, STATS_DAYS),
                ),
                Triple(
                    stringResource(R.string.feed_stats_unread),
                    stats.unread.toString(),
                    pluralStringResource(R.plurals.feed_stats_saved, stats.saved, stats.saved),
                ),
                Triple(
                    stringResource(R.string.feed_stats_average_length),
                    if (stats.stored > 0) stringResource(R.string.feed_stats_minutes, max(1, (stats.avgWords / 220.0).roundToInt())) else "—",
                    if (stats.stored > 0) stringResource(R.string.feed_stats_words, stats.avgWords) else null,
                ),
                Triple(
                    stringResource(R.string.feed_stats_on_device_since),
                    since,
                    pluralStringResource(R.plurals.feed_stats_stored, stats.stored, stats.stored),
                ),
            )
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, sub) ->
                    StatTile(label = label, value = value, sub = sub, modifier = Modifier.weight(1f))
                }
            }
        }

        StatCard {
            Text(
                text = stringResource(R.string.feed_stats_new_articles_chart, STATS_DAYS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Bars(values = stats.perDay, gap = 2.dp, modifier = Modifier.fillMaxWidth().height(64.dp))
        }

        StatCard {
            Text(
                text = stringResource(R.string.feed_stats_when_it_posts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Bars(values = stats.byWeekday, gap = 12.dp, modifier = Modifier.fillMaxWidth().height(48.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(d, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Bars(
    values: List<Int>,
    gap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        if (values.isEmpty()) return@Canvas
        val top = values.max().coerceAtLeast(1)
        val slot = size.width / values.size
        val g = gap.toPx().coerceAtMost(slot * 0.6f)
        values.forEachIndexed { i, v ->
            val h = if (v == 0) 2f else max(3f, v.toFloat() / top * size.height)
            drawRoundRect(
                color = if (v == 0) empty else color,
                topLeft = Offset(i * slot + g / 2, size.height - h),
                size = Size(slot - g, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}
