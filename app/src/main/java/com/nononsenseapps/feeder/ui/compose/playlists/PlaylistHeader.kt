package com.nononsenseapps.feeder.ui.compose.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.playlists.PlaylistSort
import com.nononsenseapps.feeder.model.playlists.label
import com.nononsenseapps.feeder.ui.compose.feed.FeedFlipContent
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedState
import com.nononsenseapps.feeder.ui.compose.feedarticle.TranslatedFeedCards
import com.nononsenseapps.feeder.ui.compose.item.rememberItemCommands
import kotlinx.coroutines.flow.MutableStateFlow

/** The playlist list's one top bar action: + opens a menu of the two kinds of playlist. */
@Composable
internal fun NewPlaylistAction(
    onNewSmart: () -> Unit,
    onNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.playlist_new)
    Box {
        PlainTooltipBox(tooltip = { Text(label) }) {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.Add, contentDescription = label)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_new_smart)) },
                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNewSmart()
                },
            )
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
        }
    }
}

/**
 * An open playlist's top bar: Flip view, then ⋮ with the less used actions (edit, download,
 * delete), as the article screen has them.
 */
@Composable
internal fun PlaylistTopBarActions(
    smart: Boolean,
    canFlip: Boolean,
    onFlip: () -> Unit,
    onEdit: () -> Unit,
    downloadCount: Int,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    prepareCount: Int = 0,
    prepareSize: String = "",
    onPrepare: () -> Unit = {},
    /** "Brief with Claude" / "New brief with Claude", or null to leave it out */
    briefLabel: String? = null,
    onBrief: () -> Unit = {},
) {
    val flipLabel = stringResource(R.string.show_as_flip_pages)
    PlainTooltipBox(tooltip = { Text(flipLabel) }) {
        IconButton(onClick = onFlip, enabled = canFlip) {
            Icon(Icons.Outlined.AutoStories, contentDescription = flipLabel)
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
            DropdownMenuItem(
                text = { Text(stringResource(if (smart) R.string.playlist_edit_rules else R.string.playlist_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            if (downloadCount > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_download, downloadCount)) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onDownload()
                    },
                )
            }
            if (prepareCount > 0) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.playlist_prepare_articles, prepareCount))
                            if (prepareSize.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.playlist_prepare_articles_size, prepareSize),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onPrepare()
                    },
                )
            }
            if (briefLabel != null) {
                DropdownMenuItem(
                    text = { Text(briefLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Summarize, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onBrief()
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                colors =
                    MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** A smart playlist's rules in one chip ("DailyTechNews · Article · Unread"); tap to edit them. */
@Composable
internal fun RulesChip(
    rules: List<String>,
    onClick: () -> Unit,
) {
    val label = rules.joinToString(" · ").ifBlank { stringResource(R.string.playlist_edit_rules) }
    FilterChip(
        selected = rules.isNotEmpty(),
        onClick = onClick,
        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp)) },
        modifier = Modifier.semantics { contentDescription = "$label. Edit rules" },
    )
}

/** The playlist's order as one chip with a menu, in place of four segmented buttons. */
@Composable
internal fun SortMenuChip(
    current: PlaylistSort,
    onSelect: (PlaylistSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortLabel = stringResource(R.string.playlist_sort)
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(current.label, maxLines = 1) },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            modifier =
                Modifier.semantics {
                    contentDescription = "$sortLabel: ${current.label}"
                    role = Role.DropdownList
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlaylistSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon =
                        if (option == current) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            { Spacer(Modifier.size(24.dp)) }
                        },
                )
            }
        }
    }
}

/**
 * A playlist as flip pages: the feed list's flip view over the playlist's items, in playlist
 * order. [items] is null while they load.
 */
@Composable
internal fun PlaylistFlip(
    items: List<FeedListItem>?,
    onOpenArticle: (Long) -> Unit,
    onExit: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    if (items == null) {
        Box(modifier.fillMaxSize())
        return
    }
    if (items.isEmpty()) {
        // Every item was deleted: back to the playlist
        val exit by rememberUpdatedState(onExit)
        LaunchedEffect(Unit) { exit() }
        return
    }
    val commands = rememberItemCommands()
    val pagingFlow = remember { MutableStateFlow(PagingData.from(items)) }
    LaunchedEffect(items) { pagingFlow.value = PagingData.from(items) }
    val paged = pagingFlow.collectAsLazyPagingItems()
    FeedFlipContent(
        viewState = FeedState(haveVisibleFeedItems = true),
        pagedFeedItems = paged,
        translatedFeedCards = TranslatedFeedCards(),
        onTranslateFeedCard = {},
        markAsUnread = { id, unread -> commands.setRead(id, !unread) },
        onSetBookmark = { id, saved -> commands.setSaved(id, saved) },
        onItemClick = onOpenArticle,
        onOpenNavDrawer = onExit,
        onAddFeed = onExit,
        onExit = onExit,
        onDeleteItem = { id -> commands.delete(id, snackbar) },
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}
