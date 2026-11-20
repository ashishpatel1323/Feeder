package com.nononsenseapps.feeder.ui.compose.feed

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.ThumbnailImage
import com.nononsenseapps.feeder.ui.compose.empty.NothingToRead
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedScreenViewState
import com.nononsenseapps.feeder.ui.compose.feedarticle.TranslatedFeedCards
import com.nononsenseapps.feeder.util.ActivityLauncher
import com.nononsenseapps.feeder.util.stripTrackingParameters
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicBoolean

private const val FLIP_PREFS = "feeder_flip_mode"
private const val FLIP_PREFS_ENABLED = "enabled"

// Smaller images look bad stretched over a whole page, so those articles are laid out like text-only ones
private const val MIN_FLIP_IMAGE_SIZE = 300

private val FlipGreen = Color(0xFF1B6D3C)
private val FlipGreenDark = Color(0xFF0B3A20)
private val FlipBackground = Color(0xFF0D0F0E)

/** Whether the article list is shown as flip pages instead of a list. One setting for every feed. */
@Stable
class FlipModePreference(
    private val context: Context,
) {
    var enabled by mutableStateOf(
        context.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE).getBoolean(FLIP_PREFS_ENABLED, false),
    )
        private set

    fun set(value: Boolean) {
        enabled = value
        context
            .getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FLIP_PREFS_ENABLED, value)
            .apply()
    }
}

@Composable
fun rememberFlipModePreference(): FlipModePreference {
    val context = LocalContext.current.applicationContext
    return remember(context) { FlipModePreference(context) }
}

internal fun ThumbnailImage.isLargeEnoughForFlip(): Boolean =
    (width ?: MIN_FLIP_IMAGE_SIZE) >= MIN_FLIP_IMAGE_SIZE &&
        (height ?: MIN_FLIP_IMAGE_SIZE) >= MIN_FLIP_IMAGE_SIZE / 2

/**
 * Splits the list into flip pages, keeping the list order. An article with a large image gets a page
 * of its own; articles without one are paired two to a page.
 *
 * Returns the item indices on each page.
 */
internal fun <T> buildFlipPages(
    items: List<T>,
    hasLargeImage: (T) -> Boolean,
): List<List<Int>> {
    val pages = mutableListOf<List<Int>>()
    var pending: Int? = null
    items.forEachIndexed { index, item ->
        if (hasLargeImage(item)) {
            pending?.let { pages.add(listOf(it)) }
            pending = null
            pages.add(listOf(index))
        } else {
            val waiting = pending
            if (waiting == null) {
                pending = index
            } else {
                pages.add(listOf(waiting, index))
                pending = null
            }
        }
    }
    pending?.let { pages.add(listOf(it)) }
    return pages
}

@Composable
fun FeedFlipContent(
    viewState: FeedScreenViewState,
    pagedFeedItems: LazyPagingItems<FeedListItem>,
    translatedFeedCards: TranslatedFeedCards,
    onTranslateFeedCard: (FeedListItem) -> Unit,
    markAsUnread: (Long, Boolean) -> Unit,
    onSetBookmark: (Long, Boolean) -> Unit,
    onItemClick: (Long) -> Unit,
    onOpenNavDrawer: () -> Unit,
    onAddFeed: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteItem: ((Long) -> Unit)? = null,
) {
    if (!viewState.haveVisibleFeedItems) {
        Box(modifier = modifier.fillMaxSize()) {
            NothingToRead(
                modifier = Modifier,
                onOpenOtherFeed = onOpenNavDrawer,
                onAddFeed = onAddFeed,
            )
        }
        return
    }

    val activityLauncher: ActivityLauncher by LocalDI.current.instance()
    val haptics = LocalHapticFeedback.current

    val snapshot = pagedFeedItems.itemSnapshotList
    val pages =
        remember(snapshot) {
            buildFlipPages(snapshot) { item -> item?.image?.isLargeEnoughForFlip() == true }
        }
    val currentPages by rememberUpdatedState(pages)
    val pagerState = rememberPagerState { currentPages.size }

    // Articles the user chose to keep unread here, so flipping past them does not mark them read again
    var keptUnread by rememberSaveable { mutableStateOf(LongArray(0)) }
    // First article on the page being read. When marking articles as read removes them from the
    // list (Unread tab), the pages shift and the pager is moved back to this article.
    var anchorId by rememberSaveable { mutableLongStateOf(ID_UNSET) }

    fun idsOnPage(page: Int): List<FeedListItem> =
        currentPages.getOrNull(page).orEmpty().mapNotNull { index ->
            if (index < pagedFeedItems.itemCount) pagedFeedItems.peek(index) else null
        }

    // Set while the pager is moved back to the anchor, which is not the user flipping
    val anchorCorrection = remember { AtomicBoolean(false) }

    LaunchedEffect(pagerState) {
        var previous = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (anchorCorrection.getAndSet(false)) {
                previous = page
                return@collect
            }
            if (page != previous) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            // Flipping forward marks the pages that were flipped past as read
            if (page > previous) {
                for (passed in previous until page) {
                    idsOnPage(passed).forEach { item ->
                        if (item.unread && item.id !in keptUnread) {
                            markAsUnread(item.id, false)
                        }
                    }
                }
            }
            previous = page
            idsOnPage(page).firstOrNull()?.let { anchorId = it.id }
        }
    }

    LaunchedEffect(pages) {
        if (anchorId == ID_UNSET || pagerState.isScrollInProgress) return@LaunchedEffect
        val anchorPage =
            pages.indexOfFirst { page ->
                page.any { index -> index < pagedFeedItems.itemCount && pagedFeedItems.peek(index)?.id == anchorId }
            }
        if (anchorPage >= 0 && anchorPage != pagerState.settledPage) {
            anchorCorrection.set(true)
            pagerState.scrollToPage(anchorPage)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(FlipBackground),
    ) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val indices = currentPages.getOrNull(pageIndex).orEmpty()
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                indices.forEach { index ->
                    // get() rather than peek() so paging loads more articles as the user flips
                    val loadedItem = if (index < pagedFeedItems.itemCount) pagedFeedItems[index] else null
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        if (loadedItem == null) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(FlipBackground),
                            )
                        } else {
                            LaunchedEffect(
                                loadedItem.id,
                                loadedItem.title,
                                loadedItem.snippet,
                                translatedFeedCards.generation,
                                onTranslateFeedCard,
                            ) {
                                onTranslateFeedCard(loadedItem)
                            }
                            val item = translatedFeedCards.merge(loadedItem)
                            val kept = item.id in keptUnread
                            FlipArticleCard(
                                item = item,
                                compact = indices.size > 1,
                                keptUnread = kept,
                                onOpen = { onItemClick(item.id) },
                                onToggleKeepUnread = {
                                    if (kept) {
                                        keptUnread = keptUnread.filter { it != item.id }.toLongArray()
                                    } else {
                                        keptUnread = keptUnread + item.id
                                        if (!item.unread) {
                                            markAsUnread(item.id, true)
                                        }
                                    }
                                },
                                onToggleBookmark = { onSetBookmark(item.id, !item.bookmarked) },
                                onDelete = onDeleteItem?.let { delete -> { delete(item.id) } },
                                onShare = {
                                    val intent =
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                if (item.link != null) {
                                                    putExtra(Intent.EXTRA_TEXT, stripTrackingParameters(item.link))
                                                }
                                                putExtra(Intent.EXTRA_TITLE, item.title)
                                                type = "text/plain"
                                            },
                                            null,
                                        )
                                    activityLauncher.startActivity(
                                        openAdjacentIfSuitable = false,
                                        intent = intent,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (pages.isNotEmpty()) {
            Text(
                text = "${pagerState.currentPage + 1} / ${pages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FlipArticleCard(
    item: FeedListItem,
    compact: Boolean,
    keptUnread: Boolean,
    onOpen: () -> Unit,
    onToggleKeepUnread: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: (() -> Unit)?,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = item.image?.takeIf { it.isLargeEnoughForFlip() }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clickable(onClick = onOpen),
    ) {
        if (image != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(image.url)
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .build(),
                contentDescription = stringResource(id = R.string.article_image),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(FlipGreenDark),
            )
            // Keeps the white text readable on any photo
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.35f),
                                0.22f to Color.Transparent,
                                0.40f to Color.Transparent,
                                0.80f to Color.Black.copy(alpha = 0.88f),
                                1f to Color.Black.copy(alpha = 0.92f),
                            ),
                        ),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(FlipGreen, FlipGreenDark))),
            )
            Text(
                text = "“",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 80.sp else 120.sp,
                color = Color.White.copy(alpha = 0.13f),
                modifier = Modifier.padding(start = 20.dp, top = if (compact) 0.dp else 40.dp),
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = if (compact) 14.dp else 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.feedImageUrl?.let { favicon ->
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(LocalContext.current)
                                .data(favicon.toString())
                                .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = listOf(item.feedTitle, item.pubDate).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.87f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.unread) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFA5F4B8)),
                    )
                }
            }
            Text(
                text = item.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 20.sp else 28.sp,
                lineHeight = if (compact) 25.sp else 33.sp,
                color = Color.White,
                maxLines = if (compact) 3 else 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && item.snippet.isNotBlank()) {
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassAction(
                    icon = Icons.Outlined.MarkEmailUnread,
                    label = stringResource(R.string.action_unread),
                    onClick = onToggleKeepUnread,
                    selected = keptUnread || !item.unread,
                )
                GlassAction(
                    icon = if (item.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    label = stringResource(if (item.bookmarked) R.string.action_saved else R.string.action_save),
                    onClick = onToggleBookmark,
                    selected = item.bookmarked,
                )
                if (onDelete != null) {
                    GlassAction(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.action_delete_article),
                        onClick = onDelete,
                        contentColor = Color(0xFFFFB4AB),
                    )
                }
                if (!compact) {
                    GlassAction(
                        icon = Icons.Default.Share,
                        label = null,
                        contentDescription = stringResource(R.string.share),
                        onClick = onShare,
                    )
                }
            }
        }
    }
}

/** A translucent pill button that reads well on top of a photo. */
@Composable
private fun RowScope.GlassAction(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
    contentColor: Color = Color.White,
    contentDescription: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f),
        contentColor = if (selected) Color(0xFF0E1F13) else contentColor,
        modifier =
            if (label != null) {
                Modifier
                    .weight(1f)
                    .height(40.dp)
            } else {
                Modifier.size(40.dp)
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (label == null) contentDescription else null,
                modifier = Modifier.size(18.dp),
            )
            if (label != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
