package com.nononsenseapps.feeder.ui.compose.youtube

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.db.room.YouTubeFeedItem
import com.nononsenseapps.feeder.db.room.YouTubeVideoMetaDao
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.youtube.Comment
import com.nononsenseapps.feeder.model.youtube.CommentPage
import com.nononsenseapps.feeder.model.youtube.InnerTube
import com.nononsenseapps.feeder.model.youtube.RelatedVideo
import com.nononsenseapps.feeder.model.youtube.VideoDetails
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.model.youtube.WatchNext
import com.nononsenseapps.feeder.model.youtube.YouTubePlayback
import com.nononsenseapps.feeder.model.youtube.YouTubeStreams
import com.nononsenseapps.feeder.model.youtube.YouTubeTiming
import com.nononsenseapps.feeder.model.youtube.youtubeWatchUrl
import com.nononsenseapps.feeder.ui.compose.editfeed.DeleteFeedDialog
import com.nononsenseapps.feeder.ui.compose.editfeed.FeedStats
import com.nononsenseapps.feeder.ui.compose.feedarticle.ChapterList
import com.nononsenseapps.feeder.ui.compose.feedarticle.DownloadVideoButton
import com.nononsenseapps.feeder.ui.compose.feedarticle.FlowVideo
import com.nononsenseapps.feeder.ui.compose.feedarticle.YouTubePlayer
import com.nononsenseapps.feeder.ui.compose.feedarticle.relativeDate
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.math.roundToInt

private const val LOG_TAG = "FEEDER_YT_PAGE"

private sealed interface Loaded {
    data object Loading : Loaded

    data class Ready(
        val details: VideoDetails,
    ) : Loaded

    data object Failed : Loaded
}

/**
 * Flow's video page: the video on top with Flow's controls, then title, views and age (tap for the
 * description), the channel, actions (likes, Save, Download, Background, Share), comments, and
 * related videos. The chevron, Back or a swipe down collapses it into the mini player while the
 * video keeps going. Plays with Feeder's engine: SponsorBlock, resume and synced positions.
 *
 * [itemId] is the Feeder article (0 for a related video not in Feeder's feeds).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeVideoPage(
    itemId: Long,
    videoId: String,
    viewModel: YouTubeViewModel,
    onCollapse: () -> Unit,
    onOpenVideo: (itemId: Long, videoId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HideMainBar()
    val di = LocalDI.current
    val dao: YouTubeVideoMetaDao by di.instance()
    val offlineVideos: OfflineVideos by di.instance()
    val innerTube: InnerTube by di.instance()
    val playback: YouTubePlayback by di.instance()
    val repository: Repository by di.instance()
    val videoPositions: VideoPositions by di.instance()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.viewState.collectAsState()

    val item by remember(itemId) { if (itemId > 0) dao.item(itemId) else flowOf(null) }.collectAsState(null)
    val articleKey = item?.let { VideoPositions.articleKey(it.feedUrl.toString(), it.guid) } ?: ""
    // Wait for the article before starting, so the player gets its key (and resume position)
    val itemReady = itemId <= 0 || item != null
    val playerKey = articleKey.ifEmpty { "embed:$videoId" }
    val title = item?.title ?: ""

    LaunchedEffect(itemId) {
        if (itemId > 0) {
            repository.markAsReadAndNotified(itemId)
            di.direct.instance<com.nononsenseapps.feeder.model.history.CentralHistoryRecorder>().itemOpened(itemId)
            runOnceSyncChainSendRead(di)
        }
    }

    val loaded by produceState<Loaded>(
        playback.state.value
            ?.takeIf { it.videoId == videoId }
            ?.let { Loaded.Ready(it.details) } ?: Loaded.Loading,
        videoId,
    ) {
        if (value is Loaded.Ready) return@produceState
        YouTubeTiming.start(videoId)
        value =
            try {
                Loaded.Ready(offlineVideos.playbackDetails(videoId).also { YouTubeTiming.log(videoId, "details ready (${it.source})") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "No streams for $videoId", e)
                Loaded.Failed
            }
    }
    val next by produceState<WatchNext?>(null, videoId) { value = innerTube.next(videoId) }
    // The next videos start at once too
    val streams: YouTubeStreams by di.instance()
    LaunchedEffect(next) {
        next?.related?.take(PREFETCH_RELATED)?.let { list -> streams.prefetch(list.map { it.videoId }) }
    }
    val firstComments by produceState<CommentPage?>(null, next?.commentsToken) {
        value = next?.commentsToken?.let { innerTube.comments(it) }
    }

    val index = state.videos.indexOfFirst { it.item.id == itemId }
    val onPrevious: (() -> Unit)? =
        state.videos
            .getOrNull(index - 1)
            ?.takeIf { index > 0 }
            ?.let { prev -> { onOpenVideo(prev.item.id, prev.videoId) } }
    val onNext: (() -> Unit)? =
        if (index >= 0) {
            state.videos.getOrNull(index + 1)?.let { n -> { onOpenVideo(n.item.id, n.videoId) } }
        } else {
            next?.related?.firstOrNull()?.let { r -> { onOpenVideo(0L, r.videoId) } }
        }
    val collapse = {
        playback.keepOnClose(playerKey)
        onCollapse()
    }

    BackHandler { collapse() }

    var showComments by rememberSaveable { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val collapseAt = with(density) { 120.dp.toPx() }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .offset { IntOffset(0, dragY.roundToInt().coerceAtLeast(0)) },
    ) {
        Box(
            Modifier
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // Swipe the video down to collapse it into the mini player, like Flow
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragY += it },
                    onDragStopped = {
                        if (dragY > collapseAt) collapse() else dragY = 0f
                    },
                ),
        ) {
            val current = loaded
            key(playerKey) {
                when {
                    !itemReady -> Unit
                    current is Loaded.Ready ->
                        FlowVideo(
                            key = playerKey,
                            videoId = videoId,
                            articleKey = articleKey,
                            itemId = itemId,
                            title = title.ifBlank { current.details.title },
                            details = current.details,
                            start = VideoPositions.resumeAt(videoPositions.get(articleKey)),
                            modifier = Modifier.fillMaxSize(),
                            onCollapse = collapse,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            autoPlay = true,
                        )
                    current is Loaded.Failed ->
                        YouTubePlayer(
                            videoId = videoId,
                            start = VideoPositions.resumeAt(videoPositions.get(articleKey)),
                            title = title,
                            onPosition = { t, d, stopped ->
                                if (articleKey.isNotEmpty()) {
                                    videoPositions.save(articleKey, t, d)
                                    if (stopped) runOnceSyncChainSendRead(di)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }
            }
        }

        // What yt-dlp's standby adds while it plays (chapters, followers) shows once it is in
        val playing by playback.state.collectAsState()
        val details = playing?.takeIf { it.videoId == videoId }?.details ?: (loaded as? Loaded.Ready)?.details
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "info") {
                VideoInfoSection(
                    title = title.ifBlank { details?.title ?: "" },
                    details = details,
                    item = item,
                    playerKey = playerKey,
                    next = next,
                    onUnsubscribe = { viewModel.unsubscribe(it) },
                    onSubscribe = { channelId -> viewModel.addChannel(channelId) },
                    statsFor = viewModel::statsFor,
                    onToggleSave = {
                        item?.let { i -> scope.launch { repository.setBookmarked(i.id, !i.bookmarked) } }
                    },
                    videoId = videoId,
                )
            }
            item(key = "comments") {
                firstComments?.takeIf { it.comments.isNotEmpty() }?.let { page ->
                    CommentsCard(page, onClick = { showComments = true })
                }
            }
            items(next?.related.orEmpty(), key = { "r:" + it.videoId }) { r ->
                RelatedCard(r, onClick = { onOpenVideo(0L, r.videoId) })
            }
        }
    }

    if (showComments) {
        val first = firstComments
        if (first != null) {
            ModalBottomSheet(
                onDismissRequest = { showComments = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            ) {
                CommentsSheet(first, innerTube)
            }
        }
    }
}

@Composable
private fun VideoInfoSection(
    title: String,
    details: VideoDetails?,
    item: YouTubeFeedItem?,
    playerKey: String,
    next: WatchNext?,
    onUnsubscribe: (Long) -> Unit,
    onSubscribe: (String) -> Unit,
    statsFor: suspend (Long) -> FeedStats,
    onToggleSave: () -> Unit,
    videoId: String,
) {
    val di = LocalDI.current
    val playback: YouTubePlayback by di.instance()
    val context = LocalContext.current
    val playState by playback.state.collectAsState()
    val current = playState?.takeIf { it.key == playerKey }
    var expanded by rememberSaveable(videoId) { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier = Modifier.fillMaxWidth().padding(top = FeederTokens.Space.md),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = FeederTokens.Space.lg),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta =
                listOfNotNull(
                    details?.viewCount?.let { stringResource(R.string.video_views, VideoDetails.compactCount(it)) },
                    relativeDate(details?.uploadDate) ?: item?.let { ageText(it.pubDate?.toInstant() ?: it.sortTime) },
                ).joinToString(" · ")
            Row(Modifier.padding(top = FeederTokens.Space.xs)) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    stringResource(if (expanded) R.string.video_less else R.string.video_more),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // Channel
        val channelName = item?.let { it.feedCustomTitle.ifBlank { it.feedTitle } } ?: next?.channelName ?: details?.channel ?: ""
        if (channelName.isNotBlank()) {
            ChannelRow(
                name = channelName,
                avatar = next?.channelAvatar ?: item?.feedImageUrl?.toString(),
                subscribers = next?.subscribersText ?: details?.channelFollowers?.let { stringResource(R.string.video_subscribers, VideoDetails.compactCount(it)) },
                feedId = item?.feedId,
                onUnsubscribe = onUnsubscribe,
                onSubscribe = next?.channelId?.let { id -> { onSubscribe(id) } },
                statsFor = statsFor,
            )
        }

        // Actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FeederTokens.Space.lg),
        ) {
            val likes = next?.likesText ?: details?.likeCount?.let { VideoDetails.compactCount(it) }
            if (likes != null) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp).padding(horizontal = FeederTokens.Space.md)) {
                        Icon(Icons.Outlined.ThumbUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(likes, style = MaterialTheme.typography.labelLarge)
                        VerticalDivider(Modifier.padding(horizontal = FeederTokens.Space.md).height(20.dp))
                        Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.youtube_dislike), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (item != null) {
                FilterChip(
                    selected = item.bookmarked,
                    onClick = onToggleSave,
                    leadingIcon = { Icon(if (item.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(stringResource(if (item.bookmarked) R.string.action_saved else R.string.action_save)) },
                )
                item.link?.let { link -> DownloadVideoButton(item.id, link, PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
            }
            if (current != null) {
                FilterChip(
                    selected = current.background,
                    onClick = { playback.setBackground(!current.background) },
                    leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.video_background)) },
                )
            }
            AssistChip(
                onClick = {
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, youtubeWatchUrl(videoId))
                            putExtra(Intent.EXTRA_SUBJECT, title)
                        }
                    context.startActivity(Intent.createChooser(intent, null))
                },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.share)) },
            )
            AssistChip(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, youtubeWatchUrl(videoId).toUri())) }
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text(stringResource(R.string.open_in_youtube_app)) },
            )
        }

        if (expanded && details != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                modifier = Modifier.padding(horizontal = FeederTokens.Space.lg),
            ) {
                if (details.description.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(FeederTokens.Radius.md)) {
                        Text(details.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(FeederTokens.Space.md))
                    }
                }
                if (details.chapters.isNotEmpty()) {
                    Text(stringResource(R.string.player_chapters), style = MaterialTheme.typography.titleMedium)
                    Column {
                        ChapterList(details.chapters, null) { chapter ->
                            if (current != null) {
                                playback.seekTo(chapter.start)
                                playback.player.value?.play()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(FeederTokens.Space.xs))
    }
}

@Composable
private fun ChannelRow(
    name: String,
    avatar: String?,
    subscribers: String?,
    feedId: Long?,
    onUnsubscribe: (Long) -> Unit,
    onSubscribe: (() -> Unit)?,
    statsFor: suspend (Long) -> FeedStats,
) {
    val currentStatsFor by rememberUpdatedState(statsFor)
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier = Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.lg),
    ) {
        ChannelAvatar(name, avatar, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subscribers != null) {
                Text(subscribers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (feedId != null) {
            Box {
                FilledTonalButton(onClick = { menu = true }) {
                    Text(stringResource(R.string.youtube_subscribed))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.youtube_unsubscribe), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            confirm = true
                        },
                    )
                }
            }
        } else if (onSubscribe != null) {
            Button(onClick = onSubscribe) { Text(stringResource(R.string.youtube_subscribe)) }
        }
    }
    if (confirm && feedId != null) {
        val stats by produceState<FeedStats?>(null, feedId) { value = currentStatsFor(feedId) }
        DeleteFeedDialog(
            feedTitle = name,
            stats = stats,
            onConfirm = {
                confirm = false
                onUnsubscribe(feedId)
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun CommentsCard(
    page: CommentPage,
    onClick: () -> Unit,
) {
    val top = page.comments.first()
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(FeederTokens.Radius.md),
        modifier = Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
    ) {
        Column(Modifier.padding(FeederTokens.Space.md)) {
            Text(
                page.countText ?: stringResource(R.string.youtube_comments),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                modifier = Modifier.padding(top = FeederTokens.Space.sm),
            ) {
                ChannelAvatar(top.author.removePrefix("@"), top.avatar, 24.dp)
                Text(top.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CommentsSheet(
    first: CommentPage,
    innerTube: InnerTube,
) {
    var comments by remember { mutableStateOf(first.comments) }
    var nextToken by remember { mutableStateOf(first.next) }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.distinctUntilChanged()
            .collect { last ->
                val token = nextToken
                if (!loading && token != null && last >= comments.size - 5) {
                    loading = true
                    val page = innerTube.comments(token)
                    if (page != null) {
                        comments = (comments + page.comments).distinctBy { it.id }
                        nextToken = page.next
                    } else {
                        nextToken = null
                    }
                    loading = false
                }
            }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            first.countText ?: stringResource(R.string.youtube_comments),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
        )
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(comments, key = { it.id }) { c -> CommentRow(c) }
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(FeederTokens.Space.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
        modifier = Modifier.fillMaxWidth().padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.md),
    ) {
        ChannelAvatar(c.author.removePrefix("@"), c.avatar, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(
                listOf(c.author, c.published).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.lg),
                modifier = Modifier.padding(top = FeederTokens.Space.xs),
            ) {
                if (c.likes.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ThumbUp, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(c.likes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (c.replies.isNotBlank() && c.replies != "0") {
                    Text(
                        stringResource(R.string.youtube_replies, c.replies),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** A related video as a big card with the channel's avatar and a ⋮ menu (Share, Open in YouTube). */
@Composable
private fun RelatedCard(
    r: RelatedVideo,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = FeederTokens.Space.sm, bottom = FeederTokens.Space.md),
    ) {
        VideoThumbnail(
            url = r.thumbnail ?: youtubeThumbnail(r.videoId, large = true),
            badge = r.durationText,
            progress = 0f,
            corner = 0.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
            modifier = Modifier.padding(start = FeederTokens.Space.md, top = FeederTokens.Space.md),
        ) {
            ChannelAvatar(r.channel, r.channelAvatar, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(r.channel, r.viewsText, r.ageText).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.open_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        onClick = {
                            menu = false
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, youtubeWatchUrl(r.videoId))
                                }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_in_youtube_app)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        onClick = {
                            menu = false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, youtubeWatchUrl(r.videoId).toUri())) }
                        },
                    )
                }
            }
        }
    }
}

/** Related videos whose streams are fetched while this one plays. */
private const val PREFETCH_RELATED = 2
