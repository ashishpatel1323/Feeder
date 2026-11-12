package com.nononsenseapps.feeder.ui.compose.feedarticle

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.TextToDisplay
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.localtranslation.BergamotModelDownloadProgress
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.ScrollDirection
import com.nononsenseapps.feeder.ui.compose.components.TranslationProgressContent
import com.nononsenseapps.feeder.ui.compose.feed.PlainTooltipBox
import com.nononsenseapps.feeder.ui.compose.html.ColumnArticleContent
import com.nononsenseapps.feeder.ui.compose.icons.CustomFilled
import com.nononsenseapps.feeder.ui.compose.icons.TextToSpeech
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleSwipeDirection
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableTTSPlayer
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechFollowViewport
import com.nononsenseapps.feeder.ui.compose.readaloud.SpeechHighlight
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnAccessibilityScroll
import com.nononsenseapps.feeder.ui.compose.readaloud.stopSpeechFollowingOnUserScroll
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import com.nononsenseapps.feeder.ui.compose.utils.ScreenType
import com.nononsenseapps.feeder.ui.compose.utils.rememberApiPermissionState
import com.nononsenseapps.feeder.util.ActivityLauncher
import com.nononsenseapps.feeder.util.stripTrackingParameters
import com.nononsenseapps.feeder.util.unicodeWrap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
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
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class,
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
) {
    // While reading aloud the bottom bar is the player and the article actions move to the top bar
    val readingAloud = viewState.isBottomBarVisible && !viewState.podcastPlayerState.isVisible
    val enterAlwaysScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // The top bar must not scroll away while it holds the article actions
    val scrollBehavior = if (readingAloud) pinnedScrollBehavior else enterAlwaysScrollBehavior

    // Some phones (OxygenOS) hide the media notification, and with it the system media player and
    // lock-screen controls, until the app may post notifications. Ask once when reading aloud starts.
    val notificationPermission =
        rememberApiPermissionState(
            permission = "android.permission.POST_NOTIFICATIONS",
            minimumApiLevel = 33,
        )
    var askedForNotificationPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(readingAloud) {
        if (readingAloud && !askedForNotificationPermission && notificationPermission.status !is PermissionStatus.Granted) {
            askedForNotificationPermission = true
            notificationPermission.launchPermissionRequest()
        }
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
            SensibleTopAppBar(
                modifier =
                    Modifier
                        .focusGroup()
                        .focusRequester(focusTopBar)
                        .focusProperties {
                            down = focusArticle
                        },
                scrollBehavior = scrollBehavior,
                title = viewState.feedDisplayTitle,
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                },
                actions = {
                    // Up to eight actions share the top bar while reading aloud, so they use 40 dp touch targets
                    CompositionLocalProvider(
                        LocalMinimumInteractiveComponentSize provides if (readingAloud) 40.dp else 48.dp,
                    ) {
                        PlainTooltipBox(tooltip = { Text(stringResource(R.string.fetch_full_article)) }) {
                            IconButton(
                                onClick = onToggleFullText,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Article,
                                    contentDescription = stringResource(R.string.fetch_full_article),
                                )
                            }
                        }

                        // The player has its own play/pause while reading aloud
                        if (!readingAloud) {
                            val readAloudLabel =
                                stringResource(
                                    if (viewState.isTTSPlaying) R.string.pause_reading else R.string.read_article,
                                )
                            PlainTooltipBox(tooltip = { Text(readAloudLabel) }) {
                                IconButton(
                                    onClick = {
                                        if (viewState.isTTSPlaying) ttsOnPause() else ttsOnPlay()
                                    },
                                ) {
                                    Icon(
                                        if (viewState.isTTSPlaying) Icons.Default.Pause else Icons.CustomFilled.TextToSpeech,
                                        contentDescription = readAloudLabel,
                                    )
                                }
                            }
                        }

                        if (!viewState.articleLink.isNullOrBlank()) {
                            val originalPageLabel =
                                stringResource(if (showOriginalPage) R.string.show_reader_view else R.string.show_original_page)
                            PlainTooltipBox(tooltip = { Text(originalPageLabel) }) {
                                IconButton(onClick = onToggleOriginalPage) {
                                    Icon(
                                        Icons.Default.Language,
                                        contentDescription = originalPageLabel,
                                        tint = if (showOriginalPage) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    )
                                }
                            }
                        }

                        PlainTooltipBox(tooltip = { Text(stringResource(id = R.string.open_in_web_view)) }) {
                            IconButton(
                                onClick = onOpenInCustomTab,
                            ) {
                                Icon(
                                    Icons.Default.OpenInBrowser,
                                    contentDescription = stringResource(id = R.string.open_in_web_view),
                                )
                            }
                        }

                        if (readingAloud) {
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
                            )
                        }
                    }
                },
            )
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
                        } else {
                            HideableTTSPlayer(
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
                            )
                        }
                    }
                    ArticleActionBar(
                        isBookmarked = viewState.isBookmarked,
                        showSummarize = viewState.showSummarize,
                        showTranslate = viewState.showTranslate,
                        isShowingTranslated = viewState.isShowingTranslated,
                        onShare = onShare,
                        onMarkAsUnread = onMarkAsUnread,
                        onSummarize = onSummarize,
                        onToggleBookmark = onToggleBookmark,
                        onTranslate = onTranslate,
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
                        articleScrollState = articleScrollState,
                        onUserScroll = ttsOnUserScroll,
                        onFeedTitleClick = onFeedTitleClick,
                        onOpenAudioPlayer = onOpenAudioPlayer,
                        onOpenInCustomTab = onOpenInCustomTab,
                        onBookmark = onToggleBookmark,
                        onMarkAsUnread = onMarkAsUnread,
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
) {
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    val context = LocalContext.current
    val activityLauncher: ActivityLauncher by LocalDI.current.instance()
    val coroutineScope = rememberCoroutineScope()

    // Track Y positions of article elements by index for anchor link scrolling
    val elementPositions = remember { mutableMapOf<Int, Float>() }
    val contentImageUrls = remember(viewState.articleContent) { viewState.articleContent.imageUrls }

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
    ) { indexOffset ->
        var offsetCounter = indexOffset

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
) {
    TopBarAction(Icons.Default.Share, stringResource(R.string.share), onShare)
    TopBarAction(Icons.Outlined.VisibilityOff, stringResource(R.string.action_unread), onMarkAsUnread)
    if (showSummarize) {
        TopBarAction(Icons.Outlined.AutoAwesome, stringResource(R.string.summarize), onSummarize)
    }
    TopBarAction(
        if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        stringResource(if (isBookmarked) R.string.action_saved else R.string.action_save),
        onToggleBookmark,
    )
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
) {
    PlainTooltipBox(tooltip = { Text(label) }) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun ArticleActionBar(
    isBookmarked: Boolean,
    showSummarize: Boolean,
    showTranslate: Boolean,
    isShowingTranslated: Boolean,
    onShare: () -> Unit,
    onMarkAsUnread: () -> Unit,
    onSummarize: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
            ) {
                ArticleAction(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                )
                ArticleAction(
                    icon = Icons.Outlined.VisibilityOff,
                    label = stringResource(R.string.action_unread),
                    onClick = onMarkAsUnread,
                    modifier = Modifier.weight(1f),
                )
                if (showSummarize) {
                    ArticleAction(
                        icon = Icons.Outlined.AutoAwesome,
                        label = stringResource(R.string.summarize),
                        onClick = onSummarize,
                        modifier = Modifier.weight(1f),
                    )
                }
                ArticleAction(
                    icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    label = stringResource(if (isBookmarked) R.string.action_saved else R.string.action_save),
                    onClick = onToggleBookmark,
                    modifier = Modifier.weight(1f),
                )
                if (showTranslate) {
                    ArticleAction(
                        icon = Icons.Default.Translate,
                        label =
                            stringResource(
                                if (isShowingTranslated) R.string.action_original else R.string.action_translate,
                            ),
                        onClick = onTranslate,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
