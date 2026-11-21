package com.nononsenseapps.feeder.ui.compose.feed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.ThumbnailImage
import com.nononsenseapps.feeder.ui.compose.empty.NothingToRead
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedScreenViewState
import com.nononsenseapps.feeder.ui.compose.feedarticle.TranslatedFeedCards
import com.nononsenseapps.feeder.util.ActivityLauncher
import com.nononsenseapps.feeder.util.stripTrackingParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private const val FLIP_PREFS = "feeder_flip_mode"
private const val FLIP_PREFS_ENABLED = "enabled"

// Smaller images look bad stretched over a whole page, so those articles are laid out like text-only ones
private const val MIN_FLIP_IMAGE_SIZE = 300

private val FlipGreen = Color(0xFF1B6D3C)
private val FlipGreenDark = Color(0xFF0B3A20)
private val FlipBackground = Color(0xFF0D0F0E)
private val FlipAccent = Color(0xFFA5F4B8)
private val FlipDelete = Color(0xFFFF6B6B)
private val FlipSummary = Color(0xFFE9DDFF)
private val FlipSummaryOn = Color(0xFF2A1A5E)

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
 * Splits the list into flip pages, keeping the list order. Every article gets a page of its own, so
 * there is room for its text.
 *
 * Returns the item indices on each page.
 */
internal fun <T> buildFlipPages(items: List<T>): List<List<Int>> = items.indices.map { listOf(it) }

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
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteItem: ((Long) -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    // Full screen until the user leaves flip mode
    ImmersiveSystemBars()
    BackHandler(onBack = onExit)

    if (!viewState.haveVisibleFeedItems) {
        Box(modifier = modifier.fillMaxSize()) {
            NothingToRead(
                modifier = Modifier,
                onOpenOtherFeed = onOpenNavDrawer,
                onAddFeed = onAddFeed,
            )
            FlipCloseButton(
                onExit = onExit,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // Keeps Undo reachable after deleting the last article
            if (snackbarHostState != null) {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                )
            }
        }
        return
    }

    val activityLauncher: ActivityLauncher by LocalDI.current.instance()
    val flipCardTexts: FlipCardTexts by LocalDI.current.instance()
    val haptics = LocalHapticFeedback.current

    val snapshot = pagedFeedItems.itemSnapshotList
    val pages =
        remember(snapshot) {
            buildFlipPages(snapshot)
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
            PageTurn(
                // How far this page is from the settled position: 0 on screen, 1 flipped away above,
                // -1 waiting below. Read in the draw phase so flipping does not recompose.
                offset = { (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction },
            ) {
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
                                    translated = item.snippet != loadedItem.snippet || item.title != loadedItem.title,
                                    flipCardTexts = flipCardTexts,
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
        }

        // Thin reading progress line along the top edge
        if (pages.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth((pagerState.currentPage + 1).toFloat() / pages.size)
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.85f)),
            )
        }

        // Delete shows its Undo snackbar here, as the list screen's host is not composed in flip view
        if (snackbarHostState != null) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)),
        ) {
            FlipCloseButton(onExit = onExit, tint = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            if (pages.isNotEmpty()) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier =
                        Modifier
                            .padding(end = 14.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FlipCloseButton(
    onExit: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onExit,
        modifier = modifier.padding(6.dp),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.exit_flip_view),
            tint = tint,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(6.dp)
                    .size(20.dp),
        )
    }
}

/** Hides the status and navigation bars while in composition; a swipe from the edge shows them briefly. */
@Composable
private fun ImmersiveSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private val TopHalf =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
    }

private val BottomHalf =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline = Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
    }

/**
 * Turns pages like paper folded at the middle. Every page is pinned in place (the pager would slide
 * it) and drawn as two halves. A page above the current one (offset > 0) swings its bottom half up
 * over the fold; a page below (offset < 0) swings its top half down. Each moving half disappears
 * once it is edge-on, where the neighbouring page's half takes over, so a flip reads as one leaf
 * turning over. The halves under the turning leaf are shaded to give it depth.
 */
@Composable
private fun PageTurn(
    offset: () -> Float,
    content: @Composable () -> Unit,
) {
    // The page whose leaf is turning has to draw above its neighbours
    val onTop by remember { derivedStateOf { abs(offset()) < 0.5f } }
    val density = LocalDensity.current.density
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(if (onTop) 1f else 0f)
                .graphicsLayer {
                    val o = offset()
                    translationY = o * size.height
                    alpha = if (abs(o) >= 1f) 0f else 1f
                },
    ) {
        for (top in listOf(true, false)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val o = offset()
                            val moving = if (top) o < 0f else o > 0f
                            cameraDistance = 16f * density
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            clip = true
                            shape = if (top) TopHalf else BottomHalf
                            rotationX = if (moving) 180f * o else 0f
                            alpha = if (moving && abs(o) >= 0.5f) 0f else 1f
                        },
            ) {
                content()
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val o = abs(offset())
                                val moving = if (top) offset() < 0f else offset() > 0f
                                alpha = if (moving) (o * 0.9f).coerceAtMost(0.45f) else o * 0.55f
                            }.background(Color.Black),
                )
            }
        }
    }
}

/** What the body of a card shows below the title. */
private sealed interface CardBody {
    data object Text : CardBody

    data object Summarizing : CardBody

    data class Summary(
        val bullets: List<String>,
    ) : CardBody

    data class Failed(
        val message: String,
    ) : CardBody
}

@Composable
private fun FlipArticleCard(
    item: FeedListItem,
    translated: Boolean,
    flipCardTexts: FlipCardTexts,
    keptUnread: Boolean,
    onOpen: () -> Unit,
    onToggleKeepUnread: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: (() -> Unit)?,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = item.image?.takeIf { it.isLargeEnoughForFlip() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The feed snippet is cut at 200 characters, so the longer article text is loaded for the card.
    // A translated card keeps its translated snippet.
    var bodyText by remember(item.id, translated, item.snippet) {
        mutableStateOf(if (translated) item.snippet else flipCardTexts.cachedText(item.id) ?: item.snippet)
    }
    if (!translated) {
        LaunchedEffect(item.id, item.snippet) {
            bodyText = flipCardTexts.cardText(itemId = item.id, link = item.link, fallback = item.snippet)
        }
    }
    var body by remember(item.id) { mutableStateOf<CardBody>(CardBody.Text) }
    val failedText = stringResource(R.string.flip_summary_failed)

    fun summarize() {
        val cached = flipCardTexts.cachedSummary(item.id)
        if (cached != null) {
            body = CardBody.Summary(cached)
            return
        }
        body = CardBody.Summarizing
        scope.launch {
            body =
                try {
                    CardBody.Summary(
                        flipCardTexts.summarize(
                            itemId = item.id,
                            title = item.title,
                            link = item.link,
                            fallback = bodyText,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CardBody.Failed(failedText.format(e.message ?: ""))
                }
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(FlipBackground)
                .clickable(onClick = onOpen),
    ) {
        // The photo takes about a third of the page, leaving room for the article text below it
        val photoHeight = maxHeight * 0.36f
        if (image != null) {
            val request =
                remember(image.url) {
                    ImageRequest
                        .Builder(context)
                        .data(image.url)
                        .precision(Precision.INEXACT)
                        .build()
                }
            // A blurred copy tints the rest of the page with the photo's colours
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .blur(28.dp),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
            )
            AsyncImage(
                model = request,
                contentDescription = stringResource(id = R.string.article_image),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(photoHeight),
            )
            // Keeps the white text readable: dark under the controls at the top, then the photo
            // fades into near black where the text starts
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.30f),
                                0.10f to Color.Transparent,
                                0.24f to Color.Transparent,
                                0.38f to Color.Black.copy(alpha = 0.90f),
                                1f to Color.Black.copy(alpha = 0.96f),
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
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        // Text-only cards start right under the close button; image cards just above
                        // the bottom of the photo
                        top = if (image != null) photoHeight - 36.dp else 64.dp,
                        // Clear of the gesture area at the bottom of the screen
                        bottom = 28.dp,
                    ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
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
                                .background(FlipAccent),
                    )
                }
            }
            Text(
                text = item.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = if (image != null) 25.sp else 27.sp,
                lineHeight = if (image != null) 31.sp else 33.sp,
                color = Color.White,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // Fills the space between the title and the buttons; text that does not fit is cut off
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .padding(horizontal = 4.dp),
            ) {
                when (val current = body) {
                    CardBody.Text ->
                        Text(
                            text = bodyText,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = Color.White.copy(alpha = 0.86f),
                            overflow = TextOverflow.Ellipsis,
                        )

                    CardBody.Summarizing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = FlipSummary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.summarizing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.86f),
                            )
                        }

                    is CardBody.Summary ->
                        SummaryBullets(bullets = current.bullets)

                    is CardBody.Failed ->
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.86f),
                        )
                }
            }
            // Always visible, so the summary is one tap away
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (body) {
                    CardBody.Text ->
                        CardPill(
                            label = stringResource(R.string.summarize),
                            icon = Icons.Outlined.AutoAwesome,
                            filled = true,
                            onClick = ::summarize,
                        )

                    CardBody.Summarizing ->
                        CardPill(
                            label = stringResource(R.string.summarizing),
                            icon = Icons.Outlined.AutoAwesome,
                            filled = false,
                            onClick = {},
                        )

                    is CardBody.Summary ->
                        CardPill(
                            label = stringResource(R.string.show_text),
                            icon = Icons.Outlined.AutoAwesome,
                            filled = false,
                            onClick = { body = CardBody.Text },
                        )

                    is CardBody.Failed ->
                        CardPill(
                            label = stringResource(R.string.flip_summary_retry),
                            icon = Icons.Outlined.AutoAwesome,
                            filled = true,
                            onClick = ::summarize,
                        )
                }
                CardPill(
                    label = stringResource(R.string.read_full_article),
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    iconAfter = true,
                    filled = false,
                    contentColor = FlipAccent,
                    onClick = onOpen,
                )
            }
            // One frosted bar, so the actions stay readable on any photo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xCC1A1D1B))
                        .padding(4.dp),
            ) {
                BarAction(
                    icon = Icons.Outlined.MarkEmailUnread,
                    label = stringResource(R.string.action_unread),
                    onClick = onToggleKeepUnread,
                    selected = keptUnread || !item.unread,
                )
                BarAction(
                    icon = if (item.bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    label = stringResource(if (item.bookmarked) R.string.action_saved else R.string.action_save),
                    onClick = onToggleBookmark,
                    selected = item.bookmarked,
                )
                if (onDelete != null) {
                    BarAction(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.action_delete_article),
                        onClick = onDelete,
                        contentColor = FlipDelete,
                    )
                }
                BarAction(
                    icon = Icons.Default.Share,
                    label = null,
                    contentDescription = stringResource(R.string.share),
                    onClick = onShare,
                )
            }
        }
    }
}

@Composable
private fun SummaryBullets(bullets: List<String>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.22f))
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = FlipSummary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.flip_summary_label),
                style = MaterialTheme.typography.labelMedium,
                color = FlipSummary,
            )
        }
        bullets.forEach { bullet ->
            Row {
                Box(
                    modifier =
                        Modifier
                            .padding(top = 8.dp, end = 10.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(FlipSummary),
                )
                Text(
                    text = bullet,
                    fontSize = 15.5.sp,
                    lineHeight = 23.sp,
                    color = Color.White,
                )
            }
        }
    }
}

/** A rounded button under the card text. */
@Composable
private fun CardPill(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    iconAfter: Boolean = false,
    contentColor: Color = if (filled) FlipSummaryOn else FlipSummary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .height(40.dp)
                .clip(CircleShape)
                .background(if (filled) FlipSummary else Color.White.copy(alpha = 0.10f))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
    ) {
        if (!iconAfter) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
        )
        if (iconAfter) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
    }
}

/** A button in the frosted action bar: white on dark, highlighted when on. */
@Composable
private fun RowScope.BarAction(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
    contentColor: Color = Color.White,
    contentDescription: String? = null,
) {
    val color = if (selected) FlipAccent else contentColor
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            (if (label != null) Modifier.weight(1f) else Modifier.width(48.dp))
                .height(44.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (label == null) contentDescription else null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        if (label != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
