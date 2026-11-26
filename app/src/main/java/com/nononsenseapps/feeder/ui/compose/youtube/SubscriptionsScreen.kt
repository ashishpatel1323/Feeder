package com.nononsenseapps.feeder.ui.compose.youtube

import com.nononsenseapps.feeder.ui.compose.components.FolderFilterChip
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.YouTubeStreams
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.ui.compose.components.DeleteRequest
import com.nononsenseapps.feeder.ui.compose.components.DeleteScope
import com.nononsenseapps.feeder.ui.compose.components.rememberDeleteConfirmer
import com.nononsenseapps.feeder.ui.compose.feed.FeedSelection
import com.nononsenseapps.feeder.ui.compose.feed.FeedSelectionBar
import com.nononsenseapps.feeder.ui.compose.feedarticle.openInYouTubeApp
import com.nononsenseapps.feeder.ui.compose.item.markReadIcon
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * The YouTube tab, after Flow's Subscriptions: folder chips, when the feeds were last fetched, a
 * Shorts shelf, then every video of the YouTube feeds, newest first, as rows or big cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: YouTubeViewModel,
    onOpenVideo: (VideoRow) -> Unit,
    onOpenShorts: (VideoRow) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.viewState.collectAsState()
    val streams: YouTubeStreams by LocalDI.current.instance()
    // Opens the connection to YouTube before the first video is tapped
    LaunchedEffect(Unit) { streams.warmUp() }
    val open: (VideoRow) -> Unit = { row ->
        streams.prefetch(listOf(row.videoId))
        onOpenVideo(row)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val actions = rememberVideoActions(viewModel, state, snackbar)
    var editGroups by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    // Lengths and views of the rows on screen (and the next few) are looked up as they show
    LaunchedEffect(gridState, state.videos, state.shorts) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.distinctUntilChanged()
            .collect { last ->
                viewModel.onRowsShown(state.shorts.take(12) + state.videos.take(last + 20))
            }
    }
    // The first few videos on screen start at once when tapped: their streams are fetched ahead
    LaunchedEffect(gridState, state.videos) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .firstOrNull()
                ?.index ?: 0
        }.distinctUntilChanged()
            .collectLatest { first ->
                delay(PREFETCH_SETTLE_MS)
                streams.prefetch(
                    state.videos
                        .drop((first - 1).coerceAtLeast(0))
                        .take(PREFETCH_ROWS)
                        .map { it.videoId },
                )
            }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SubscriptionsTopBar(
                grid = state.grid,
                onToggleGrid = viewModel::toggleGrid,
                onSearch = onManage,
            )
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.loaded && !state.hasChannels) {
                    EmptySubscriptions(onManage)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (state.grid) 320.dp else 480.dp),
                        state = gridState,
                        // Room for the selection bar, so the last rows can still be picked
                        contentPadding = PaddingValues(bottom = if (actions.selection.active) 200.dp else FeederTokens.Space.lg),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                GroupChips(
                                    groups = state.groups,
                                    picked = state.picked,
                                    onChange = viewModel::selectGroups,
                                    onEdit = { editGroups = true },
                                )
                                RefreshStatus(state)
                            }
                        }
                        if (state.shorts.isNotEmpty()) {
                            item(key = "shorts", span = { GridItemSpan(maxLineSpan) }) {
                                ShortsShelf(
                                    shorts = state.shorts,
                                    onOpen = { row ->
                                        streams.prefetch(listOf(row.videoId))
                                        onOpenShorts(row)
                                    },
                                    actions = actions,
                                )
                            }
                        }
                        items(state.videos, key = { it.item.id }) { row ->
                            if (state.grid) {
                                VideoCard(row, onClick = { open(row) }, actions = actions)
                            } else {
                                VideoListRow(row, onClick = { open(row) }, actions = actions)
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            SnackbarHost(snackbar)
            // Long press picks videos; the same bar as in Feeds, plus Save
            if (actions.selection.active) FeedSelectionBar(actions.selection)
        }
    }

    if (editGroups) {
        GroupsDialog(
            state = state,
            onSave = viewModel::saveGroup,
            onDelete = viewModel::deleteGroup,
            onDismiss = { editGroups = false },
        )
    }
}

@Composable
private fun SubscriptionsTopBar(
    grid: Boolean,
    onToggleGrid: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp)
                .padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs),
    ) {
        Text(
            stringResource(R.string.youtube_subscriptions),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleGrid) {
            Icon(
                if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                contentDescription = stringResource(if (grid) R.string.youtube_view_list else R.string.youtube_view_grid),
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.youtube_search_subscriptions))
        }
    }
}

@Composable
private fun GroupChips(
    groups: List<String>,
    picked: List<String>,
    onChange: (List<String>) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FeederTokens.Space.md, vertical = FeederTokens.Space.xs),
    ) {
        val res = LocalContext.current.resources
        FolderFilterChip(
            folders = groups,
            picked = picked,
            onChange = onChange,
            allLabel = stringResource(R.string.youtube_group_all),
            several = { n -> res.getQuantityString(R.plurals.youtube_groups_picked, n, n) },
            contentDescription = stringResource(R.string.youtube_filter_groups),
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.youtube_edit_groups),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RefreshStatus(state: YouTubeViewState) {
    val checked = state.videos.size + state.shorts.size
    val text =
        if (state.lastRefreshed != null) {
            stringResource(R.string.youtube_last_refreshed, ageText(state.lastRefreshed)) + " · " +
                pluralStringResource(R.plurals.youtube_videos_checked, checked, checked)
        } else {
            pluralStringResource(R.plurals.youtube_videos_checked, checked, checked)
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.xs),
    )
}

@Composable
private fun ShortsShelf(
    shorts: List<VideoRow>,
    onOpen: (VideoRow) -> Unit,
    actions: VideoActions,
) {
    Column(Modifier.padding(top = FeederTokens.Space.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.sm),
        ) {
            Text(
                stringResource(R.string.youtube_shorts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(vertical = FeederTokens.Space.sm),
            )
            // Full screen, swiping up and down through the shelf like YouTube Shorts
            TextButton(onClick = { shorts.firstOrNull()?.let(onOpen) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(FeederTokens.Space.xs))
                Text(stringResource(R.string.youtube_shorts_play_all))
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            contentPadding = PaddingValues(horizontal = FeederTokens.Space.lg),
        ) {
            items(shorts, key = { it.item.id }) { row ->
                VideoPressable(row, onClick = { onOpen(row) }, actions = actions, modifier = Modifier.width(132.dp)) { press, more ->
                    Column(press) {
                        VideoThumbnail(
                            url = youtubeThumbnail(row.videoId, large = true),
                            badge = null,
                            progress = row.progress,
                            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                        )
                        Row {
                            Column(Modifier.weight(1f).padding(top = FeederTokens.Space.xs)) {
                                Text(
                                    row.item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = titleColor(row),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                row.views?.let {
                                    Text(
                                        stringResource(R.string.video_views, VideoDetails.compactCount(it)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            more()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(FeederTokens.Space.md))
        HorizontalDivider()
    }
}

/** List mode: thumbnail on the left, title, channel and views · age on the right. */
@Composable
fun VideoListRow(
    row: VideoRow,
    onClick: () -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
) {
    VideoPressable(row, onClick, actions, modifier.fillMaxWidth()) { press, more ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
            modifier =
                press
                    .fillMaxWidth()
                    .padding(start = FeederTokens.Space.lg, end = FeederTokens.Space.xs, top = FeederTokens.Space.sm, bottom = FeederTokens.Space.sm),
        ) {
            VideoThumbnail(
                url = youtubeThumbnail(row.videoId),
                badge = durationBadge(row),
                progress = row.progress,
                corner = FeederTokens.Radius.sm,
                modifier = Modifier.width(168.dp).aspectRatio(16f / 9f),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    row.item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor(row),
                    fontWeight = titleWeight(row),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.channel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    viewsAndAge(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            more()
        }
    }
}

/** Grid mode: a big thumbnail, then the channel's avatar beside title and meta, like Flow's cards. */
@Composable
fun VideoCard(
    row: VideoRow,
    onClick: () -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
) {
    VideoPressable(row, onClick, actions, modifier.fillMaxWidth()) { press, more ->
        Column(
            modifier =
                press
                    .fillMaxWidth()
                    .padding(bottom = FeederTokens.Space.lg),
        ) {
            VideoThumbnail(
                url = youtubeThumbnail(row.videoId, large = true),
                badge = durationBadge(row),
                progress = row.progress,
                corner = 0.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier = Modifier.padding(start = FeederTokens.Space.md, end = FeederTokens.Space.xs, top = FeederTokens.Space.md),
            ) {
                ChannelAvatar(row.channel, row.channelAvatar, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        row.item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor(row),
                        fontWeight = titleWeight(row),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(row.channel, viewsAndAge(row)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                more()
            }
        }
    }
}

/**
 * What can be done to the videos, which are Feeder articles: the same as in Feeds (Save, Mark
 * watched, Delete, Share, Read above/below), from a row's ⋮ menu or by long-pressing into
 * multi-select ([selection], shown in Feeds' selection bar).
 */
@Immutable
class VideoActions(
    val selection: FeedSelection,
    val onToggleSave: (VideoRow) -> Unit,
    val onToggleRead: (VideoRow) -> Unit,
    val onShare: (VideoRow) -> Unit,
    val onOpenInYouTube: (VideoRow) -> Unit,
    val onDelete: (VideoRow) -> Unit,
)

/** The YouTube tab's [VideoActions], through the same repository calls (and delete confirmation) as Feeds. */
@Composable
private fun rememberVideoActions(
    viewModel: YouTubeViewModel,
    state: YouTubeViewState,
    snackbar: SnackbarHostState,
): VideoActions {
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val undoText = stringResource(R.string.undo_delete_article)
    val deleteConfirmer = rememberDeleteConfirmer()
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(state.picked) { selectedIds = emptySet() }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    val rows = state.videos + state.shorts

    fun rowOf(id: Long) = rows.firstOrNull { it.item.id == id }

    fun deleteNow(ids: List<Long>) {
        scope.launch {
            val deleted = viewModel.deleteForGood(ids)
            snackbar.currentSnackbarData?.dismiss()
            val result =
                snackbar.showSnackbar(
                    message = resources.getQuantityString(R.plurals.n_articles_deleted_everywhere, ids.size, ids.size),
                    actionLabel = undoText,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(deleted)
        }
    }

    // As in Feeds: one video has Undo, so it goes without asking; several ask first
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) return deleteNow(ids)
        deleteConfirmer.ask(
            DeleteRequest(
                title = R.string.confirm_delete_title_articles,
                itemName = resources.getQuantityString(R.plurals.confirm_delete_n_articles, ids.size, ids.size),
                scope = DeleteScope.ALL_DEVICES,
                onConfirm = { deleteNow(ids) },
            ),
        )
    }

    fun share(row: VideoRow) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, youtubeWatchUrl(row.videoId))
                putExtra(Intent.EXTRA_SUBJECT, row.item.title)
            }
        context.startActivity(Intent.createChooser(intent, null))
    }

    val selection =
        FeedSelection(
            ids = selectedIds,
            onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
            onClear = { selectedIds = emptySet() },
            onMarkRead = { read ->
                viewModel.setRead(selectedIds, read)
                selectedIds = emptySet()
            },
            onDelete = {
                delete(selectedIds.toList())
                selectedIds = emptySet()
            },
            onMarkAboveAsRead = { id ->
                viewModel.markAboveAsRead(id)
                selectedIds = emptySet()
            },
            onMarkBelowAsRead = { id ->
                viewModel.markBelowAsRead(id)
                selectedIds = emptySet()
            },
            onShare = { id -> rowOf(id)?.let(::share) },
            onOpenInBrowser = { id -> rowOf(id)?.let { openInYouTubeApp(context, youtubeWatchUrl(it.videoId)) } },
            onSave = { save ->
                viewModel.setSaved(selectedIds, save)
                selectedIds = emptySet()
            },
            allSaved = selectedIds.isNotEmpty() && selectedIds.all { id -> rowOf(id)?.item?.bookmarked == true },
        )
    return VideoActions(
        selection = selection,
        onToggleSave = { row -> viewModel.setSaved(listOf(row.item.id), !row.item.bookmarked) },
        onToggleRead = { row -> viewModel.setRead(listOf(row.item.id), row.item.readTime == null) },
        onShare = ::share,
        onOpenInYouTube = { row -> openInYouTubeApp(context, youtubeWatchUrl(row.videoId)) },
        onDelete = { row -> delete(listOf(row.item.id)) },
    )
}

/** Watched videos look like read articles in Feeds: the title goes grey. */
@Composable
private fun titleColor(row: VideoRow) = if (row.item.readTime != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

private fun titleWeight(row: VideoRow) = if (row.item.readTime != null) FontWeight.Normal else FontWeight.Medium

/**
 * Tap opens the video; long press starts multi-select (then taps pick more). [content] gets the
 * modifier that handles the presses and the ⋮ button (Save, Mark watched, Share, Open in YouTube,
 * Delete) to place; every action is a TalkBack action too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPressable(
    row: VideoRow,
    onClick: () -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    content: @Composable (press: Modifier, more: @Composable () -> Unit) -> Unit,
) {
    val selection = actions.selection
    val selected = row.item.id in selection.ids
    val saved = row.item.bookmarked
    val read = row.item.readTime != null
    var menu by remember { mutableStateOf(false) }
    val saveLabel = stringResource(if (saved) R.string.item_action_unsave else R.string.action_save)
    val readLabel = stringResource(if (read) R.string.item_action_mark_unread else R.string.item_action_mark_watched)
    val shareLabel = stringResource(R.string.share)
    val openLabel = stringResource(R.string.open_in_youtube_app)
    val deleteLabel = stringResource(R.string.action_delete_article)
    val selectLabel = stringResource(R.string.select_article)
    val more: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val close = { menu = false }
                MenuEntry(saveLabel, if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, close) { actions.onToggleSave(row) }
                MenuEntry(readLabel, markReadIcon(read), close) { actions.onToggleRead(row) }
                MenuEntry(shareLabel, Icons.Default.Share, close) { actions.onShare(row) }
                MenuEntry(openLabel, Icons.Default.OpenInBrowser, close) { actions.onOpenInYouTube(row) }
                MenuEntry(selectLabel, Icons.Outlined.CheckCircle, close) { selection.onToggle(row.item.id) }
                HorizontalDivider()
                MenuEntry(deleteLabel, Icons.Outlined.Delete, close, danger = true) { actions.onDelete(row) }
            }
        }
    }
    Box(modifier) {
        content(
            Modifier
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .semantics {
                    this.selected = selected
                    customActions =
                        listOf(
                            CustomAccessibilityAction(saveLabel) {
                                actions.onToggleSave(row)
                                true
                            },
                            CustomAccessibilityAction(readLabel) {
                                actions.onToggleRead(row)
                                true
                            },
                            CustomAccessibilityAction(shareLabel) {
                                actions.onShare(row)
                                true
                            },
                            CustomAccessibilityAction(selectLabel) {
                                selection.onToggle(row.item.id)
                                true
                            },
                            CustomAccessibilityAction(deleteLabel) {
                                actions.onDelete(row)
                                true
                            },
                        )
                }.combinedClickable(
                    onClick = { if (selection.active) selection.onToggle(row.item.id) else onClick() },
                    onLongClick = { selection.onToggle(row.item.id) },
                ),
            more,
        )
    }
}

@Composable
private fun MenuEntry(
    label: String,
    icon: ImageVector,
    onClose: () -> Unit,
    danger: Boolean = false,
    action: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = {
            onClose()
            action()
        },
    )
}

@Composable
private fun EmptySubscriptions(onManage: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(FeederTokens.Space.xl),
    ) {
        Icon(Icons.Outlined.Subscriptions, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.youtube_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = FeederTokens.Space.lg),
        )
        Text(
            stringResource(R.string.youtube_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FeederTokens.Space.sm, bottom = FeederTokens.Space.lg),
        )
        Button(onClick = onManage) { Text(stringResource(R.string.youtube_add_channels)) }
    }
}

/** Rows whose streams are fetched ahead, from the first one on screen. */
private const val PREFETCH_ROWS = 3

/** Scrolling settles this long before prefetching: no requests for rows flung past. */
private const val PREFETCH_SETTLE_MS = 600L
