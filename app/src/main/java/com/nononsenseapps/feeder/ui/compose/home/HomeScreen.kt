package com.nononsenseapps.feeder.ui.compose.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.navigation.FeedsViewSwitch
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchTopBar
import com.nononsenseapps.feeder.ui.compose.pullrefresh.PullRefreshIndicator
import com.nononsenseapps.feeder.ui.compose.pullrefresh.pullRefresh
import com.nononsenseapps.feeder.ui.compose.pullrefresh.rememberPullRefreshState
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

private val LANE_TILE = 84.dp
private val ROW_ART = 48.dp

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenFeed: (Long) -> Unit,
    onOpenFolderArticles: (String) -> Unit,
    onAddFeed: () -> Unit,
    modifier: Modifier = Modifier,
    onManageFeeds: () -> Unit = {},
    onEditFeed: (Long) -> Unit = {},
) {
    val state by viewModel.viewState.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val folder = state.openFolder
    val pullRefreshState = rememberPullRefreshState(refreshing = syncing, onRefresh = viewModel::refreshAll)

    BackHandler(enabled = folder != null) { viewModel.openFolder(null) }

    Scaffold(
        modifier =
            modifier
                .pullRefresh(pullRefreshState)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            if (folder != null) {
                SensibleTopAppBar(
                    title = folder.ifBlank { stringResource(R.string.home_no_folder) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.openFolder(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                        }
                    },
                    actions = {
                        if (folder.isNotBlank()) {
                            TooltipIconButton(Icons.Default.DoneAll, stringResource(R.string.home_mark_folder_read)) {
                                viewModel.markFolderRead(folder)
                            }
                            TooltipIconButton(Icons.AutoMirrored.Filled.Article, stringResource(R.string.home_show_articles)) {
                                onOpenFolderArticles(folder)
                            }
                        }
                    },
                )
            } else {
                Column {
                    GlobalSearchTopBar(actions = { HomeMenu(state, viewModel, onManageFeeds) })
                    FeedsViewSwitch(view = com.nononsenseapps.feeder.ui.compose.navigation.FeedsView.BY_FEED)
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFeed,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_feed)) },
            )
        },
    ) { padding ->
        val tile: Dp =
            when (state.size) {
                HomeSize.S -> 80.dp
                HomeSize.M -> 100.dp
                HomeSize.L -> 140.dp
            }
        // Plain tile grids get side margins; lanes and list rows bring their own, edge to edge
        val padded = state.layout == HomeLayout.TILES && !state.asFolders
        val edge = if (padded) 0.dp else 16.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tile),
            contentPadding = PaddingValues(start = 16.dp - edge, end = 16.dp - edge, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            header("filters") { Filters(state, viewModel, edge) }
            homeBody(state, viewModel, onOpenFeed, onEditFeed, edge)
        }
        Box(Modifier.padding(padding).fillMaxSize()) {
            PullRefreshIndicator(syncing, pullRefreshState, Modifier.align(Alignment.TopCenter))
        }
    }
}

private fun LazyGridScope.header(
    key: String,
    content: @Composable () -> Unit,
) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.homeBody(
    state: HomeViewState,
    viewModel: HomeViewModel,
    onOpenFeed: (Long) -> Unit,
    onEditFeed: (Long) -> Unit,
    edge: Dp,
) {
    if (!state.loaded) return
    val shown = state.shown
    val list = state.layout == HomeLayout.LIST
    when {
        state.feeds.isEmpty() -> header("empty") { Message(stringResource(R.string.home_no_feeds)) }
        shown.isEmpty() ->
            header("nomatch") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Message(stringResource(R.string.home_no_match))
                    TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.home_clear_filters)) }
                }
            }
        state.asFolders -> {
            val (folders, loose) = shown.groupByFolder(state.sort)
            val sections = folders.map { it.name to it } + if (loose.isEmpty()) emptyList() else listOf("" to HomeFolder("", loose))
            for ((name, f) in sections) {
                if (list) {
                    val folded = name in state.folded
                    header("fh:$name") {
                        FolderHeader(f, folded = folded, foldable = true) { viewModel.toggleFolded(name) }
                    }
                    if (!folded) {
                        for (feed in f.feeds.take(LIST_FOLDER_MAX)) {
                            header("fr:$name:${feed.id}") { FeedRow(feed, { onEditFeed(feed.id) }) { onOpenFeed(feed.id) } }
                        }
                        if (f.feeds.size > LIST_FOLDER_MAX) {
                            header("fs:$name") { ShowAll(f.feeds.size) { viewModel.openFolder(name) } }
                        }
                    }
                } else {
                    header("lh:$name") { FolderHeader(f, folded = false, foldable = false) { viewModel.openFolder(name) } }
                    header("ln:$name") {
                        Lane(f, onAll = { viewModel.openFolder(name) }, onEditFeed = onEditFeed, onOpenFeed = onOpenFeed)
                    }
                }
            }
        }
        state.group == HomeGroup.TYPE && state.openFolder == null -> {
            val sorted = shown.sortHome(state.sort)
            for (kind in FeedKind.entries) {
                val ofKind = sorted.filter { it.kind == kind }
                if (ofKind.isEmpty()) continue
                header("kind:$kind") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = edge, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    ) {
                        Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(kind.label(), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text("${ofKind.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                feedItems(ofKind, list, "k$kind", onOpenFeed, onEditFeed)
            }
        }
        else -> {
            if (state.openFolder != null) {
                header("fsum") {
                    Text(
                        pluralStringResource(R.plurals.home_n_feeds, shown.size, shown.size) + " · " +
                            stringResource(R.string.home_unread_n, shown.sumOf { it.unread }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = edge, top = 4.dp, bottom = 8.dp),
                    )
                }
            }
            feedItems(shown.sortHome(state.sort), list, "all", onOpenFeed, onEditFeed)
        }
    }
}

/** Feeds as rows (list) or named tiles (grid cells). */
private fun LazyGridScope.feedItems(
    feeds: List<HomeFeed>,
    list: Boolean,
    keyPrefix: String,
    onOpenFeed: (Long) -> Unit,
    onEditFeed: (Long) -> Unit,
) {
    if (list) {
        for (feed in feeds) header("$keyPrefix:${feed.id}") { FeedRow(feed, { onEditFeed(feed.id) }) { onOpenFeed(feed.id) } }
    } else {
        items(feeds, key = { "$keyPrefix:${it.id}" }) { feed ->
            NamedTile(
                feed,
                size = null,
                onLongClick = { onEditFeed(feed.id) },
                modifier = Modifier.padding(bottom = 16.dp),
            ) { onOpenFeed(feed.id) }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun FeedKind.label(): String =
    stringResource(
        when (this) {
            FeedKind.PODCAST -> R.string.home_podcasts
            FeedKind.YOUTUBE -> R.string.home_youtube
            FeedKind.TEXT -> R.string.home_text
        },
    )

private fun FeedKind.icon(): ImageVector =
    when (this) {
        FeedKind.PODCAST -> Icons.Default.Headphones
        FeedKind.YOUTUBE -> Icons.Default.PlayArrow
        FeedKind.TEXT -> Icons.AutoMirrored.Filled.Article
    }

/** The ⋮ of the search bar: grouping, tile size, folding, the unread filter, Manage feeds and Refresh. */
@Composable
private fun HomeMenu(
    state: HomeViewState,
    viewModel: HomeViewModel,
    onManageFeeds: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.open_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun Choice(
                label: String,
                checked: Boolean,
                onClick: () -> Unit,
            ) = DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { if (checked) Icon(Icons.Default.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                onClick = {
                    open = false
                    onClick()
                },
            )

            MenuLabel(stringResource(R.string.home_group))
            Choice(stringResource(R.string.home_group_folders), state.group == HomeGroup.FOLDERS) { viewModel.setGroup(HomeGroup.FOLDERS) }
            Choice(stringResource(R.string.home_group_type), state.group == HomeGroup.TYPE) { viewModel.setGroup(HomeGroup.TYPE) }
            Choice(stringResource(R.string.home_group_none), state.group == HomeGroup.NONE) { viewModel.setGroup(HomeGroup.NONE) }
            if (state.layout == HomeLayout.TILES && state.group != HomeGroup.FOLDERS) {
                HorizontalDivider()
                MenuLabel(stringResource(R.string.home_tile_size))
                for (s in HomeSize.entries) Choice(s.name, state.size == s) { viewModel.setSize(s) }
            }
            HorizontalDivider()
            Choice(stringResource(R.string.home_only_unread), state.filter.unreadOnly) { viewModel.toggleUnreadOnly() }
            if (state.layout == HomeLayout.LIST && state.asFolders) {
                val names = state.feeds.map { it.tag }.distinct()
                val allFolded = names.isNotEmpty() && names.all { it in state.folded }
                Choice(
                    stringResource(if (allFolded) R.string.home_unfold_all else R.string.home_fold_all),
                    false,
                ) { viewModel.setAllFolded(names, fold = !allFolded) }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manage_feeds)) },
                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                onClick = {
                    open = false
                    onManageFeeds()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_refresh)) },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = {
                    open = false
                    viewModel.refreshAll()
                },
            )
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One line: type chips (All, podcasts, YouTube, text), then Sort and the Tiles / List switch. */
@Composable
private fun Filters(
    state: HomeViewState,
    viewModel: HomeViewModel,
    edge: Dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = edge, end = (edge - 12.dp).coerceAtLeast(0.dp), bottom = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            CountChip(state.filter.kind == null, stringResource(R.string.home_type_all), state.kindCount(null)) { viewModel.setKind(null) }
            for (kind in FeedKind.entries) {
                val count = state.kindCount(kind)
                if (count == 0 && state.filter.kind != kind) continue
                CountChip(state.filter.kind == kind, null, count, kind.icon(), kind.label()) {
                    viewModel.setKind(if (state.filter.kind == kind) null else kind)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        SortChip(state.sort, viewModel::setSort)
        val toList = state.layout == HomeLayout.TILES
        TooltipIconButton(
            if (toList) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
            stringResource(if (toList) R.string.home_view_list else R.string.home_view_tiles),
        ) { viewModel.setLayout(if (toList) HomeLayout.LIST else HomeLayout.TILES) }
    }
}

@Composable
private fun SortChip(
    sort: HomeSort,
    onSort: (HomeSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    fun label(s: HomeSort) =
        when (s) {
            HomeSort.LATEST -> R.string.home_sort_latest
            HomeSort.AZ -> R.string.home_sort_az
            HomeSort.UNREAD -> R.string.home_sort_unread
        }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(stringResource(label(sort))) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuLabel(stringResource(R.string.sort))
            for (s in HomeSort.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(label(s))) },
                    leadingIcon = { if (s == sort) Icon(Icons.Default.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onSort(s)
                    },
                )
            }
        }
    }
}

@Composable
private fun CountChip(
    selected: Boolean,
    label: String?,
    count: Int,
    icon: ImageVector? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        label = {
            if (label != null) {
                Text(label)
                Spacer(Modifier.width(6.dp))
            }
            Text("$count", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = if (description != null) Modifier.semantics { contentDescription = "$description $count" } else Modifier,
    )
}

/** A folder's title line: name, feed count and unread; opens the folder (tiles) or folds it (list). */
@Composable
private fun FolderHeader(
    folder: HomeFolder,
    folded: Boolean,
    foldable: Boolean,
    onClick: () -> Unit,
) {
    val name = folder.name.ifBlank { stringResource(R.string.home_no_folder) }
    val action =
        when {
            !foldable -> stringResource(R.string.home_open_folder, name)
            folded -> stringResource(R.string.home_unfold_folder, name)
            else -> stringResource(R.string.home_fold_folder, name)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = action, role = Role.Button, onClick = onClick)
                .padding(start = if (foldable) 8.dp else 16.dp, end = if (foldable) 16.dp else 8.dp, top = 14.dp, bottom = 8.dp)
                .semantics { heading() },
    ) {
        if (foldable) {
            Icon(
                if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        val count = " · " + pluralStringResource(R.plurals.home_n_feeds, folder.feeds.size, folder.feeds.size)
        Text(
            buildAnnotatedString {
                append(name)
                withStyle(
                    MaterialTheme.typography.bodySmall
                        .toSpanStyle()
                        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { append(count) }
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (folder.unread > 0) UnreadPill(folder.unread)
        if (!foldable) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnreadPill(count: Int) {
    Text(
        stringResource(R.string.home_unread_n, count),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A folder as a sideways row of tiles, ending in "All N" when it has more than [LANE_MAX]. */
@Composable
private fun Lane(
    folder: HomeFolder,
    onAll: () -> Unit,
    onEditFeed: (Long) -> Unit,
    onOpenFeed: (Long) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        items(folder.feeds.take(LANE_MAX), key = { it.id }) { feed ->
            NamedTile(feed, size = LANE_TILE, onLongClick = { onEditFeed(feed.id) }) { onOpenFeed(feed.id) }
        }
        if (folder.feeds.size > LANE_MAX) {
            item(key = "all") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .padding(top = 6.dp)
                            .size(LANE_TILE - 6.dp)
                            .clip(RoundedCornerShape(FeederTokens.Radius.md))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(FeederTokens.Radius.md))
                            .clickable(onClick = onAll),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.home_all_n, folder.feeds.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Artwork with an unread badge, then the name and "N new" under it. [size] null = fill the grid cell. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NamedTile(
    feed: HomeFeed,
    size: Dp?,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.home_feed_tile, feed.title, feed.unread)
    Column(
        modifier =
            modifier
                .then(if (size != null) Modifier.width(size) else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(FeederTokens.Radius.md))
                // Long-press opens the feed's settings
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .clearAndSetSemantics { contentDescription = description },
    ) {
        // Room above the artwork for the badge, which overhangs its corner
        Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
            Box(
                Modifier
                    .then(if (size != null) Modifier.size(size - 6.dp) else Modifier.fillMaxWidth().aspectRatio(1f))
                    .clip(RoundedCornerShape(FeederTokens.Radius.md)),
            ) {
                Art(feed, initialsSize = 26.sp)
                KindMark(feed.kind, Modifier.align(Alignment.BottomStart))
            }
            if (feed.unread > 0) Badge(feed.unread, Modifier.align(Alignment.TopEnd))
        }
        Text(
            feed.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, end = 6.dp),
        )
        Text(
            if (feed.unread > 0) stringResource(R.string.home_new_n, feed.unread) else stringResource(R.string.home_up_to_date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Small podcast / YouTube mark in a tile's corner; none for text feeds. */
@Composable
private fun KindMark(
    kind: FeedKind,
    modifier: Modifier = Modifier,
) {
    if (kind == FeedKind.TEXT) return
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .padding(4.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0x9E000000)),
    ) {
        Icon(kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** One feed as a list row: artwork, name, then unread · total · saved · in progress, and when it last posted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(
    feed: HomeFeed,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val unreadText = if (feed.unread > 0) stringResource(R.string.home_unread_n, feed.unread) else stringResource(R.string.home_all_read)
    val rest =
        listOfNotNull(
            stringResource(R.string.home_total_n, feed.total),
            if (feed.saved > 0) stringResource(R.string.home_saved_n, feed.saved) else null,
            if (feed.inProgress > 0) stringResource(R.string.home_in_progress_n, feed.inProgress) else null,
        ).joinToString(" · ")
    val age = shortAge(feed.latest)
    val description = stringResource(R.string.home_feed_row, feed.title, listOf(unreadText, rest, age).filter { it.isNotBlank() }.joinToString(", "))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .clearAndSetSemantics { contentDescription = description }
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(ROW_ART).clip(RoundedCornerShape(FeederTokens.Radius.md))) {
            Art(feed, initialsSize = 16.sp, showTitle = false)
            KindMark(feed.kind, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(feed.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    unreadText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (feed.unread > 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (feed.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    " · $rest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (age.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(age, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShowAll(
    count: Int,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick, modifier = Modifier.padding(start = 16.dp + ROW_ART + 2.dp)) {
            Text(stringResource(R.string.home_show_all_n, count))
        }
    }
}

/** Feed artwork, or the generated tile (initials on a color from the title) under it when there is none. */
@Composable
private fun Art(
    feed: HomeFeed,
    initialsSize: TextUnit,
    showTitle: Boolean = false,
) {
    val (bg, fg) = tileColors(feed.title)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(Color(bg))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            Text(
                initials(feed.title),
                color = Color(fg),
                fontWeight = FontWeight.ExtraBold,
                fontSize = initialsSize,
                maxLines = 1,
            )
            if (showTitle) {
                Text(
                    feed.title,
                    color = Color(fg).copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (feed.imageUrl != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(feed.imageUrl)
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Badge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                // Overhangs the artwork's corner, into the room the tile leaves for it
                .offset(x = 6.dp, y = (-6).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(if (count > 999) "999+" else "$count", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
