package com.nononsenseapps.feeder.ui.compose.playlists

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.briefs.Brief
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import kotlinx.coroutines.delay

private const val POLL_MS = 20_000L

/**
 * A playlist's brief at the top of the playlist (same states as web BriefCard.tsx): waiting for the
 * Mac, being written, failed with Try again, or the big picture and one summary per article (tap a
 * title to read it).
 */
@Composable
internal fun BriefCard(
    brief: Brief,
    /** The article's id on this phone, or null when it isn't here */
    localId: (String) -> Long?,
    onOpen: (Long) -> Unit,
    onAgain: () -> Unit,
    onSync: () -> Unit,
) {
    val pending = brief.isPending()
    val sync by rememberUpdatedState(onSync)
    // While the Mac works on it, look for its answer more often than the usual sync
    LaunchedEffect(pending) {
        while (pending) {
            delay(POLL_MS)
            sync()
        }
    }
    val total = brief.items.size
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(FeederTokens.Radius.lg),
        modifier = Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.sm),
    ) {
        Column(Modifier.padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.sm, top = 10.dp, bottom = FeederTokens.Space.md)) {
            when {
                brief.s == Brief.DONE -> DoneBrief(brief, localId, onOpen, onAgain)
                brief.s == Brief.FAILED -> {
                    Header(Icons.Outlined.Summarize, stringResource(R.string.playlist_brief_failed)) {
                        TextButton(onClick = onAgain) { Text(stringResource(R.string.playlist_brief_try_again)) }
                    }
                    Hint(brief.err ?: stringResource(R.string.playlist_brief_failed_default))
                }
                !pending -> {
                    Header(Icons.Outlined.Summarize, stringResource(R.string.playlist_brief_stale)) {
                        TextButton(onClick = onAgain) { Text(stringResource(R.string.playlist_brief_try_again)) }
                    }
                    Hint(stringResource(R.string.playlist_brief_stale_hint))
                }
                else -> {
                    val reading = brief.s == Brief.WORKING && (brief.n ?: 0) < total
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(
                            when {
                                brief.s == Brief.ASKED -> stringResource(R.string.playlist_brief_waiting, total)
                                reading -> stringResource(R.string.playlist_brief_reading, brief.n ?: 0, total)
                                else -> stringResource(R.string.playlist_brief_writing)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(vertical = FeederTokens.Space.sm),
                        )
                    }
                    if (brief.s == Brief.WORKING) {
                        LinearProgressIndicator(
                            progress = { if (reading) 0.7f * (brief.n ?: 0) / total.coerceAtLeast(1) else 0.85f },
                            modifier = Modifier.fillMaxWidth().padding(end = FeederTokens.Space.sm, top = FeederTokens.Space.xs),
                        )
                    }
                    Hint(stringResource(R.string.playlist_brief_working_hint))
                }
            }
        }
    }
}

@Composable
private fun DoneBrief(
    brief: Brief,
    localId: (String) -> Long?,
    onOpen: (Long) -> Unit,
    onAgain: () -> Unit,
) {
    val context = LocalContext.current
    var open by rememberSaveable(brief.at) { mutableStateOf(true) }
    val shareLabel = stringResource(R.string.playlist_brief_share)
    val againLabel = stringResource(R.string.playlist_brief_new)
    val toggleLabel = stringResource(if (open) R.string.playlist_brief_hide else R.string.playlist_brief_show)
    Header(Icons.Outlined.Summarize, stringResource(R.string.playlist_brief_title), stringResource(R.string.playlist_brief_meta, brief.items.size)) {
        PlainTooltipBox(tooltip = { Text(shareLabel) }) {
            IconButton(onClick = {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, brief.name)
                        putExtra(Intent.EXTRA_TEXT, brief.asText())
                    }
                context.startActivity(Intent.createChooser(send, shareLabel))
            }) { Icon(Icons.Default.Share, contentDescription = shareLabel) }
        }
        PlainTooltipBox(tooltip = { Text(againLabel) }) {
            IconButton(onClick = onAgain) { Icon(Icons.Default.Refresh, contentDescription = againLabel) }
        }
        PlainTooltipBox(tooltip = { Text(toggleLabel) }) {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = toggleLabel)
            }
        }
    }
    if (!open) return
    Column(Modifier.padding(end = FeederTokens.Space.sm)) {
        brief.text?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        brief.ov?.takeIf { it.isNotEmpty() }?.let { bullets ->
            Label(stringResource(R.string.playlist_brief_big_picture))
            bullets.forEach { b ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(FeederTokens.Space.lg))
                    Text(b, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Label(stringResource(R.string.playlist_brief_by_article))
        brief.items.forEachIndexed { i, item ->
            val sum = brief.sums?.getOrNull(i)
            if (sum.isNullOrBlank()) return@forEachIndexed
            val id = localId(item.id)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (id != null) Modifier.clickable { onOpen(id) } else Modifier)
                    .padding(vertical = FeederTokens.Space.xs),
            ) {
                Text(
                    "${i + 1}. ${item.t}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (id != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(sum, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Header(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    meta: String? = null,
    actions: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(FeederTokens.Space.sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FeederTokens.Space.sm, bottom = FeederTokens.Space.xs),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FeederTokens.Space.xs, end = FeederTokens.Space.sm),
    )
}
