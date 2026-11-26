package com.nononsenseapps.feeder.ui.compose.youtube

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.YouTubeStreams
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
                        contentPadding = PaddingValues(bottom = FeederTokens.Space.lg),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                GroupChips(
                                    groups = state.groups,
                                    selected = state.group,
                                    onSelect = viewModel::selectGroup,
                                    onEdit = { editGroups = true },
                                )
                                RefreshStatus(state)
                            }
                        }
                        if (state.shorts.isNotEmpty()) {
                            item(key = "shorts", span = { GridItemSpan(maxLineSpan) }) {
                                ShortsShelf(state.shorts, open)
                            }
                        }
                        items(state.videos, key = { it.item.id }) { row ->
                            if (state.grid) {
                                VideoCard(row, onClick = { open(row) })
                            } else {
                                VideoListRow(row, onClick = { open(row) })
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
    selected: String?,
    onSelect: (String?) -> Unit,
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
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.youtube_group_all)) })
        groups.forEach { g ->
            FilterChip(selected = selected == g, onClick = { onSelect(g) }, label = { Text(g) })
        }
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
) {
    Column(Modifier.padding(top = FeederTokens.Space.sm)) {
        Text(
            stringResource(R.string.youtube_shorts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            contentPadding = PaddingValues(horizontal = FeederTokens.Space.lg),
        ) {
            items(shorts, key = { it.item.id }) { row ->
                VideoPressable(row, onClick = { onOpen(row) }, modifier = Modifier.width(132.dp)) { press ->
                    Column(press) {
                        VideoThumbnail(
                            url = youtubeThumbnail(row.videoId, large = true),
                            badge = null,
                            progress = row.progress,
                            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                        )
                        Text(
                            row.item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = FeederTokens.Space.xs),
                        )
                        row.views?.let {
                            Text(
                                stringResource(R.string.video_views, VideoDetails.compactCount(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
    modifier: Modifier = Modifier,
) {
    VideoPressable(row, onClick, modifier.fillMaxWidth()) { press ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
            modifier =
                press
                    .fillMaxWidth()
                    .padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
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
        }
    }
}

/** Grid mode: a big thumbnail, then the channel's avatar beside title and meta, like Flow's cards. */
@Composable
fun VideoCard(
    row: VideoRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoPressable(row, onClick, modifier.fillMaxWidth()) { press ->
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
                modifier = Modifier.padding(start = FeederTokens.Space.md, end = FeederTokens.Space.lg, top = FeederTokens.Space.md),
            ) {
                ChannelAvatar(row.channel, row.channelAvatar, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(row.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(row.channel, viewsAndAge(row)).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Tap opens the video; long press offers Share (also a TalkBack action). [content] gets the
 * modifier that handles the presses.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPressable(
    row: VideoRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val shareLabel = stringResource(R.string.share)
    val share = {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, youtubeWatchUrl(row.videoId))
                putExtra(Intent.EXTRA_SUBJECT, row.item.title)
            }
        context.startActivity(Intent.createChooser(intent, null))
    }
    Box(modifier) {
        content(
            Modifier
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(shareLabel) {
                                share()
                                true
                            },
                        )
                }.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(shareLabel) }, onClick = {
                menu = false
                share()
            })
        }
    }
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
