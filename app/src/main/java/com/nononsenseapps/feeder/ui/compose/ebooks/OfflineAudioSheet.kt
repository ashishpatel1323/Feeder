package com.nononsenseapps.feeder.ui.compose.ebooks

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.ebooks.BookAudio
import com.nononsenseapps.feeder.model.ebooks.BookAudioState
import com.nononsenseapps.feeder.model.ebooks.MacAudioState
import com.nononsenseapps.feeder.model.ebooks.isActive
import com.nononsenseapps.feeder.model.ebooks.SectionAudio
import com.nononsenseapps.feeder.model.ebooks.SectionAudioState
import com.nononsenseapps.feeder.ui.compose.readaloud.PlayerStatusLine

/** The player's status line for a book's offline audio, or null when there is nothing to say. */
@Composable
fun offlineAudioStatusLine(
    state: BookAudioState,
    bookId: String,
    ahead: Int,
    onClick: () -> Unit,
): PlayerStatusLine? {
    if (state.bookId != bookId || state.current < 0) return null
    val context = LocalContext.current
    val size = if (state.bytes > 0) Formatter.formatShortFileSize(context, state.bytes) else ""
    val upcoming = state.sections.filter { it.index > state.current }
    val preparing = upcoming.firstOrNull { it.state == SectionAudioState.PREPARING }
    return when {
        upcoming.isEmpty() -> null
        state.readyAhead > 0 && state.readyAhead >= minOf(ahead, upcoming.size) ->
            PlayerStatusLine(pluralStringResource(R.plurals.offline_audio_status_ready, state.readyAhead, state.readyAhead), size, busy = false, onClick = onClick)
        preparing != null ->
            PlayerStatusLine(stringResource(R.string.offline_audio_status_preparing, (preparing.progress * 100).toInt()), size, busy = true, onClick = onClick)
        else -> PlayerStatusLine(stringResource(R.string.offline_audio_status_waiting), size, busy = true, onClick = onClick)
    }
}

/** Each chapter's offline audio, and the two settings: how far ahead, and deleting played audio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineAudioSheet(
    state: BookAudioState,
    /** What the Mac has for this book (audio it makes with the natural voice, in Dropbox) */
    mac: MacAudioState,
    /** How much of the chapter being read read aloud has spoken so far (0–1), or null when it isn't reading */
    spokenProgress: Float?,
    ahead: Int,
    deletePlayed: Boolean,
    onSetAhead: (Int) -> Unit,
    onSetAutoDelete: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.offline_audio), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                if (state.bytes > 0) {
                    stringResource(R.string.offline_audio_lead, Formatter.formatShortFileSize(context, state.bytes))
                } else {
                    stringResource(R.string.offline_audio_lead_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            MacCard(state, mac)
            for (section in state.sections) SectionRow(section, state.currentReady, spokenProgress)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.offline_audio_prepare_ahead),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                BookAudio.AHEAD_CHOICES.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = ahead == n,
                        onClick = { onSetAhead(n) },
                        shape = SegmentedButtonDefaults.itemShape(i, BookAudio.AHEAD_CHOICES.size),
                    ) { Text(if (n == BookAudio.WHOLE_BOOK) stringResource(R.string.offline_audio_whole_book) else n.toString(), maxLines = 1) }
                }
            }
            Text(
                stringResource(R.string.offline_audio_ahead_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.offline_audio_delete_played), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.offline_audio_delete_played_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = deletePlayed, onCheckedChange = onSetAutoDelete)
            }
        }
    }
}

/**
 * Design B: one card at the top says how the Mac is doing (making audio, or not seen for a while so
 * this phone makes chapters itself). Nothing shows when the Mac has never made audio.
 */
@Composable
private fun MacCard(
    state: BookAudioState,
    mac: MacAudioState,
) {
    val status = mac.status ?: return
    if (status.seen <= 0) return
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val active = status.isActive(now)
    val title: String
    val detail: String
    if (active) {
        val ready = state.sections.count { it.fromMac && (it.state == SectionAudioState.READY || it.state == SectionAudioState.PLAYING) }
        val waiting = state.sections.count { it.waitingForWifi }
        title = stringResource(if (status.working != null) R.string.offline_audio_mac_making else R.string.offline_audio_mac_ready)
        val parts =
            buildList {
                add(pluralStringResource(R.plurals.offline_audio_mac_ready_count, ready, ready))
                if (waiting > 0) add(pluralStringResource(R.plurals.offline_audio_mac_waiting_count, waiting, waiting))
            }.joinToString(", ")
        val seen = DateUtils.getRelativeTimeSpanString(status.seen, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val size = if (state.bytes > 0) " " + Formatter.formatShortFileSize(context, state.bytes) + "." else ""
        detail = stringResource(R.string.offline_audio_mac_detail, parts, seen) + size
    } else {
        title = stringResource(R.string.offline_audio_mac_away_title)
        val since = DateUtils.formatDateTime(context, status.seen, DateUtils.FORMAT_SHOW_TIME or (if (now - status.seen > DateUtils.DAY_IN_MILLIS) DateUtils.FORMAT_SHOW_DATE else 0))
        detail = stringResource(R.string.offline_audio_mac_away, since)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Outlined.LaptopMac, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The small "Mac" badge on chapters made by the Mac with the natural voice. */
@Composable
private fun MacBadge() {
    Text(
        stringResource(R.string.offline_audio_mac_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun SectionRow(
    section: SectionAudio,
    currentReady: Boolean,
    spokenProgress: Float?,
) {
    val playing = section.state == SectionAudioState.PLAYING
    val played = section.state == SectionAudioState.PLAYED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (playing) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (section.state) {
                SectionAudioState.PLAYED -> Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.outline)
                SectionAudioState.PLAYING -> Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                SectionAudioState.READY -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                SectionAudioState.PREPARING ->
                    CircularProgressIndicator(
                        progress = { section.progress.coerceIn(0.02f, 1f) },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 3.dp,
                    )
                SectionAudioState.WAITING ->
                    if (section.waitingForWifi) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.outline)
                    } else {
                        Box(Modifier.size(18.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                section.label.ifBlank { stringResource(R.string.offline_audio_section, section.index + 1) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (played) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (section.state) {
                    SectionAudioState.PLAYED -> stringResource(R.string.offline_audio_played)
                    SectionAudioState.PLAYING ->
                        when {
                            currentReady -> stringResource(R.string.offline_audio_playing_ready)
                            spokenProgress == null -> stringResource(R.string.offline_audio_playing)
                            spokenProgress >= 1f -> stringResource(R.string.offline_audio_playing_saving)
                            else -> stringResource(R.string.offline_audio_playing_preparing, (spokenProgress * 100).toInt())
                        }
                    SectionAudioState.READY ->
                        stringResource(R.string.offline_audio_ready, stringResource(R.string.offline_audio_minutes, maxOf(1, (section.durationMillis / 60_000).toInt())))
                    SectionAudioState.PREPARING -> stringResource(R.string.offline_audio_preparing, (section.progress * 100).toInt())
                    SectionAudioState.WAITING ->
                        if (section.waitingForWifi) stringResource(R.string.offline_audio_waiting_wifi) else stringResource(R.string.offline_audio_up_next)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (section.fromMac && !section.waitingForWifi) MacBadge()
            }
        }
    }
}
