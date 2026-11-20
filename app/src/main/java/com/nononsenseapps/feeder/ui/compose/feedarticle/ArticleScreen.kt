package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.TextToDisplay
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.localtranslation.BergamotModelDownloadProgress
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.ReadAloudNeighbors
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.offline.isYouTubeLink
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.ScrollDirection
import com.nononsenseapps.feeder.ui.compose.components.TranslationProgressContent
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.html.ColumnArticleContent
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleSwipeDirection
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechFollowViewport
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechHighlight
import com.nononsenseapps.feeder.ui.compose.readaloud.WordModeState
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnAccessibilityScroll
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnUserScroll
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import com.nononsenseapps.feeder.ui.compose.utils.ScreenType
import com.nononsenseapps.feeder.util.ActivityLauncher
import com.nononsenseapps.feeder.util.stripTrackingParameters
import com.nononsenseapps.feeder.util.unicodeWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ArticleScreen(
    onNavigateUp: () -> Unit,
    onNavigateToFeed: (Long) -> Unit,
    viewModel: ArticleViewModel,
    mainActivityViewModel: MainActivityViewModel,
    modifier: Modifier = Modifier,
    onNavigateToArticle: (Long) -> Unit = {},
    onSwipeToArticle: (Long, ArticleSwipeDirection) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onNavigateUp)
    val di = LocalDI.current
    val activityLauncher: ActivityLauncher by di.instance()

    val mavm = remember { mainActivityViewModel }
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val readAloudProgress = viewModel.readAloudProgress
    val speechHighlight by viewModel.speechHighlight.collectAsStateWithLifecycle(initialValue = null)
    val isReadingAloudHere by viewModel.isReadingAloudHere.collectAsStateWithLifecycle(initialValue = false)
    val isPagingMode by mavm.isPagingMode.collectAsStateWithLifecycle()
    val isAnimatedPaging by mavm.isAnimatedPaging.collectAsStateWithLifecycle()
    val showOriginalPage by viewModel.showOriginalPage.collectAsStateWithLifecycle()
    val swipeNeighbors by viewModel.swipeNeighbors.collectAsStateWithLifecycle()
    val readAloudNeighbors by viewModel.readAloudNeighbors.collectAsStateWithLifecycle()
    val readAloudAutoContinue by viewModel.readAloudAutoContinue.collectAsStateWithLifecycle()
    val fastReading by viewModel.fastReading.collectAsStateWithLifecycle()
    val wordMode by viewModel.wordMode.collectAsStateWithLifecycle()

    // Previous / next and auto-continue in read aloud open the article being read
    val currentOnSwipeToArticle by rememberUpdatedState(onSwipeToArticle)
    LaunchedEffect(viewModel) {
        viewModel.readAloudOpenArticle.collect { (articleId, direction) ->
            if (articleId != viewModel.itemId) currentOnSwipeToArticle(articleId, direction)
        }
    }

    val articleScrollState = rememberScrollState(initial = viewModel.scrollPosition)

    LaunchedEffect(articleScrollState) {
        snapshotFlow { articleScrollState.value }
            .debounce(500.milliseconds)
            .collect { viewModel.saveScrollPosition(it) }
    }

    LaunchedEffect(Unit) {
        mavm.scrollCommand.collect { direction ->
            viewModel.ttsStopFollowing()
            val scrollAmount = (articleScrollState.viewportSize * 0.9f).toInt()
            when (direction) {
                ScrollDirection.UP -> {
                    val target = (articleScrollState.value - scrollAmount).coerceAtLeast(0)
                    if (isAnimatedPaging) {
                        articleScrollState.animateScrollTo(target)
                    } else {
                        articleScrollState.scrollTo(target)
                    }
                }

                ScrollDirection.DOWN -> {
                    val target = (articleScrollState.value + scrollAmount).coerceAtMost(articleScrollState.maxValue)
                    if (isAnimatedPaging) {
                        articleScrollState.animateScrollTo(target)
                    } else {
                        articleScrollState.scrollTo(target)
                    }
                }
            }
        }
    }

    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    val context = LocalContext.current

    ArticleScreen(
        viewState = viewState,
        onToggleFullText = viewModel::toggleFullText,
        onMarkAsUnread = viewModel::markAsUnread,
        onShare = {
            if (viewState.articleId > ID_UNSET) {
                val intent =
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            val articleLink = viewState.articleLink
                            if (articleLink != null) {
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    stripTrackingParameters(articleLink),
                                )
                            }
                            putExtra(Intent.EXTRA_TITLE, viewState.articleTitle)
                            type = "text/plain"
                        },
                        null,
                    )
                activityLauncher.startActivity(
                    openAdjacentIfSuitable = false,
                    intent = intent,
                )
            }
        },
        onOpenInCustomTab = {
            viewState.articleLink?.let { link ->
                activityLauncher.openLinkInCustomTab(link, toolbarColor)
            }
        },
        onFeedTitleClick = {
            onNavigateToFeed(viewState.articleFeedId)
        },
        onOpenAudioPlayer = viewModel::openPodcastPlayer,
        ttsOnPlay = viewModel::ttsPlay,
        ttsOnPause = viewModel::ttsPause,
        ttsOnStop = viewModel::ttsStop,
        ttsOnSkipNext = viewModel::ttsSkipNext,
        ttsOnSelectLanguage = viewModel::ttsOnSelectLanguage,
        readAloudProgress = readAloudProgress,
        speechHighlight = speechHighlight,
        ttsOnSeekTo = viewModel::ttsSeekTo,
        ttsOnSetSpeed = viewModel::ttsSetSpeed,
        ttsDiagnostics = viewModel::ttsDiagnostics,
        isReadingAloud = isReadingAloudHere,
        ttsOnReadFrom = viewModel::ttsReadFrom,
        ttsOnUserScroll = viewModel::ttsStopFollowing,
        ttsOnGoToSpokenText = {
            viewModel.ttsFocusSpokenText()?.let { articleId ->
                if (articleId != viewState.articleId) onNavigateToArticle(articleId)
            }
        },
        podcastOnPlay = viewModel::podcastPlay,
        podcastOnPause = viewModel::podcastPause,
        podcastOnStop = viewModel::stopPodcastPlayback,
        podcastOnSeekBack = { viewModel.podcastSeekBy(-10_000) },
        podcastOnSeekForward = { viewModel.podcastSeekBy(10_000) },
        podcastOnSeekTo = viewModel::podcastSeekTo,
        onToggleBookmark = {
            viewModel.setBookmarked(!viewState.isBookmarked)
        },
        onDelete = {
            viewModel.deleteForGood()
            Toast.makeText(context, R.string.article_deleted_everywhere, Toast.LENGTH_SHORT).show()
            onNavigateUp()
        },
        articleScrollState = articleScrollState,
        onNavigateUp = onNavigateUp,
        onSummarize = {
            viewModel.summarize()
        },
        onTranslate = {
            viewModel.translate()
        },
        onOpenSystemTranslationSettings = {
            activityLauncher.startActivity(
                openAdjacentIfSuitable = false,
                intent = Intent(Settings.ACTION_SETTINGS),
            )
            viewModel.dismissSystemTranslationSettingsPrompt()
        },
        onDismissSystemTranslationSettings = viewModel::dismissSystemTranslationSettingsPrompt,
        onDownloadBergamotRuntime = viewModel::downloadBergamotRuntimeAndTranslate,
        onDismissBergamotRuntimeDownload = viewModel::dismissBergamotRuntimeDownloadPrompt,
        modifier = modifier,
        isPagingMode = isPagingMode,
        isAnimatedPaging = isAnimatedPaging,
        showOriginalPage = showOriginalPage,
        onToggleOriginalPage = viewModel::toggleOriginalPage,
        onOriginalPageText = viewModel::setOriginalPageText,
        onOpenLink = { link -> activityLauncher.openLinkInCustomTab(link, toolbarColor) },
        swipeNeighbors = swipeNeighbors,
        onSwipeToArticle = onSwipeToArticle,
        readAloudNeighbors = readAloudNeighbors,
        ttsOnSkipArticle = viewModel::ttsSkipArticle,
        readAloudAutoContinue = readAloudAutoContinue,
        ttsOnSetAutoContinue = viewModel::ttsSetAutoContinue,
        onSpeedRead = viewModel::startSpeedReading,
        wordMode = wordMode,
        onSetShowWord = viewModel::setShowWord,
        onSetMuted = viewModel::setVoiceMuted,
        onSetWpm = viewModel::speedReadSetWpm,
        onSkipWords = viewModel::speedReadSkipWords,
        onJumpToWord = viewModel::speedReadJumpToWord,
        fastReading = fastReading,
        onToggleFastReading = { viewModel.setFastReading(!fastReading) },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun ArticleScreen(
    viewState: ArticleScreenViewState,
    onToggleFullText: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onShare: () -> Unit,
    onOpenInCustomTab: () -> Unit,
    onFeedTitleClick: () -> Unit,
    onOpenAudioPlayer: (url: String) -> Unit,
    ttsOnPlay: () -> Unit,
    ttsOnPause: () -> Unit,
    ttsOnStop: () -> Unit,
    ttsOnSkipNext: () -> Unit,
    ttsOnSelectLanguage: (LocaleOverride) -> Unit,
    podcastOnPlay: () -> Unit,
    podcastOnPause: () -> Unit,
    podcastOnStop: () -> Unit,
    podcastOnSeekBack: () -> Unit,
    podcastOnSeekForward: () -> Unit,
    podcastOnSeekTo: (Int) -> Unit,
    onToggleBookmark: () -> Unit,
    articleScrollState: ScrollState,
    onDelete: () -> Unit = {},
    onNavigateUp: () -> Unit,
    onSummarize: () -> Unit,
    onTranslate: () -> Unit,
    onOpenSystemTranslationSettings: () -> Unit,
    onDismissSystemTranslationSettings: () -> Unit,
    onDownloadBergamotRuntime: () -> Unit,
    onDismissBergamotRuntimeDownload: () -> Unit,
    modifier: Modifier = Modifier,
    readAloudProgress: StateFlow<ReadAloudProgress>? = null,
    speechHighlight: SpeechHighlight? = null,
    ttsOnSeekTo: (Long) -> Unit = {},
    ttsOnSetSpeed: (Float) -> Unit = {},
    ttsDiagnostics: () -> String = { "" },
    isReadingAloud: Boolean = false,
    ttsOnReadFrom: ((speechOffset: Int) -> Unit)? = null,
    ttsOnGoToSpokenText: () -> Unit = {},
    ttsOnUserScroll: () -> Unit = {},
    isPagingMode: Boolean = false,
    isAnimatedPaging: Boolean = false,
    showOriginalPage: Boolean = false,
    onToggleOriginalPage: () -> Unit = {},
    onOriginalPageText: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    swipeNeighbors: ArticleSwipeNeighbors = ArticleSwipeNeighbors(),
    onSwipeToArticle: (Long, ArticleSwipeDirection) -> Unit = { _, _ -> },
    readAloudNeighbors: ReadAloudNeighbors = ReadAloudNeighbors(),
    ttsOnSkipArticle: (next: Boolean) -> Unit = {},
    readAloudAutoContinue: Boolean = false,
    ttsOnSetAutoContinue: (Boolean) -> Unit = {},
    onSpeedRead: () -> Unit = {},
    wordMode: WordModeState = WordModeState(),
    onSetShowWord: ((Boolean) -> Unit)? = null,
    onSetMuted: ((Boolean) -> Unit)? = null,
    onSetWpm: (Int) -> Unit = {},
    onSkipWords: (Int) -> Unit = {},
    onJumpToWord: (Int) -> Unit = {},
    fastReading: Boolean = false,
    onToggleFastReading: () -> Unit = {},
) {
    // While reading aloud the bottom bar is the player and the article actions move to the top bar
    val readingAloud = viewState.isBottomBarVisible && !viewState.podcastPlayerState.isVisible
    // The top bar always holds article actions, so it never scrolls away
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Once the title has scrolled away, the top bar shows it with Save, Unread and Delete
    val headerHeightPx = with(LocalDensity.current) { 280.dp.toPx() }
    val scrolledPastHeader by remember {
        derivedStateOf { articleScrollState.value > headerHeightPx }
    }
    val showCollapsedBar = scrolledPastHeader && !showOriginalPage && !readingAloud
    // The original page has no chips under the title, so its top bar always holds Save, Unread and Delete
    val showArticleActionsInBar = showCollapsedBar || (showOriginalPage && !readingAloud)

    val bottomBarVisibleState = remember { MutableTransitionState(viewState.isBottomBarVisible) }
    LaunchedEffect(viewState.isBottomBarVisible) {
        bottomBarVisibleState.targetState = viewState.isBottomBarVisible
    }

    val focusArticle = remember { FocusRequester() }
    val focusTopBar = remember { FocusRequester() }

    Scaffold(
        modifier =
            modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Column {
                SensibleTopAppBar(
                    modifier =
                        Modifier
                            .focusGroup()
                            .focusRequester(focusTopBar)
                            .focusProperties {
                                down = focusArticle
                            },
                    scrollBehavior = scrollBehavior,
                    title = if (showCollapsedBar) viewState.articleTitle else viewState.feedDisplayTitle,
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.go_back),
                            )
                        }
                    },
                    actions = {
                        if (readingAloud) {
                            // Up to eight actions share the top bar while reading aloud, so they use 40 dp touch targets
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                                TopBarAction(Icons.AutoMirrored.Filled.Article, stringResource(R.string.fetch_full_article), onToggleFullText)
                                if (!viewState.articleLink.isNullOrBlank()) {
                                    TopBarAction(
                                        Icons.Default.Language,
                                        stringResource(if (showOriginalPage) R.string.show_reader_view else R.string.show_original_page),
                                        onToggleOriginalPage,
                                    )
                                }
                                TopBarAction(Icons.Default.OpenInBrowser, stringResource(R.string.open_in_web_view), onOpenInCustomTab)
                                ArticleTopBarActions(
                                    isBookmarked = viewState.isBookmarked,
                                    showSummarize = viewState.showSummarize,
                                    showTranslate = viewState.showTranslate,
                                    isShowingTranslated = viewState.isShowingTranslated,
                                    onShare = onShare,
                                    onMarkAsUnread = onMarkAsUnread,
                                    onSummarize = onSummarize,
                                    onToggleBookmark = onToggleBookmark,
                                    onTranslate = onTranslate,
                                    onDelete = onDelete,
                                )
                            }
                        } else if (showArticleActionsInBar) {
                            if (showOriginalPage) {
                                TopBarAction(Icons.Default.Share, stringResource(R.string.share), onShare)
                            }
                            TopBarAction(
                                if (viewState.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                stringResource(if (viewState.isBookmarked) R.string.action_saved else R.string.action_save),
                                onToggleBookmark,
                            )
                            TopBarAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.action_unread), onMarkAsUnread)
                            TopBarAction(
                                Icons.Outlined.Delete,
                                stringResource(R.string.action_delete_article),
                                onDelete,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            if (viewState.showTranslate) {
                                TopBarAction(
                                    Icons.Default.Translate,
                                    stringResource(if (viewState.isShowingTranslated) R.string.action_original else R.string.action_translate),
                                    onTranslate,
                                )
                            }
                            TopBarAction(Icons.Default.OpenInBrowser, stringResource(R.string.open_in_web_view), onOpenInCustomTab)
                            TopBarAction(Icons.Default.Share, stringResource(R.string.share), onShare)
                        }
                    },
                )
                if (showCollapsedBar) {
                    LinearProgressIndicator(
                        progress = {
                            val max = articleScrollState.maxValue
                            if (max > 0) articleScrollState.value.toFloat() / max else 0f
                        },
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (readingAloud) {
                // One bar: the player replaces the action bar and takes the navigation bar inset itself
                HideableCompactTTSPlayer(
                    visibleState = bottomBarVisibleState,
                    currentlyPlaying = viewState.isTTSPlaying,
                    onPlay = ttsOnPlay,
                    onPause = ttsOnPause,
                    onStop = ttsOnStop,
                    onSkipNext = ttsOnSkipNext,
                    languages = ImmutableHolder(viewState.ttsLanguages),
                    onSelectLanguage = ttsOnSelectLanguage,
                    progress = readAloudProgress,
                    onSeekTo = ttsOnSeekTo,
                    onSelectSpeed = ttsOnSetSpeed,
                    onGoToSpokenText = ttsOnGoToSpokenText,
                    displayedArticleId = viewState.articleId,
                    diagnostics = ttsDiagnostics,
                    neighbors = readAloudNeighbors,
                    onSkipArticle = ttsOnSkipArticle,
                    autoContinue = readAloudAutoContinue,
                    onSetAutoContinue = ttsOnSetAutoContinue,
                    wordMode = wordMode,
                    onSetShowWord = onSetShowWord,
                    onSetMuted = onSetMuted,
                    onSetWpm = onSetWpm,
                    onSkipWords = onSkipWords,
                    onJumpToWord = onJumpToWord,
                )
            } else {
                Column {
                    // The action bar below takes the navigation bar inset, so the player must not add it again
                    Box(modifier = Modifier.consumeWindowInsets(WindowInsets.navigationBars)) {
                        if (viewState.podcastPlayerState.isVisible) {
                            HideablePodcastPlayer(
                                visibleState = bottomBarVisibleState,
                                viewState = viewState.podcastPlayerState,
                                onPlay = podcastOnPlay,
                                onPause = podcastOnPause,
                                onStop = podcastOnStop,
                                onSeekBack = podcastOnSeekBack,
                                onSeekForward = podcastOnSeekForward,
                                onSeekTo = podcastOnSeekTo,
                            )
                        }
                    }
                    ReaderToolbar(
                        isTTSPlaying = viewState.isTTSPlaying,
                        onListen = { if (viewState.isTTSPlaying) ttsOnPause() else ttsOnPlay() },
                        fastReading = fastReading,
                        onToggleFastReading = onToggleFastReading,
                        onSpeedRead = onSpeedRead,
                        showSummarize = viewState.showSummarize,
                        onSummarize = onSummarize,
                        onToggleFullText = onToggleFullText,
                        showOriginalPageToggle = !viewState.articleLink.isNullOrBlank(),
                        showOriginalPage = showOriginalPage,
                        onToggleOriginalPage = onToggleOriginalPage,
                    )
                }
            }
        },
    ) { padding ->
        val originalPageLink = viewState.articleLink?.takeIf { showOriginalPage && it.isNotBlank() }
        if (originalPageLink != null) {
            // Keyed on the link so a different article loads a fresh page
            key(originalPageLink) {
                OriginalPageView(
                    url = originalPageLink,
                    speechHighlight = speechHighlight,
                    isReadingAloud = isReadingAloud,
                    onPageText = onOriginalPageText,
                    onReadFrom = { offset -> ttsOnReadFrom?.invoke(offset) },
                    onUserScroll = ttsOnUserScroll,
                    onOpenLink = onOpenLink,
                    modifier = Modifier.padding(padding),
                )
            }
        } else {
            // Box handles the dynamic padding so ArticleContent don't have to recompose on scroll
            ArticleSwipeContainer(
                neighbors = swipeNeighbors,
                onSwipeTo = onSwipeToArticle,
                modifier = Modifier.padding(padding),
            ) {
                SpeechFollowViewport(
                    centerSpeech = speechHighlight?.followSpokenText == true,
                    modifier = Modifier.stopSpeechFollowingOnUserScroll(ttsOnUserScroll),
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    ArticleContent(
                        viewState = viewState,
                        screenType = ScreenType.SINGLE,
                        speechHighlight = speechHighlight,
                        isReadingAloud = isReadingAloud,
                        onReadAloudFrom = ttsOnReadFrom,
                        fastReading = fastReading,
                        articleScrollState = articleScrollState,
                        onUserScroll = ttsOnUserScroll,
                        onFeedTitleClick = onFeedTitleClick,
                        onOpenAudioPlayer = onOpenAudioPlayer,
                        onOpenInCustomTab = onOpenInCustomTab,
                        onBookmark = onToggleBookmark,
                        onMarkAsUnread = onMarkAsUnread,
                        onDelete = onDelete,
                        modifier =
                            Modifier
                                .focusGroup()
                                .focusRequester(focusArticle)
                                .focusProperties {
                                    up = focusTopBar
                                },
                    )

                    if (isPagingMode) {
                        Row(modifier = Modifier.matchParentSize()) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(0.2f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            val scrollAmount = (articleScrollState.viewportSize * 0.9f).toInt()
                                            val target = (articleScrollState.value - scrollAmount).coerceAtLeast(0)
                                            ttsOnUserScroll()
                                            coroutineScope.launch {
                                                if (isAnimatedPaging) {
                                                    articleScrollState.animateScrollTo(target)
                                                } else {
                                                    articleScrollState.scrollTo(target)
                                                }
                                            }
                                        },
                            )
                            Spacer(modifier = Modifier.weight(0.6f))
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(0.2f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            val scrollAmount = (articleScrollState.viewportSize * 0.9f).toInt()
                                            val target = (articleScrollState.value + scrollAmount).coerceAtMost(articleScrollState.maxValue)
                                            ttsOnUserScroll()
                                            coroutineScope.launch {
                                                if (isAnimatedPaging) {
                                                    articleScrollState.animateScrollTo(target)
                                                } else {
                                                    articleScrollState.scrollTo(target)
                                                }
                                            }
                                        },
                            )
                        }
                    }
                }
            }
        }
    }

    if (viewState.systemTranslationSettingsMessage.isNotBlank()) {
        SystemTranslationSettingsDialog(
            message = viewState.systemTranslationSettingsMessage,
            onOpenSettings = onOpenSystemTranslationSettings,
            onDismiss = onDismissSystemTranslationSettings,
        )
    }
    if (viewState.showBergamotRuntimeDownloadPrompt) {
        BergamotRuntimeDownloadDialog(
            onDownload = onDownloadBergamotRuntime,
            onDismiss = onDismissBergamotRuntimeDownload,
        )
    }
}

@Composable
private fun BergamotRuntimeDownloadDialog(
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bergamot_runtime_download_title)) },
        text = { Text(stringResource(R.string.bergamot_runtime_download_warning)) },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SystemTranslationSettingsDialog(
    message: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
fun ArticleContent(
    viewState: ArticleScreenViewState,
    screenType: ScreenType,
    onFeedTitleClick: () -> Unit,
    onOpenAudioPlayer: (url: String) -> Unit,
    onOpenInCustomTab: () -> Unit,
    onBookmark: () -> Unit,
    onMarkAsUnread: () -> Unit,
    articleScrollState: ScrollState,
    modifier: Modifier = Modifier,
    speechHighlight: SpeechHighlight? = null,
    onUserScroll: (() -> Unit)? = null,
    isReadingAloud: Boolean = false,
    onReadAloudFrom: ((speechOffset: Int) -> Unit)? = null,
    fastReading: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    val context = LocalContext.current
    val activityLauncher: ActivityLauncher by LocalDI.current.instance()
    val coroutineScope = rememberCoroutineScope()

    // Track Y positions of article elements by index for anchor link scrolling
    val elementPositions = remember { mutableMapOf<Int, Float>() }
    val contentImageUrls = remember(viewState.articleContent) { viewState.articleContent.imageUrls }
    // YouTube's streaming player, unless the saved offline copy plays below or Offline mode is on
    val offlineVideos: OfflineVideos by LocalDI.current.instance()
    val repository: Repository by LocalDI.current.instance()
    val offlineMode by repository.offlineMode.collectAsState()
    val offlineVideoChanges by offlineVideos.changes.collectAsState()
    val hasSavedVideo by produceState(false, viewState.articleId, offlineVideoChanges) {
        value = withContext(Dispatchers.IO) { offlineVideos.savedFiles(viewState.articleId) != null }
    }
    val youtubeId = viewState.youtubeId?.takeUnless { hasSavedVideo || offlineMode }

    ReaderView(
        screenType = screenType,
        wordCount = viewState.wordCount,
        onEnclosureClick = {
            if (viewState.enclosure.present) {
                if (viewState.useInAppAudioPlayer && shouldOpenInPodcastPlayer(viewState.enclosure.link, viewState.enclosure)) {
                    onOpenAudioPlayer(viewState.enclosure.link)
                } else {
                    activityLauncher.openLinkInBrowser(link = viewState.enclosure.link)
                }
            }
        },
        onFeedTitleClick = onFeedTitleClick,
        enclosure = viewState.enclosure,
        articleTitle = viewState.articleTitle,
        feedTitle = viewState.feedDisplayTitle,
        saved = viewState.isBookmarked,
        onSaveClick = onBookmark,
        isUnread = viewState.isUnread,
        onMarkAsUnread = onMarkAsUnread,
        onDelete = onDelete,
        authorDate =
            when {
                viewState.author == null && viewState.pubDate != null ->
                    stringResource(
                        R.string.on_date,
                        formatArticleDate(context, viewState.pubDate),
                    )

                viewState.author != null && viewState.pubDate != null ->
                    stringResource(
                        R.string.by_author_on_date,
                        // Must wrap author in Unicode marks to ensure it formats
                        // correctly in RTL
                        context.unicodeWrap(viewState.author ?: ""),
                        formatArticleDate(context, viewState.pubDate),
                    )

                else -> null
            },
        image = viewState.image,
        showHeaderImage = viewState.textToDisplay == TextToDisplay.CONTENT,
        contentImageUrls = contentImageUrls,
        modifier = if (onUserScroll != null) modifier.stopSpeechFollowingOnAccessibilityScroll(articleScrollState, onUserScroll) else modifier,
        articleScrollState = articleScrollState,
        video =
            if (youtubeId != null) {
                {
                    YouTubeVideoBlock(
                        videoId = youtubeId,
                        articleKey = viewState.articleKey,
                        title = viewState.articleTitle,
                    )
                }
            } else {
                null
            },
    ) { indexOffset ->
        var offsetCounter = indexOffset

        // YouTube articles: Save video offline, and the saved copy's player
        if (viewState.articleId > ID_UNSET && viewState.articleLink?.let { isYouTubeLink(it) } == true) {
            offsetCounter++
            OfflineVideoPanel(itemId = viewState.articleId, link = viewState.articleLink)
        }

        if (viewState.isTranslationLoading) {
            offsetCounter++
            viewState.translationModelDownloadProgress?.let { progress ->
                TranslationModelDownloadProgress(progress)
            } ?: LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (viewState.openAiSummary !is OpenAISummaryState.Empty) {
            offsetCounter++
            SummarySection(viewState.openAiSummary)
        }
        // Can take a composition or two before viewstate is set to its actual values
        if (viewState.articleId > ID_UNSET) {
            when (viewState.textToDisplay) {
                TextToDisplay.CONTENT -> {
                    ColumnArticleContent(
                        articleContent = viewState.articleContent,
                        speechHighlight = speechHighlight,
                        isReadingAloud = isReadingAloud,
                        onReadAloudFrom = onReadAloudFrom,
                        fastReading = fastReading,
                        onLinkClick = { link, index ->
                            if (index != null && elementPositions.containsKey(index)) {
                                onUserScroll?.invoke()
                                // Anchor link - scroll to the element position
                                val yPosition = elementPositions[index]
                                if (yPosition != null) {
                                    coroutineScope.launch {
                                        articleScrollState.animateScrollTo(yPosition.toInt())
                                    }
                                }
                            } else {
                                if (viewState.useInAppAudioPlayer && shouldOpenInPodcastPlayer(link, viewState.enclosure)) {
                                    onOpenAudioPlayer(link)
                                } else {
                                    // External link - open in browser/custom tab
                                    activityLauncher.openLink(
                                        link = link,
                                        toolbarColor = toolbarColor,
                                    )
                                }
                            }
                        },
                        onElementPosition = { index, yPosition ->
                            elementPositions[offsetCounter + index] = yPosition
                        },
                    )
                }

                TextToDisplay.LOADING_FULLTEXT -> {
                    Text(text = stringResource(id = R.string.fetching_full_article))
                }

                TextToDisplay.FAILED_TO_LOAD_FULLTEXT -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article))
                }

                TextToDisplay.FAILED_MISSING_BODY -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article_missing_body))
                }

                TextToDisplay.FAILED_MISSING_LINK -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article_missing_link))
                }

                TextToDisplay.FAILED_NOT_HTML -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article_not_html))
                }

                TextToDisplay.FAILED_FULLTEXT_TOO_LARGE -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article_too_large))
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onOpenInCustomTab) {
                        Text(text = stringResource(id = R.string.open_in_web_view))
                    }
                }
            }
        }
    }
}

internal fun formatArticleDate(
    context: Context,
    publicationDate: ZonedDateTime?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    publicationDate?.let {
        val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMMEEEEdHm" else "yMMMMEEEEdhm"
        val locale = context.resources.configuration.locales[0]
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .apply { timeZone = TimeZone.getTimeZone(zoneId) }
            .format(Date.from(it.toInstant()))
    } ?: ""

@Composable
private fun TranslationModelDownloadProgress(progress: BergamotModelDownloadProgress) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        TranslationProgressContent(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SummarySection(summary: OpenAISummaryState) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (summary) {
            OpenAISummaryState.Empty -> {}
            OpenAISummaryState.Loading ->
                LinearProgressIndicator(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                )
            is OpenAISummaryState.Result -> {
                Column(modifier = Modifier.padding(8.dp)) {
                    summary.annotatedStrings.forEachIndexed { index, annotatedString ->
                        Text(
                            text = annotatedString,
                            modifier =
                                Modifier.padding(
                                    bottom = if (index < summary.annotatedStrings.size - 1) 8.dp else 0.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Share, Unread, Summarize, Save and Translate as icon buttons, for the top bar while reading aloud. */
@Composable
private fun RowScope.ArticleTopBarActions(
    isBookmarked: Boolean,
    showSummarize: Boolean,
    showTranslate: Boolean,
    isShowingTranslated: Boolean,
    onShare: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onSummarize: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTranslate: () -> Unit,
    onDelete: () -> Unit,
) {
    TopBarAction(Icons.Default.Share, stringResource(R.string.share), onShare)
    TopBarAction(Icons.Outlined.MarkEmailUnread, stringResource(R.string.action_unread), onMarkAsUnread)
    if (showSummarize) {
        TopBarAction(Icons.Outlined.AutoAwesome, stringResource(R.string.summarize), onSummarize)
    }
    TopBarAction(
        if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        stringResource(if (isBookmarked) R.string.action_saved else R.string.action_save),
        onToggleBookmark,
    )
    TopBarAction(Icons.Outlined.Delete, stringResource(R.string.action_delete_article), onDelete)
    if (showTranslate) {
        TopBarAction(
            Icons.Default.Translate,
            stringResource(if (isShowingTranslated) R.string.action_original else R.string.action_translate),
            onTranslate,
        )
    }
}

@Composable
private fun TopBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

/**
 * The reading tools in one floating pill, styled like the read-aloud player. Save, Unread and Delete
 * live under the title and in the top bar instead.
 */
@Composable
private fun ReaderToolbar(
    isTTSPlaying: Boolean,
    onListen: () -> Unit,
    fastReading: Boolean,
    onToggleFastReading: () -> Unit,
    onSpeedRead: () -> Unit,
    showSummarize: Boolean,
    onSummarize: () -> Unit,
    onToggleFullText: () -> Unit,
    showOriginalPageToggle: Boolean,
    showOriginalPage: Boolean,
    onToggleOriginalPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                ReaderToolbarButton(
                    icon = if (isTTSPlaying) Icons.Default.Pause else Icons.Default.Headphones,
                    label = stringResource(if (isTTSPlaying) R.string.pause_reading else R.string.listen),
                    onClick = onListen,
                    primary = true,
                )
                ReaderToolbarButton(
                    icon = Icons.Default.FormatBold,
                    label = stringResource(R.string.fast_reading),
                    onClick = onToggleFastReading,
                    selected = fastReading,
                )
                ReaderToolbarButton(
                    icon = Icons.Default.Speed,
                    label = stringResource(R.string.speed_read_short),
                    onClick = onSpeedRead,
                )
                if (showSummarize) {
                    ReaderToolbarButton(
                        icon = Icons.Outlined.AutoAwesome,
                        label = stringResource(R.string.summary_short),
                        onClick = onSummarize,
                    )
                }
                ReaderToolbarButton(
                    icon = Icons.AutoMirrored.Filled.Article,
                    label = stringResource(R.string.full_text_short),
                    onClick = onToggleFullText,
                )
                if (showOriginalPageToggle) {
                    ReaderToolbarButton(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.original_page_short),
                        onClick = onToggleOriginalPage,
                        selected = showOriginalPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    // Set for on/off tools, which then show their state
    selected: Boolean? = null,
) {
    val highlighted = primary || selected == true
    val background =
        when {
            primary -> MaterialTheme.colorScheme.primaryContainer
            selected == true -> MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f)
            else -> Color.Transparent
        }
    val color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .width(if (primary) 60.dp else 52.dp)
                .height(50.dp)
                .clip(CircleShape)
                .background(background)
                .then(
                    if (selected == null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
                    },
                ),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
