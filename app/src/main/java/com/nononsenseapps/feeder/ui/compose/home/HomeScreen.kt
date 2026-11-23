package com.nononsenseapps.feeder.ui.compose.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenFeed: (Long) -> Unit,
    onOpenFolderArticles: (String) -> Unit,
    onOpenArticles: () -> Unit,
    onAddFeed: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaylists: () -> Unit = {},
    onDiscover: () -> Unit = {},
) {
    val state by viewModel.viewState.collectAsStateWithLifecycle()
    val folder = state.openFolder

    BackHandler(enabled = folder != null) { viewModel.openFolder(null) }

    Scaffold(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SensibleTopAppBar(
                title = folder ?: stringResource(R.string.home),
                navigationIcon = {
                    if (folder != null) {
                        IconButton(onClick = { viewModel.openFolder(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
                        }
                    }
                },
                actions = {
                    if (folder != null) {
                        LabeledAction(Icons.AutoMirrored.Filled.Article, stringResource(R.string.home_show_articles)) { onOpenFolderArticles(folder) }
                    } else {
                        LabeledAction(Icons.AutoMirrored.Filled.List, stringResource(R.string.home_articles), onOpenArticles)
                    }
                    LabeledAction(Icons.Default.Explore, stringResource(R.string.discover), onDiscover)
                    LabeledAction(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.playlists), onOpenPlaylists)
                    LabeledAction(Icons.Default.Refresh, stringResource(R.string.home_refresh), viewModel::refreshAll)
                },
            )
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
                HomeSize.S -> 72.dp
                HomeSize.M -> 96.dp
                HomeSize.L -> 140.dp
            }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tile),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            header("filters") { Filters(state, viewModel) }
            homeBody(state, viewModel, onOpenFeed)
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
) {
    if (!state.loaded) return
    val shown = state.shown
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
            items(folders, key = { "folder:${it.name}" }) { FolderTile(it) { viewModel.openFolder(it.name) } }
            items(loose, key = { it.id }) { FeedTile(it) { onOpenFeed(it.id) } }
        }
        state.group == HomeGroup.TYPE && state.openFolder == null -> {
            val sorted = shown.sortHome(state.sort)
            for (kind in FeedKind.entries) {
                val list = sorted.filter { it.kind == kind }
                if (list.isEmpty()) continue
                header("kind:$kind") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(kind.label(), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        Text("${list.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.id }) { FeedTile(it) { onOpenFeed(it.id) } }
            }
        }
        else -> items(shown.sortHome(state.sort), key = { it.id }) { FeedTile(it) { onOpenFeed(it.id) } }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@Composable
private fun LabeledAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

@Composable
private fun Filters(
    state: HomeViewState,
    viewModel: HomeViewModel,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            CountChip(state.filter.kind == null, stringResource(R.string.home_type_all), state.kindCount(null)) { viewModel.setKind(null) }
            for (kind in FeedKind.entries) {
                CountChip(state.filter.kind == kind, kind.label(), state.kindCount(kind), kind.icon()) {
                    viewModel.setKind(if (state.filter.kind == kind) null else kind)
                }
            }
            CountChip(state.filter.unreadOnly, stringResource(R.string.home_has_unread), state.unreadCount) { viewModel.toggleUnreadOnly() }
        }
        if (state.openFolder == null) {
            val folders = state.folderCounts
            if (folders.any { it.first.isNotBlank() }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for ((name, count) in folders) {
                        CountChip(
                            state.filter.folder == name,
                            name.ifBlank { stringResource(R.string.home_no_folder) },
                            count,
                            if (name.isBlank()) null else Icons.Default.Folder,
                        ) { viewModel.toggleFolderFilter(name) }
                    }
                }
            }
        }
        val shown = state.shown
        val summary =
            when {
                state.openFolder != null -> stringResource(R.string.home_in_folder, shown.size, state.openFolder)
                state.asFolders -> {
                    val (folders, loose) = shown.groupByFolder(state.sort)
                    stringResource(R.string.home_folders_summary, folders.size, loose.size)
                }
                else -> stringResource(R.string.home_feeds_count, shown.size)
            }
        Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        ) {
            if (state.openFolder == null) {
                Segmented(
                    HomeGroup.entries,
                    state.group,
                    {
                        stringResource(
                            when (it) {
                                HomeGroup.FOLDERS -> R.string.home_group_folders
                                HomeGroup.NONE -> R.string.home_group_none
                                HomeGroup.TYPE -> R.string.home_group_type
                            },
                        )
                    },
                    viewModel::setGroup,
                )
            }
            Segmented(
                HomeSort.entries,
                state.sort,
                {
                    stringResource(
                        when (it) {
                            HomeSort.LATEST -> R.string.home_sort_latest
                            HomeSort.AZ -> R.string.home_sort_az
                            HomeSort.UNREAD -> R.string.home_sort_unread
                        },
                    )
                },
                viewModel::setSort,
            )
            Segmented(HomeSize.entries, state.size, { it.name }, viewModel::setSize)
        }
    }
}

@Composable
private fun CountChip(
    selected: Boolean,
    label: String,
    count: Int,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) } },
        label = {
            Text(label)
            Spacer(Modifier.width(6.dp))
            Text("$count", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(label(option), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

/** Feed artwork, or the generated tile (initials on a color from the title) under it when there is none. */
@Composable
private fun Art(
    feed: HomeFeed,
    mini: Boolean = false,
) {
    val (bg, fg) = tileColors(feed.title)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(Color(bg))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
            Text(
                initials(feed.title),
                color = Color(fg),
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (mini) 11.sp else 26.sp,
                maxLines = 1,
            )
            if (!mini) {
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
                .padding(4.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(if (count > 999) "999+" else "$count", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeedTile(
    feed: HomeFeed,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.home_feed_tile, feed.title, feed.unread)
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .shadow(2.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        Box(Modifier.clearAndSetSemantics { }) { Art(feed) }
        if (feed.unread > 0) Badge(feed.unread, Modifier.align(Alignment.TopEnd))
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x9E000000)),
        ) {
            Icon(
                feed.kind.icon(),
                contentDescription = null,
                tint =
                    when (feed.kind) {
                        FeedKind.YOUTUBE -> Color(0xFFFF7070)
                        FeedKind.PODCAST -> Color(0xFFCDB2FF)
                        FeedKind.TEXT -> Color(0xFFA6D8FF)
                    },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun FolderTile(
    folder: HomeFolder,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.home_folder_tile, folder.name, folder.feeds.size, folder.unread)
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .shadow(2.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Color(tileColors(folder.name).first))
                .clickable(onClick = onClick)
                .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val four = folder.feeds.take(4)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                        for (col in 0..1) {
                            val f = four.getOrNull(row * 2 + col)
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(3.dp)),
                            ) {
                                if (f != null) Art(f, mini = true)
                            }
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            ) {
                Text(
                    folder.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Text("${folder.feeds.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
        if (folder.unread > 0) Badge(folder.unread, Modifier.align(Alignment.TopEnd))
    }
}
