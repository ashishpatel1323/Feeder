package com.nononsenseapps.feeder.ui.compose.highlights

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightColor
import com.nononsenseapps.feeder.model.highlights.allQuotesText
import com.nononsenseapps.feeder.model.highlights.quoteText
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import java.text.DateFormat
import java.util.Date

val HighlightColor.label: Int
    get() =
        when (this) {
            HighlightColor.YELLOW -> R.string.highlight_color_yellow
            HighlightColor.GREEN -> R.string.highlight_color_green
            HighlightColor.BLUE -> R.string.highlight_color_blue
            HighlightColor.PINK -> R.string.highlight_color_pink
        }

@Composable
fun ColorSwatches(
    selected: HighlightColor,
    onPick: (HighlightColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md), verticalAlignment = Alignment.CenterVertically) {
        HighlightColor.entries.forEach { c ->
            val label = stringResource(c.label)
            val on = c == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(c.background())
                        .border(
                            if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            CircleShape,
                        ).clickable(role = Role.RadioButton) { onPick(c) }
                        .semantics {
                            contentDescription = label
                            this.selected = on
                        },
            ) {
                if (on) Icon(Icons.Default.Check, contentDescription = null, tint = HighlightTextColor)
            }
        }
    }
}

/**
 * The sheet for a fresh selection (pick a color, add a note, Highlight) or for a saved highlight
 * (recolor right away, change the note, share, copy, delete).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HighlightSheet(
    text: String,
    existing: Highlight?,
    onSave: (HighlightColor, String) -> Unit,
    onShare: (HighlightColor, String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var color by rememberSaveable(existing?.id, text) { mutableStateOf(existing?.highlightColor ?: HighlightColor.YELLOW) }
    var note by rememberSaveable(existing?.id, text) { mutableStateOf(existing?.note ?: "") }
    val dirtyNote = existing != null && note.trim() != existing.note
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.lg),
            modifier = Modifier.padding(start = FeederTokens.Space.xl, end = FeederTokens.Space.xl, bottom = FeederTokens.Space.xl),
        ) {
            Text(
                stringResource(if (existing != null) R.string.highlight_edit else R.string.highlight_new),
                style = MaterialTheme.typography.titleLarge,
            )
            QuotePreview(text, color)
            Text(stringResource(R.string.highlight_color), style = MaterialTheme.typography.titleSmall)
            ColorSwatches(
                selected = color,
                onPick = {
                    color = it
                    // A saved highlight takes a new color right away
                    if (existing != null) onSave(it, existing.note)
                },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.highlight_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            ) {
                if (existing == null) {
                    Button(onClick = { onSave(color, note) }) {
                        Icon(Icons.Outlined.BorderColor, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(R.string.highlight_action))
                    }
                } else {
                    Button(onClick = { onSave(color, note) }, enabled = dirtyNote) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(if (dirtyNote) R.string.highlight_save_note else R.string.highlight_saved))
                    }
                }
                OutlinedButton(onClick = { onShare(color, note) }) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(FeederTokens.Space.sm))
                    Text(stringResource(R.string.highlight_share_image))
                }
                OutlinedButton(onClick = { onCopy(note) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(FeederTokens.Space.sm))
                    Text(stringResource(R.string.highlight_copy_text))
                }
                if (existing != null) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.width(FeederTokens.Space.sm))
                        Text(stringResource(R.string.highlight_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotePreview(
    text: String,
    color: HighlightColor,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(Color(color.ink)),
        )
        Text(
            "“$text”",
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = FeederTokens.Space.md),
        )
    }
}

/** One highlight with its actions, in the article's list and on the Highlights screen. */
@Composable
fun HighlightCard(
    h: Highlight,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    showDate: Boolean = false,
    // Shown with a checkbox when picking highlights to share together
    selected: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(FeederTokens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Color(h.highlightColor.ink)),
            )
            Column(modifier = Modifier.padding(FeederTokens.Space.md).weight(1f)) {
                if (onToggle != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(role = Role.Checkbox, onClick = onToggle),
                    ) {
                        Checkbox(checked = selected == true, onCheckedChange = null)
                        Text(
                            stringResource(R.string.highlights_include),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = FeederTokens.Space.sm),
                        )
                    }
                    Spacer(Modifier.height(FeederTokens.Space.xs))
                }
                Text(
                    h.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
                    color = if (selected == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier =
                        if (onOpen != null) {
                            Modifier.clickable(onClickLabel = stringResource(R.string.highlights_show_in_article), onClick = onOpen)
                        } else {
                            Modifier
                        },
                )
                if (h.note.isNotBlank()) {
                    Text(
                        h.note,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = FeederTokens.Space.xs),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDate) {
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(h.createdAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    CardAction(Icons.Default.Image, stringResource(R.string.highlight_share_image), onShare)
                    CardAction(Icons.Default.ContentCopy, stringResource(R.string.highlight_copy_text), onCopy)
                    onEdit?.let { CardAction(Icons.Default.Edit, stringResource(R.string.highlight_edit), it) }
                    CardAction(Icons.Outlined.Delete, stringResource(R.string.highlight_delete), onDelete)
                }
            }
        }
    }
}

@Composable
private fun CardAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** The open article's highlights, with Select all to share several in one image or text. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArticleHighlightsSheet(
    highlights: List<Highlight>,
    onEdit: (Highlight) -> Unit,
    onShare: (Highlight) -> Unit,
    onShareMany: (List<Highlight>) -> Unit,
    onDelete: (Highlight) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Which highlights a combined share includes: all of them unless unticked
    var off by remember { mutableStateOf(emptySet<String>()) }
    val picked = highlights.filter { it.id !in off }
    val allOn = picked.size == highlights.size
    val many = highlights.size > 1
    val copiedMany = pluralStringResource(R.plurals.highlights_copied_many, picked.size, picked.size)
    val copied = stringResource(R.string.highlight_copied)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            modifier = Modifier.padding(horizontal = FeederTokens.Space.lg),
        ) {
            item {
                Text(highlightsCount(highlights.size), style = MaterialTheme.typography.titleLarge)
            }
            if (many) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.clickable(role = Role.Checkbox) {
                                    off = if (allOn) highlights.map { it.id }.toSet() else emptySet()
                                },
                        ) {
                            Checkbox(checked = allOn, onCheckedChange = null, modifier = Modifier.padding(FeederTokens.Space.sm))
                            Text(stringResource(R.string.highlights_select_all, picked.size, highlights.size), style = MaterialTheme.typography.bodyMedium)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm)) {
                            Button(onClick = { onShareMany(picked) }, enabled = picked.isNotEmpty()) {
                                Icon(Icons.Default.Image, contentDescription = null)
                                Spacer(Modifier.width(FeederTokens.Space.sm))
                                Text(stringResource(R.string.highlights_image_link))
                            }
                            OutlinedButton(onClick = { copyText(context, allQuotesText(picked), copiedMany) }, enabled = picked.isNotEmpty()) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(FeederTokens.Space.sm))
                                Text(stringResource(R.string.highlights_text_link))
                            }
                        }
                    }
                }
            }
            if (highlights.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.highlights_empty_article),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = FeederTokens.Space.lg),
                    )
                }
            }
            items(highlights, key = { it.id }) { h ->
                HighlightCard(
                    h = h,
                    onOpen = { onEdit(h) },
                    onShare = { onShare(h) },
                    onCopy = { copyText(context, quoteText(h.text, h.itemTitle, h.feedTitle, h.itemLink, h.note), copied) },
                    onEdit = { onEdit(h) },
                    onDelete = { onDelete(h) },
                    selected = if (many) h.id !in off else null,
                    onToggle =
                        if (many) {
                            { off = if (h.id in off) off - h.id else off + h.id }
                        } else {
                            null
                        },
                )
            }
            item { Spacer(Modifier.height(FeederTokens.Space.xl)) }
        }
    }
}

fun toast(
    context: android.content.Context,
    msg: Int,
) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
