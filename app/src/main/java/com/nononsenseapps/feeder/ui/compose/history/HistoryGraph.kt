package com.nononsenseapps.feeder.ui.compose.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.audiobooks.Chapter
import com.nononsenseapps.feeder.model.history.CentralHistory
import com.nononsenseapps.feeder.model.history.HistoryEvent
import com.nononsenseapps.feeder.model.history.HistoryGraph
import com.nononsenseapps.feeder.model.history.HistoryStats
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.model.history.ProgressPoint
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The card at the top of an item's History: minutes a day over the last 14 days with this week's
 * total and the streak, then how far through it is over time (a ZIM site has no end, so it shows
 * pages visited a day instead). Everything comes from the history already recorded. The same as
 * the web app's components/HistoryGraph.tsx.
 */
@Composable
fun HistoryGraphCard(
    c: String,
    i: String,
    modifier: Modifier = Modifier,
    /** An audiobook's chapters and length, for the chapter bar under the progress line */
    chapters: List<Chapter> = emptyList(),
    bookLengthMs: Long = 0,
) {
    val context = LocalContext.current
    val history = remember { ItemHistory.get(context) }
    val all by history.events.collectAsState()
    val events = remember(all, c, i) { all.filter { it.c == c && it.i == i } }
    if (events.isEmpty()) return
    val g = remember(events) { HistoryStats.graphOf(events) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = FeederTokens.Space.sm)
            .clip(RoundedCornerShape(FeederTokens.Radius.xl))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(minutesText(g.week), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(if (c == ItemHistory.AUDIOBOOK) R.string.history_graph_listening_week else R.string.history_graph_reading_week),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = FeederTokens.Space.xs).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (g.streak > 1) {
                Text(
                    stringResource(R.string.history_graph_streak, g.streak),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.xs),
                )
            }
        }
        Label(stringResource(R.string.history_graph_minutes_label))
        Bars(g, g.minutes, height = 76) { minutesText(it) }
        if (c == ItemHistory.ZIM) {
            ZimPages(g, i)
        } else {
            Progress(c, g, events, chapters, bookLengthMs)
        }
    }
}

@Composable
private fun minutesText(m: Int): String =
    when {
        m < 60 -> stringResource(R.string.history_graph_minutes, m)
        m % 60 == 0 -> stringResource(R.string.history_graph_hours, m / 60)
        else -> stringResource(R.string.history_graph_hours_minutes, m / 60, m % 60)
    }

@Composable
private fun Label(
    text: String,
    end: String? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = FeederTokens.Space.md, bottom = FeederTokens.Space.xs)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (end != null) {
            Text(end, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = FeederTokens.Space.sm))
        }
    }
}

@Composable
private fun Foot(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = FeederTokens.Space.sm))
}

@Composable
private fun Bars(
    g: HistoryGraph,
    values: List<Int>,
    height: Int,
    what: @Composable (Int) -> String,
) {
    val max = max(1, values.maxOrNull() ?: 0)
    val top = values.indexOf(max)
    val last = values.size - 1
    val weekday = remember { SimpleDateFormat("EEEEE", Locale.getDefault()) }
    val dayName = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val texts = values.map { what(it) }
    val summary = values.indices.joinToString { "${dayName.format(Date(g.days[it]))}: ${texts[it]}" }
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = summary },
    ) {
        values.forEachIndexed { k, v ->
            val today = k == last
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (v > 0 && k == top) {
                        Text(
                            if (values === g.minutes) "${v}m" else "$v",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.align(if (today) Alignment.TopEnd else Alignment.TopCenter),
                        )
                    }
                    Canvas(
                        Modifier
                            .widthIn(max = 22.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(top = 14.dp),
                    ) {
                        val h = if (v > 0) max(6.dp.toPx(), size.height * v / max) else 3.dp.toPx()
                        val r = minOf(FeederTokens.Radius.sm.toPx(), size.width / 2, h / 2)
                        drawRoundRect(
                            color = if (v > 0) bar.copy(alpha = if (today) 1f else 0.55f) else empty,
                            topLeft = Offset(0f, size.height - h),
                            size = Size(size.width, h),
                            cornerRadius = CornerRadius(r, r),
                        )
                    }
                }
                Text(
                    weekday.format(Date(g.days[k])),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (today) FontWeight.Bold else null,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Progress(
    c: String,
    g: HistoryGraph,
    events: List<HistoryEvent>,
    chapters: List<Chapter>,
    bookLengthMs: Long,
) {
    Column(Modifier.fillMaxWidth()) {
        val f = g.f
        if (f == null || g.progress.isEmpty()) {
            Label(stringResource(R.string.history_graph_progress_later))
            return@Column
        }
        val now = remember(events) { System.currentTimeMillis() }
        val from = g.days.first()
        val to = max(now, g.progress.last().at)
        var lo = g.progress.minOf { it.f }
        var hi = g.progress.maxOf { it.f }
        if (hi - lo < 0.02) {
            lo = (lo - 0.01).coerceAtLeast(0.0)
            hi = (hi + 0.01).coerceAtMost(1.0)
        }
        val pts = g.progress + ProgressPoint(to, f)
        val step = c == ItemHistory.EBOOK
        val end = if (c == ItemHistory.EBOOK && g.page != null) "p. ${g.page.first}" else "${(f * 100).roundToInt()}%"
        val line = MaterialTheme.colorScheme.primary
        val axis = MaterialTheme.colorScheme.surfaceContainerHighest
        val ring = MaterialTheme.colorScheme.surfaceContainer
        val span = (to - from).coerceAtLeast(1).toFloat()
        val range = (hi - lo).takeIf { it > 0 } ?: 1.0

        Label(stringResource(R.string.history_graph_progress))
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val endWidth = 52.dp
            val plotWidth = maxWidth - endWidth
            val lastY = (1 - (f - lo) / range).toFloat()
            Canvas(Modifier.width(plotWidth).fillMaxHeight()) {
                fun x(t: Long) = (t - from) / span * size.width

                fun y(v: Double) = (size.height - 4.dp.toPx()) - ((v - lo) / range).toFloat() * (size.height - 10.dp.toPx())
                val base = size.height - 1f
                drawLine(axis, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
                val path = Path()
                pts.forEachIndexed { k, p ->
                    if (k == 0) {
                        path.moveTo(x(p.at), y(p.f))
                    } else if (step) {
                        path.lineTo(x(p.at), y(pts[k - 1].f))
                        path.lineTo(x(p.at), y(p.f))
                    } else {
                        path.lineTo(x(p.at), y(p.f))
                    }
                }
                val area =
                    Path().apply {
                        addPath(path)
                        lineTo(x(to), base)
                        lineTo(x(pts.first().at), base)
                        close()
                    }
                drawPath(area, line.copy(alpha = 0.12f))
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val dot = Offset(x(to), y(f))
                drawCircle(ring, radius = 6.dp.toPx(), center = dot)
                drawCircle(line, radius = 4.dp.toPx(), center = dot)
            }
            Text(
                end,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.offset(x = plotWidth + FeederTokens.Space.sm, y = (56 - 10).dp * lastY - 4.dp),
            )
        }
        val short = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
        Row(Modifier.fillMaxWidth().padding(end = 52.dp, top = 2.dp)) {
            Text(short.format(Date(from)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.history_graph_today), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        if (c == ItemHistory.AUDIOBOOK && chapters.size > 1 && bookLengthMs > 0) {
            val at = HistoryStats.chapterAt(chapters.map { it.bookMs to it.lengthMs }, bookLengthMs, f)
            if (at != null) {
                val (k, into) = at
                val title = chapters[k].title
                Label(
                    stringResource(R.string.history_graph_chapter, k + 1, chapters.size) + if (title.isNotBlank()) " · $title" else "",
                    stringResource(R.string.history_graph_done, k),
                )
                if (chapters.size > 40) {
                    Track(f)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height(6.dp)) {
                        chapters.indices.forEach { j ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            ) {
                                val fill =
                                    when {
                                        j < k -> 1f
                                        j == k -> into.toFloat()
                                        else -> 0f
                                    }
                                if (fill > 0f) {
                                    Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                }
                            }
                        }
                    }
                }
            }
        } else if (c == ItemHistory.AUDIOBOOK && bookLengthMs > 0) {
            Label(
                stringResource(R.string.history_graph_of, ItemHistory.clockText((f * bookLengthMs).toLong()), ItemHistory.clockText(bookLengthMs)),
                stringResource(R.string.history_graph_left, minutesText(((1 - f) * bookLengthMs / 60_000).roundToInt())),
            )
            Track(f)
        } else if (c == ItemHistory.EBOOK) {
            val title = events.sortedByDescending { it.at }.firstOrNull { it.s.isNotBlank() }?.s
            if (title != null || g.page != null) {
                Label(title.orEmpty(), g.page?.let { stringResource(R.string.history_graph_pages_left, it.second - it.first) })
            }
        }

        val pace =
            if (c == ItemHistory.EBOOK && g.page != null && g.fPerDay != null) {
                stringResource(R.string.history_graph_pages_a_day, max(1, (g.fPerDay * g.page.second).roundToInt()))
            } else {
                stringResource(R.string.history_graph_min_a_day, g.minutesPerDay)
            }
        when {
            f >= 0.999 -> Foot(stringResource(R.string.history_graph_finished))
            g.daysLeft != null -> Foot(stringResource(R.string.history_graph_days_left, pluralStringResource(R.plurals.history_graph_days, g.daysLeft, g.daysLeft), pace))
        }
    }
}

@Composable
private fun Track(f: Double) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(Modifier.fillMaxWidth(f.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

/** ZIM: pages visited a day, from central History (one entry per page, at its last visit). */
@Composable
private fun ZimPages(
    g: HistoryGraph,
    bookId: String,
) {
    Column(Modifier.fillMaxWidth()) {
        val context = LocalContext.current
        val central = remember { CentralHistory.get(context) }
        val entries by central.entries.collectAsState()
        val pages = remember(entries, bookId) { entries.filter { it.t == CentralHistory.ZIM && it.a == CentralHistory.OPEN && it.i.startsWith("$bookId\n") } }
        val counts = remember(pages) { HistoryStats.countByDay(pages.map { it.at }) }
        val total = counts.sum()
        Label(stringResource(R.string.history_graph_pages_label), pluralStringResource(R.plurals.history_graph_pages, total, total))
        Bars(g, counts, height = 60) { pluralStringResource(R.plurals.history_graph_pages, it, it) }
        pages.maxByOrNull { it.at }?.let { Foot(stringResource(R.string.history_graph_last_page, it.n)) }
        Text(
            stringResource(R.string.history_graph_zim_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FeederTokens.Space.sm),
        )
    }
}
