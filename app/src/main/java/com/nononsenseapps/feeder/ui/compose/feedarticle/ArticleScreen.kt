package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.itemKind
import com.nononsenseapps.feeder.model.playlists.itemSeconds
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.ScrollDirection
import com.nononsenseapps.feeder.ui.compose.components.TranslationProgressContent
import com.nononsenseapps.feeder.ui.compose.highlights.ArticleHighlightsHost
import com.nononsenseapps.feeder.ui.compose.highlights.ArticleHighlightsState
import com.nononsenseapps.feeder.ui.compose.html.ColumnArticleContent
import com.nononsenseapps.feeder.ui.compose.item.ItemHeader
import com.nononsenseapps.feeder.ui.compose.item.PodcastMediaBlock
import com.nononsenseapps.feeder.ui.compose.item.rememberItemProgress
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleSwipeDirection
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechFollowViewport
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechHighlight
import com.nononsenseapps.feeder.ui.compose.readaloud.WordModeState
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnAccessibilityScroll
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnUserScroll
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
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
    val showingFullText by viewModel.showingFullText.collectAsStateWithLifecycle()

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

    ArticleHighlightsHost(
        articleId = viewState.articleId,
        title = viewState.articleTitle,
        link = viewState.articleLink,
        feedTitle = viewState.feedDisplayTitle,
        imageUrl = viewState.image?.url,
    ) { highlights ->
        ArticleScreen(
            highlights = highlights,
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
            onToggleRead = viewModel::toggleRead,
            showingFullText = showingFullText,
            onRetryFullText = viewModel::retryFullText,
        )
    }
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
    onToggleRead: () -> Unit = onMarkAsUnread,
    showingFullText: Boolean = false,
    onRetryFullText: () -> Unit = {},
    highlights: ArticleHighlightsState? = null,
) {
    val itemKindHere = itemKind(viewState.articleLink, viewState.enclosure.link.ifBlank { null }, viewState.enclosure.type)

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
    // Material 3: Save and Share sit in the top bar, everything else in its ⋮ menu, and Listen is
    // the one floating action. The bar is pinned, so these stay reachable while reading.
    val addToPlaylist =
        com.nononsenseapps.feeder.ui.compose.playlists
            .rememberAddToPlaylist()
    var showTranscript by rememberSaveable(viewState.articleId) { mutableStateOf(false) }
    val isArticle = itemKindHere == ItemKind.ARTICLE
    val hasLink = !viewState.articleLink.isNullOrBlank()
    val menuActions =
        ArticleMenuActions(
            kind = itemKindHere,
            read = !viewState.isUnread,
            onToggleRead = onToggleRead,
            onPlaylist = { addToPlaylist(viewState.articleId, viewState.articleTitle) },
            transcriptShown = showTranscript,
            onToggleTranscript = if (itemKindHere == ItemKind.PODCAST) ({ showTranscript = !showTranscript }) else null,
            fullText = showingFullText,
            onToggleFullText = if (isArticle) onToggleFullText else null,
            webPage = showOriginalPage,
            onToggleWebPage = if (isArticle && hasLink) onToggleOriginalPage else null,
            onOpenInBrowser = if (hasLink) onOpenInCustomTab else null,
            onSummarize = if (viewState.showSummarize) onSummarize else null,
            translated = viewState.isShowingTranslated,
            onTranslate = if (viewState.showTranslate) onTranslate else null,
            fastReading = fastReading,
            onToggleFastReading = if (isArticle) onToggleFastReading else null,
            onSpeedRead = if (isArticle) onSpeedRead else null,
            highlightCount = highlights?.count ?: 0,
            onHighlights = highlights?.onShowList,
            onDelete = onDelete,
        )
    // Listen is a full label at the top and while scrolling back up, an icon while reading down
    val listenExpanded by remember {
        derivedStateOf { articleScrollState.value == 0 || !articleScrollState.lastScrolledForward }
    }

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
                        ArticleTopBarActions(
                            saved = viewState.isBookmarked,
                            onToggleSave = onToggleBookmark,
                            onShare = onShare,
                            menu = menuActions,
                        )
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
            } else if (viewState.podcastPlayerState.isVisible) {
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
        },
        floatingActionButton = {
            // Reading aloud has its own player; podcasts and videos have their own Play
            if (isArticle && !readingAloud && !viewState.podcastPlayerState.isVisible) {
                ListenFab(
                    expanded = listenExpanded,
                    onClick = ttsOnPlay,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                )
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
                        onShare = onShare,
                        onSummarize = onSummarize,
                        onTranslate = onTranslate,
                        onToggleRead = onToggleRead,
                        showTranscript = showTranscript,
                        onToggleFullText = onToggleFullText,
                        onShowWebPage = if (hasLink) onToggleOriginalPage else null,
                        onRetryFullText = onRetryFullText,
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
    onShare: (() -> Unit)? = null,
    onSummarize: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onToggleRead: () -> Unit = onMarkAsUnread,
    // Hoisted to the screen: the ⋮ menu's Transcript entry toggles it
    showTranscript: Boolean = false,
    onToggleFullText: () -> Unit = {},
    onShowWebPage: (() -> Unit)? = null,
    onRetryFullText: () -> Unit = {},
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
    val addToPlaylist =
        com.nononsenseapps.feeder.ui.compose.playlists
            .rememberAddToPlaylist()
    val kind = itemKind(viewState.articleLink, viewState.enclosure.link.ifBlank { null }, viewState.enclosure.type)
    val progress = rememberItemProgress(viewState.articleId, if (kind == ItemKind.YOUTUBE) viewState.articleKey else "")
    val lengthSeconds =
        itemSeconds(
            kind = kind,
            wordCount = viewState.wordCount,
            enclosureDuration = viewState.enclosureDuration,
            positionLength = progress?.lengthSeconds,
        )
    val dimens = LocalDimens.current

    ReaderView(
        header = {
            // The same header and labeled actions as Flip: type, feed, date, real length, title
            Column(
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.md),
                modifier = Modifier.width(dimens.maxReaderWidth),
            ) {
                ItemHeader(
                    kind = kind,
                    title = viewState.articleTitle,
                    feedTitle = viewState.feedDisplayTitle,
                    date =
                        listOfNotNull(
                            viewState.author?.ifBlank { null }?.let { context.unicodeWrap(it) },
                            viewState.pubDate?.let { formatArticleDate(context, it) },
                        ).joinToString(" · "),
                    lengthSeconds = lengthSeconds,
                    progress = progress,
                    onFeedClick = onFeedTitleClick,
                )
                // The episode's media block, right under the header like in Flip
                if (kind == ItemKind.PODCAST && viewState.useInAppAudioPlayer && viewState.enclosure.link.isNotBlank()) {
                    PodcastMediaBlock(
                        itemId = viewState.articleId,
                        link = viewState.enclosure.link,
                        lengthSeconds = lengthSeconds,
                        image = viewState.image?.url,
                        progress = progress,
                        showTranscript = showTranscript,
                    )
                }
            }
        },
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
        enclosureActions =
            if (viewState.enclosure.type.startsWith("audio/")) {
                { OfflineAudioPanel(itemId = viewState.articleId, enclosure = viewState.enclosure) }
            } else {
                null
            },
        articleTitle = viewState.articleTitle,
        feedTitle = viewState.feedDisplayTitle,
        saved = viewState.isBookmarked,
        onSaveClick = onBookmark,
        isUnread = viewState.isUnread,
        onMarkAsUnread = onMarkAsUnread,
        onDelete = onDelete,
        onAddToPlaylist = { addToPlaylist(viewState.articleId, viewState.articleTitle) },
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
                        itemId = viewState.articleId,
                        link = viewState.articleLink,
                        isBookmarked = viewState.isBookmarked,
                        onToggleBookmark = onBookmark,
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
            // The player's Download button saves it; this shows progress, errors and the saved copy
            OfflineVideoPanel(itemId = viewState.articleId, link = viewState.articleLink, showSaveButton = youtubeId == null)
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
                    FullTextFailure(
                        message = stringResource(id = R.string.failed_to_fetch_full_article),
                        onShowWebPage = onShowWebPage,
                        onShowFeedText = onToggleFullText,
                        onRetry = onRetryFullText,
                    )
                }

                TextToDisplay.FAILED_BLOCKED -> {
                    FullTextFailure(
                        message = stringResource(id = R.string.full_text_blocked),
                        onShowWebPage = onShowWebPage,
                        onShowFeedText = onToggleFullText,
                        onRetry = null,
                    )
                }

                TextToDisplay.FAILED_MISSING_BODY -> {
                    FullTextFailure(
                        message = stringResource(id = R.string.failed_to_fetch_full_article_missing_body),
                        onShowWebPage = onShowWebPage,
                        onShowFeedText = onToggleFullText,
                        onRetry = null,
                    )
                }

                TextToDisplay.FAILED_MISSING_LINK -> {
                    Text(text = stringResource(id = R.string.failed_to_fetch_full_article_missing_link))
                }

                TextToDisplay.FAILED_NOT_HTML -> {
                    FullTextFailure(
                        message = stringResource(id = R.string.failed_to_fetch_full_article_not_html),
                        onShowWebPage = onShowWebPage,
                        onShowFeedText = onToggleFullText,
                        onRetry = null,
                    )
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
