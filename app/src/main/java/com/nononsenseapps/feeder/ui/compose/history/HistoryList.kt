package com.nononsenseapps.feeder.ui.compose.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.history.HistoryEvent
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import java.util.Calendar

/** An item's events grouped by day, newest first. */
data class HistoryDay(
    val label: String,
    val events: List<HistoryEvent>,
)

/** One item's history, grouped by day, kept up to date. */
@Composable
fun rememberHistory(
    c: String,
    i: String,
): List<HistoryDay> {
    val context = LocalContext.current
    val history = remember { ItemHistory.get(context) }
    val all by history.events.collectAsState()
    val today = stringResource(R.string.history_today)
    val yesterday = stringResource(R.string.history_yesterday)
    return remember(all, c, i) {
        groupByDay(all.filter { it.c == c && it.i == i }, today, yesterday)
    }
}

fun groupByDay(
    events: List<HistoryEvent>,
    today: String,
    yesterday: String,
    now: Long = System.currentTimeMillis(),
): List<HistoryDay> {
    val out = mutableListOf<HistoryDay>()
    val startToday = startOfDay(now)
    val thisYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
    for (e in events.sortedByDescending { it.at }) {
        val start = startOfDay(e.at)
        val days = Math.round((startToday - start) / 86_400_000.0)
        val label =
            when (days) {
                0L -> today
                1L -> yesterday
                else -> {
                    val year = Calendar.getInstance().apply { timeInMillis = e.at }.get(Calendar.YEAR)
                    val pattern = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), if (year == thisYear) "EEEMMMd" else "EEEMMMdyyyy")
                    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(e.at))
                }
            }
        val last = out.lastOrNull()
        if (last?.label == label) out[out.size - 1] = last.copy(events = last.events + e) else out += HistoryDay(label, listOf(e))
    }
    return out
}

private fun startOfDay(t: Long): Long =
    Calendar
        .getInstance()
        .apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

@Composable
private fun reasonText(r: String): String? =
    when (r) {
        "app" -> stringResource(R.string.history_reason_app)
        "notification" -> stringResource(R.string.history_reason_notification)
        "media" -> stringResource(R.string.history_reason_media)
        "headset" -> stringResource(R.string.history_reason_headset)
        "bluetooth" -> stringResource(R.string.history_reason_bluetooth)
        "car" -> stringResource(R.string.history_reason_car)
        "watch" -> stringResource(R.string.history_reason_watch)
        "sleep" -> stringResource(R.string.history_reason_sleep)
        "end" -> stringResource(R.string.history_reason_end)
        "focus" -> stringResource(R.string.history_reason_focus)
        "closed" -> stringResource(R.string.history_reason_closed)
        "background" -> stringResource(R.string.history_reason_background)
        "system" -> stringResource(R.string.history_reason_system)
        "widget" -> stringResource(R.string.history_reason_widget)
        else -> null
    }

@Composable
private fun actionText(a: String): String =
    when (a) {
        ItemHistory.PLAY -> stringResource(R.string.history_played)
        ItemHistory.PAUSE -> stringResource(R.string.history_paused)
        ItemHistory.OPEN -> stringResource(R.string.history_opened)
        ItemHistory.CLOSE -> stringResource(R.string.history_closed)
        else -> a
    }

/** The day headers and timeline cards, for a list that has other things in it too. */
fun LazyListScope.historyItems(
    days: List<HistoryDay>,
    emptyText: Int,
    onJump: ((HistoryEvent) -> Unit)?,
) {
    if (days.isEmpty()) {
        item(key = "history-empty") {
            Text(
                stringResource(emptyText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(FeederTokens.Space.md),
            )
        }
        return
    }
    for (d in days) {
        item(key = "day-${d.label}") {
            Text(
                d.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = FeederTokens.Space.md, top = FeederTokens.Space.lg, bottom = FeederTokens.Space.sm),
            )
        }
        item(key = "card-${d.label}") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FeederTokens.Radius.xl))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(vertical = FeederTokens.Space.sm),
            ) {
                d.events.forEachIndexed { k, e ->
                    // The line down to the older event: solid while it was playing (or open), dotted while paused
                    val older = d.events.getOrNull(k + 1)
                    val line =
                        when {
                            older == null -> null
                            older.a == ItemHistory.PLAY || older.a == ItemHistory.OPEN -> true
                            else -> false
                        }
                    HistoryRow(e, onJump, line)
                }
            }
        }
    }
}

/** One event: time, icon on the timeline, what happened and why, the place as a chip, the device. */
@Composable
fun HistoryRow(
    e: HistoryEvent,
    onJump: ((HistoryEvent) -> Unit)?,
    /** The line below the icon: null none, true solid, false dotted */
    line: Boolean? = null,
) {
    val context = LocalContext.current
    val reason = reasonText(e.r)
    val time = remember(e.at) { DateFormat.getTimeFormat(context).format(java.util.Date(e.at)) }
    val active = e.a == ItemHistory.PLAY || e.a == ItemHistory.OPEN
    val jump = onJump?.takeIf { e.x.isNotEmpty() }
    val goBack = stringResource(R.string.history_go_back)
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.outline
    Row(
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(if (jump != null) Modifier.clickable(onClickLabel = goBack, role = Role.Button) { jump(e) } else Modifier)
                .padding(horizontal = FeederTokens.Space.lg),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(64.dp).padding(top = 9.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Icon(
                    when (e.a) {
                        ItemHistory.PLAY -> Icons.Outlined.PlayArrow
                        ItemHistory.PAUSE -> Icons.Outlined.Pause
                        ItemHistory.OPEN -> Icons.AutoMirrored.Outlined.MenuBook
                        else -> Icons.Outlined.Close
                    },
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (line != null) {
                Box(
                    Modifier
                        .weight(1f)
                        .width(2.dp)
                        .drawBehind {
                            if (line) {
                                drawLine(lineColor, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width)
                            } else {
                                drawLine(
                                    dotColor,
                                    Offset(size.width / 2, 0f),
                                    Offset(size.width / 2, size.height),
                                    strokeWidth = size.width,
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 6.dp.toPx())),
                                )
                            }
                        },
                )
            }
        }
        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = FeederTokens.Space.lg)) {
            Text(
                actionText(e.a) + (reason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (e.p.isNotEmpty() || e.dn.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                    modifier = Modifier.padding(top = FeederTokens.Space.sm),
                ) {
                    if (e.p.isNotEmpty()) {
                        Text(
                            e.p,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .widthIn(max = 180.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = FeederTokens.Space.md, vertical = 6.dp),
                        )
                    }
                    if (e.dn.isNotEmpty()) {
                        Text(
                            e.dn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (e.s.isNotEmpty() && e.p.isEmpty()) {
                Text(e.s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An ebook's or ZIM site's history in a bottom sheet. Tapping an event goes back there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    c: String,
    i: String,
    onJump: (HistoryEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val days = rememberHistory(c, i)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.history),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = FeederTokens.Space.xl),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.sm),
        ) {
            item(key = "history-graph") { HistoryGraphCard(c, i) }
            historyItems(days, if (c == ItemHistory.AUDIOBOOK) R.string.history_empty_audio else R.string.history_empty_read) {
                onJump(it)
                onDismiss()
            }
        }
    }
}
