package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.item.DeleteIcon
import com.nononsenseapps.feeder.ui.compose.item.PlaylistIcon
import com.nononsenseapps.feeder.ui.compose.item.markReadIcon
import com.nononsenseapps.feeder.ui.compose.item.markReadLabel
import com.nononsenseapps.feeder.ui.compose.item.saveIcon
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens

/**
 * What the article screen's ⋮ menu can do. A null callback leaves that entry out.
 * Material 3: the top bar keeps the two everyday actions (Save, Share); the rest live here.
 */
class ArticleMenuActions(
    val kind: ItemKind,
    val read: Boolean,
    val onToggleRead: () -> Unit,
    val onPlaylist: (() -> Unit)? = null,
    val transcriptShown: Boolean = false,
    val onToggleTranscript: (() -> Unit)? = null,
    val fullText: Boolean = false,
    val onToggleFullText: (() -> Unit)? = null,
    val webPage: Boolean = false,
    val onToggleWebPage: (() -> Unit)? = null,
    val onOpenInBrowser: (() -> Unit)? = null,
    val onSummarize: (() -> Unit)? = null,
    val translated: Boolean = false,
    val onTranslate: (() -> Unit)? = null,
    val fastReading: Boolean = false,
    val onToggleFastReading: (() -> Unit)? = null,
    val onSpeedRead: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
)

/** Top app bar actions of the article screen: Save, Share and the ⋮ menu. */
@Composable
fun RowScope.ArticleTopBarActions(
    saved: Boolean,
    onToggleSave: () -> Unit,
    onShare: () -> Unit,
    menu: ArticleMenuActions,
) {
    val saveLabel = stringResource(if (saved) R.string.action_saved else R.string.action_save)
    PlainTooltipBox(tooltip = { Text(saveLabel) }) {
        IconButton(onClick = onToggleSave) {
            Icon(
                saveIcon(saved),
                contentDescription = saveLabel,
                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val shareLabel = stringResource(R.string.share)
    PlainTooltipBox(tooltip = { Text(shareLabel) }) {
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = shareLabel)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.more_options)
    Box {
        PlainTooltipBox(tooltip = { Text(moreLabel) }) {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = moreLabel)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ArticleMenuItems(menu = menu, close = { expanded = false })
        }
    }
}

@Composable
private fun ArticleMenuItems(
    menu: ArticleMenuActions,
    close: () -> Unit,
) {
    fun run(action: () -> Unit): () -> Unit =
        {
            close()
            action()
        }
    MenuEntry(markReadIcon(menu.read), markReadLabel(menu.kind, menu.read), run(menu.onToggleRead))
    menu.onPlaylist?.let { MenuEntry(PlaylistIcon, stringResource(R.string.add_to_playlist_menu), run(it)) }
    menu.onToggleTranscript?.let {
        MenuEntry(Icons.Outlined.ClosedCaption, stringResource(R.string.transcript), run(it), checked = menu.transcriptShown)
    }

    val hasReading = menu.onToggleFullText != null || menu.onToggleWebPage != null || menu.onOpenInBrowser != null
    if (hasReading) HorizontalDivider()
    menu.onToggleFullText?.let {
        MenuEntry(Icons.AutoMirrored.Filled.Article, stringResource(R.string.full_text_short), run(it), checked = menu.fullText)
    }
    menu.onToggleWebPage?.let {
        MenuEntry(Icons.Default.Language, stringResource(R.string.menu_web_page), run(it), checked = menu.webPage)
    }
    menu.onOpenInBrowser?.let {
        MenuEntry(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_in_browser), run(it))
    }

    val hasTools = menu.onSummarize != null || menu.onTranslate != null || menu.onToggleFastReading != null || menu.onSpeedRead != null
    if (hasTools) HorizontalDivider()
    menu.onSummarize?.let { MenuEntry(Icons.Outlined.AutoAwesome, stringResource(R.string.summarize), run(it)) }
    menu.onTranslate?.let {
        MenuEntry(
            Icons.Default.Translate,
            stringResource(if (menu.translated) R.string.action_original else R.string.action_translate),
            run(it),
        )
    }
    menu.onToggleFastReading?.let {
        MenuEntry(Icons.Default.FormatBold, stringResource(R.string.menu_bold_word_starts), run(it), checked = menu.fastReading)
    }
    menu.onSpeedRead?.let { MenuEntry(Icons.Default.Speed, stringResource(R.string.speed_read), run(it)) }

    menu.onDelete?.let {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete_article)) },
            onClick = run(it),
            leadingIcon = { Icon(DeleteIcon, contentDescription = null) },
            colors =
                MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
        )
    }
}

@Composable
private fun MenuEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    // Set for on/off entries: a check mark shows when on
    checked: Boolean? = null,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon =
            if (checked == true) {
                { Icon(Icons.Default.Check, contentDescription = stringResource(androidx.compose.ui.R.string.selected)) }
            } else {
                null
            },
    )
}

/** The article's one floating action: Listen (read aloud). Shrinks to the icon while reading down. */
@Composable
fun ListenFab(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
        text = { Text(stringResource(R.string.listen)) },
        modifier = modifier,
    )
}

/**
 * Shown instead of the article when its full text could not be loaded: what happened and the
 * ways forward (the web page in the app, the feed's own text, or another try).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullTextFailure(
    message: String,
    onShowWebPage: (() -> Unit)?,
    onShowFeedText: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
        ) {
            onShowWebPage?.let {
                Button(onClick = it) {
                    Icon(Icons.Default.Language, contentDescription = null)
                    Text(stringResource(R.string.open_web_page), modifier = Modifier.padding(start = FeederTokens.Space.sm))
                }
            }
            OutlinedButton(onClick = onShowFeedText) {
                Text(stringResource(R.string.show_feed_text))
            }
            onRetry?.let {
                OutlinedButton(onClick = it) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(stringResource(R.string.try_again), modifier = Modifier.padding(start = FeederTokens.Space.sm))
                }
            }
        }
    }
}
