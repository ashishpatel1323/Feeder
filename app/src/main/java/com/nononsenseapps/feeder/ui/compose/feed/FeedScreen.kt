package com.nononsenseapps.feeder.ui.compose.feed

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.FeedListGrouping
import com.nononsenseapps.feeder.archmodel.FeedType
import com.nononsenseapps.feeder.archmodel.SortingOptions
import com.nononsenseapps.feeder.db.room.FeedItemCursor
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.localtranslation.BergamotModelDownloadProgress
import com.nononsenseapps.feeder.model.LocaleOverride
import com.nononsenseapps.feeder.model.ReadAloudNeighbors
import com.nononsenseapps.feeder.model.ReadAloudProgress
import com.nononsenseapps.feeder.model.export.exportSavedArticles
import com.nononsenseapps.feeder.model.export.importSavedArticles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.opml.exportOpml
import com.nononsenseapps.feeder.model.opml.importOpml
import com.nononsenseapps.feeder.ui.compose.components.TranslationProgressContent
import com.nononsenseapps.feeder.ui.compose.components.safeSemantics
import com.nononsenseapps.feeder.ui.compose.deletefeed.DeletableFeed
import com.nononsenseapps.feeder.ui.compose.deletefeed.DeleteFeedDialog
import com.nononsenseapps.feeder.ui.compose.dialog.RenameTagDialog
import com.nononsenseapps.feeder.ui.compose.empty.NothingToRead
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilterCallback
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedScreenViewState
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedViewModel
import com.nononsenseapps.feeder.ui.compose.feedarticle.HideablePodcastPlayer
import com.nononsenseapps.feeder.ui.compose.feedarticle.TranslatedFeedCards
import com.nononsenseapps.feeder.ui.compose.feedarticle.onlyUnread
import com.nononsenseapps.feeder.ui.compose.feedarticle.onlyUnreadAndSaved
import com.nononsenseapps.feeder.ui.compose.item.ItemActionButton
import com.nononsenseapps.feeder.ui.compose.material3.DrawerState
import com.nononsenseapps.feeder.ui.compose.material3.DrawerValue
import com.nononsenseapps.feeder.ui.compose.material3.rememberDrawerState
import com.nononsenseapps.feeder.ui.compose.modifiers.trackVisibility
import com.nononsenseapps.feeder.ui.compose.navdrawer.ScreenWithNavDrawer
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleDestination
import com.nononsenseapps.feeder.ui.compose.navigation.DownloadsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.EditFeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.FeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.FeedsViewSwitch
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchDestination
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchTopBar
import com.nononsenseapps.feeder.ui.compose.navigation.HideMainBar
import com.nononsenseapps.feeder.ui.compose.navigation.HighlightsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.HomeDestination
import com.nononsenseapps.feeder.ui.compose.navigation.ManageFeedsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.PlaylistsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.SearchFeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.SettingsDestination
import com.nononsenseapps.feeder.ui.compose.playlists.LocalAddToPlaylist
import com.nononsenseapps.feeder.ui.compose.playlists.rememberAddToPlaylist
import com.nononsenseapps.feeder.ui.compose.pullrefresh.PullRefreshIndicator
import com.nononsenseapps.feeder.ui.compose.pullrefresh.pullRefresh
import com.nononsenseapps.feeder.ui.compose.pullrefresh.rememberPullRefreshState
import com.nononsenseapps.feeder.ui.compose.readaloud.HideableCompactTTSPlayer
import com.nononsenseapps.feeder.ui.compose.theme.FeederTokens
import com.nononsenseapps.feeder.ui.compose.theme.LocalDimens
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar
import com.nononsenseapps.feeder.ui.compose.utils.ImmutableHolder
import com.nononsenseapps.feeder.ui.compose.utils.addMargin
import com.nononsenseapps.feeder.ui.compose.utils.isCompactDevice
import com.nononsenseapps.feeder.ui.compose.utils.onKeyEventLikeEscape
import com.nononsenseapps.feeder.util.ActivityLauncher
import com.nononsenseapps.feeder.util.ToastMaker
import com.nononsenseapps.feeder.util.logDebug
import com.nononsenseapps.feeder.util.stripTrackingParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.compose.LocalDI
import org.kodein.di.compose.instance
import org.kodein.di.instance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "FEEDER_FEEDSCREEN"

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun FeedScreen(
    navController: NavController,
    viewModel: FeedViewModel,
    navDrawerListState: LazyListState,
) {
    val toastMaker: ToastMaker by instance()
    val viewState: FeedScreenViewState by viewModel.viewState.collectAsStateWithLifecycle()
    val readAloudProgress = viewModel.readAloudProgress
    val readAloudNeighbors by viewModel.readAloudNeighbors.collectAsStateWithLifecycle()
    val readAloudAutoContinue by viewModel.readAloudAutoContinue.collectAsStateWithLifecycle()
    val translatedFeedCards by viewModel.translatedFeedCards.collectAsStateWithLifecycle()
    val pagedFeedItems = viewModel.currentFeedListItems.collectAsLazyPagingItems()
    val pagedNavDrawerItems = viewModel.pagedNavDrawerItems.collectAsLazyPagingItems()

    val di = LocalDI.current
    val savedArticleExporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) {
                val applicationCoroutineScope: ApplicationCoroutineScope by di.instance()
                applicationCoroutineScope.launch {
                    exportSavedArticles(di, uri)
                }
            }
        }
    val opmlExporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/x-opml"),
        ) { uri ->
            if (uri != null) {
                val applicationCoroutineScope: ApplicationCoroutineScope by di.instance()
                applicationCoroutineScope.launch {
                    exportOpml(di, uri)
                }
            }
        }
    val opmlImporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val applicationCoroutineScope: ApplicationCoroutineScope by di.instance()
                applicationCoroutineScope.launch {
                    importOpml(di, uri)
                }
            }
        }
    val savedArticleImporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val applicationCoroutineScope: ApplicationCoroutineScope by di.instance()
                applicationCoroutineScope.launch {
                    importSavedArticles(di, uri)
                }
            }
        }

    val activityLauncher: ActivityLauncher by LocalDI.current.instance()

    // Each feed gets its own scroll state. Persists across device rotations, but is cleared when
    // switching feeds
    val feedListState =
        key(viewState.currentFeedOrTag) {
            pagedFeedItems.rememberLazyListState()
        }

    val feedGridState =
        key(viewState.currentFeedOrTag) {
            rememberLazyStaggeredGridState()
        }

    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoText = stringResource(R.string.undo_delete_article)
    val resources = LocalContext.current.resources

    // Multi-select: started from "Select" in an article's long-press menu
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(viewState.currentFeedOrTag, viewState.filter) {
        selectedIds = emptySet()
    }
    BackHandler(enabled = selectedIds.isNotEmpty()) {
        selectedIds = emptySet()
    }

    fun deleteWithUndo(itemIds: List<Long>) {
        coroutineScope.launch {
            val deleted = viewModel.deleteForGood(itemIds)
            snackbarHostState.currentSnackbarData?.dismiss()
            val result =
                snackbarHostState.showSnackbar(
                    message = resources.getQuantityString(R.plurals.n_articles_deleted_everywhere, itemIds.size, itemIds.size),
                    actionLabel = undoText,
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete(deleted)
            }
        }
    }

    val focusNavDrawer = remember { FocusRequester() }

    BackHandler(
        enabled = drawerState.isOpen,
        onBack = {
            if (drawerState.isOpen) {
                coroutineScope.launch {
                    drawerState.close()
                }
            }
        },
    )

    // See https://issuetracker.google.com/issues/177245496#comment24
    // After recreation, items first return 0 items, then the cached items.
    // This behavior/issue is resetting the LazyListState scroll position.
    // Below is a workaround. More info: https://issuetracker.google.com/issues/177245496.
    val workaroundNavDrawerListState = rememberLazyListState()
    val hasTags by viewModel.hasTags.collectAsStateWithLifecycle()

    val navDrawerListStateToUse by remember {
        derivedStateOf {
            when (pagedNavDrawerItems.itemCount) {
                0 -> workaroundNavDrawerListState
                else -> navDrawerListState
            }
        }
    }

    val offlineVideos: OfflineVideos by LocalDI.current.instance()
    val downloadChanges by offlineVideos.changes.collectAsStateWithLifecycle()
    val downloadJobs by offlineVideos.jobs.collectAsStateWithLifecycle()
    val downloadsCount by produceState(0, downloadChanges) {
        value = withContext(Dispatchers.IO) { offlineVideos.savedIds().size }
    }

    val addToPlaylist = rememberAddToPlaylist()
    CompositionLocalProvider(LocalAddToPlaylist provides addToPlaylist) {
        ScreenWithNavDrawer(
            feedsAndTags = pagedNavDrawerItems,
            onToggleTagExpansion = { tag ->
                viewModel.toggleTagExpansion(tag)
            },
            showFolderControls = hasTags,
            onExpandAllTags = viewModel::expandAllTags,
            onCollapseAllTags = viewModel::collapseAllTags,
            onDrawerItemSelect = { feedId, tag ->
                FeedDestination.navigate(navController, feedId = feedId, tag = tag)
            },
            focusRequester = focusNavDrawer,
            drawerState = drawerState,
            navDrawerListState = navDrawerListStateToUse,
            downloadsCount = downloadsCount + downloadJobs.size,
            onDownloadsClick = { DownloadsDestination.navigate(navController) },
            onPlaylistsClick = { PlaylistsDestination.navigate(navController) },
            onHighlightsClick = { HighlightsDestination.navigate(navController) },
            onHomeClick = { HomeDestination.navigate(navController) },
        ) {
            FeedScreen(
                viewState = viewState,
                onRefreshVisible = {
                    viewModel.requestImmediateSyncOfCurrentFeedOrTag()
                },
                onRefreshAll = {
                    viewModel.requestImmediateSyncOfAll()
                    coroutineScope.launch {
                        if (feedListState.firstVisibleItemIndex != 0) {
                            feedListState.animateScrollToItem(0)
                        }
                        if (feedGridState.firstVisibleItemIndex != 0) {
                            feedGridState.animateScrollToItem(0)
                        }
                    }
                },
                onMarkAllAsRead = {
                    viewModel.markAllAsRead()
                },
                onShowToolbarMenu = { visible ->
                    viewModel.setToolbarMenuVisible(visible)
                },
                ttsOnPlay = viewModel::ttsPlay,
                ttsOnPause = viewModel::ttsPause,
                ttsOnStop = viewModel::ttsStop,
                ttsOnSkipNext = viewModel::ttsSkipNext,
                ttsOnSelectLanguage = viewModel::ttsOnSelectLanguage,
                readAloudProgress = readAloudProgress,
                ttsOnSeekTo = viewModel::ttsSeekTo,
                ttsOnSetSpeed = viewModel::ttsSetSpeed,
                ttsOnGoToSpokenText = {
                    viewModel.ttsFocusSpokenText()?.let { ArticleDestination.navigate(navController, it) }
                },
                readAloudNeighbors = readAloudNeighbors,
                readAloudAutoContinue = readAloudAutoContinue,
                ttsOnSkipArticle = viewModel::ttsSkipArticle,
                ttsOnSetAutoContinue = viewModel::ttsSetAutoContinue,
                ttsDiagnostics = viewModel::ttsDiagnostics,
                podcastOnPlay = viewModel::podcastPlay,
                podcastOnPause = viewModel::podcastPause,
                podcastOnStop = viewModel::podcastStop,
                podcastOnSeekBack = { viewModel.podcastSeekBy(-10_000) },
                podcastOnSeekForward = { viewModel.podcastSeekBy(10_000) },
                podcastOnSeekTo = viewModel::podcastSeekTo,
                onAddFeed = { SearchFeedDestination.navigate(navController) },
                onEditFeed = { feedId ->
                    EditFeedDestination.navigate(navController, feedId)
                },
                onShowEditDialog = {
                    viewModel.setShowEditDialog(true)
                },
                onDismissEditDialog = {
                    viewModel.setShowEditDialog(false)
                },
                onDeleteFeeds = { feedIds ->
                    viewModel.deleteFeeds(feedIds.toList())
                },
                onRenameTag = { oldTag, newTag ->
                    viewModel.renameTag(oldTag, newTag)
                },
                onShowDeleteDialog = {
                    viewModel.setShowDeleteDialog(true)
                },
                onDismissDeleteDialog = {
                    viewModel.setShowDeleteDialog(false)
                },
                onShowRenameTagDialog = {
                    viewModel.setShowRenameTagDialog(true)
                },
                onDismissRenameTagDialog = {
                    viewModel.setShowRenameTagDialog(false)
                },
                onSettings = {
                    SettingsDestination.navigate(navController)
                },
                onManageFeeds = {
                    ManageFeedsDestination.navigate(navController)
                },
                onImport = {
                    try {
                        opmlImporter.launch(
                            arrayOf(
                                // All valid for OPML files
                                "text/xml",
                                "text/x-opml",
                                "application/xml",
                                // This is the mimetype the file actually gets when exported
                                "application/json",
                                "application/octet-stream",
                                // But just in case a file isn't named right etc, accept all
                                "*/*",
                            ),
                        )
                    } catch (_: Exception) {
                        // ActivityNotFoundException in particular
                        coroutineScope.launch {
                            toastMaker.makeToast(R.string.failed_to_import_OPML)
                        }
                    }
                },
                onExportOPML = {
                    try {
                        opmlExporter.launch("feeder-export-${LocalDate.now()}-${LocalTime.now().toSecondOfDay()}.opml")
                    } catch (_: Exception) {
                        // ActivityNotFoundException in particular
                        coroutineScope.launch {
                            toastMaker.makeToast(R.string.failed_to_export_OPML)
                        }
                    }
                },
                onImportSavedArticles = {
                    try {
                        savedArticleImporter.launch(
                            arrayOf(
                                "text/plain",
                                // This is the mimetype the file may get from older exports
                                "application/octet-stream",
                                // But just in case a file isn't named right etc, accept all
                                "*/*",
                            ),
                        )
                    } catch (_: Exception) {
                        // ActivityNotFoundException in particular
                        coroutineScope.launch {
                            toastMaker.makeToast(R.string.failed_to_import_saved_articles)
                        }
                    }
                },
                onExportSavedArticles = {
                    try {
                        savedArticleExporter.launch(
                            "feeder-saved-articles-${LocalDate.now()}-${
                                LocalTime.now().toSecondOfDay()
                            }.json",
                        )
                    } catch (_: Exception) {
                        // ActivityNotFoundException in particular
                        coroutineScope.launch {
                            toastMaker.makeToast(R.string.failed_to_export_saved_articles)
                        }
                    }
                },
                drawerState = drawerState,
                markAsUnread = { itemId, unread ->
                    if (unread) {
                        viewModel.markAsUnread(itemId)
                    } else {
                        viewModel.markAsRead(itemId)
                    }
                },
                markAsReadOnSwipe = { itemId, unread, saved ->
                    when {
                        viewState.filter.onlyUnread -> {
                            // Get rid of it
                            viewModel.markAsReadOnSwipe(itemId)
                        }

                        viewState.filter.onlyUnreadAndSaved && !saved -> {
                            // Get rid of it
                            viewModel.markAsReadOnSwipe(itemId)
                        }

                        unread -> {
                            viewModel.markAsUnread(itemId)
                        }

                        else -> {
                            viewModel.markAsRead(itemId)
                        }
                    }
                },
                markBeforeAsRead = { cursor ->
                    viewModel.markBeforeAsRead(cursor)
                },
                markAfterAsRead = { cursor ->
                    viewModel.markAfterAsRead(cursor)
                },
                onOpenFeedItem = { itemId ->
                    viewModel.openArticle(
                        itemId = itemId,
                        openInBrowser = { articleLink ->
                            activityLauncher.openLinkInBrowser(articleLink)
                        },
                        openInCustomTab = { articleLink ->
                            activityLauncher.openLinkInCustomTab(articleLink, toolbarColor)
                        },
                        navigateToArticle = {
                            ArticleDestination.navigate(navController, itemId)
                        },
                    )
                },
                onOpenFeedItemInReader = { itemId ->
                    viewModel.openArticleInReader(
                        itemId = itemId,
                        navigateToArticle = {
                            ArticleDestination.navigate(navController, itemId)
                        },
                    )
                },
                onOpenFeedItemInCustomTab = { itemId ->
                    viewModel.openArticleInCustomTab(
                        itemId = itemId,
                        openInCustomTab = { articleLink ->
                            activityLauncher.openLinkInCustomTab(articleLink, toolbarColor)
                        },
                        onNoArticleLink = {
                            coroutineScope.launch {
                                toastMaker.makeToast(R.string.no_article_link_custom_tab)
                            }
                        },
                    )
                },
                onOpenFeedItemInBrowser = { itemId ->
                    viewModel.openArticleInBrowser(
                        itemId = itemId,
                        openInBrowser = { articleLink ->
                            activityLauncher.openLinkInBrowser(articleLink)
                        },
                        onNoArticleLink = {
                            coroutineScope.launch {
                                toastMaker.makeToast(R.string.no_article_link_browser)
                            }
                        },
                    )
                },
                onSetBookmark = { itemId, value ->
                    viewModel.setBookmarked(itemId, value)
                },
                filterCallback = viewModel.filterCallback,
                onSetFeedListGrouping = viewModel::setFeedListGrouping,
                onSetSorting = viewModel::setCurrentSorting,
                onMarkGroupAsRead = { groupKey -> viewModel.markGroupAsRead(groupKey) },
                // The search button opens global search (every feed, transcripts, highlights, playlists)
                onShowSearchBar = { show ->
                    if (show) GlobalSearchDestination.navigate(navController) else viewModel.setSearchBarVisible(false)
                },
                searchCallback = viewModel::searchCallback,
                feedListState = feedListState,
                feedGridState = feedGridState,
                pagedFeedItems = pagedFeedItems,
                translatedFeedCards = translatedFeedCards,
                onTranslateFeedCard = viewModel::translateFeedCardIfNeeded,
                snackbarHostState = snackbarHostState,
                onDeleteItem = { itemId -> deleteWithUndo(listOf(itemId)) },
                selection =
                    FeedSelection(
                        ids = selectedIds,
                        onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                        onClear = { selectedIds = emptySet() },
                        onMarkRead = { read ->
                            viewModel.setReadState(selectedIds, read)
                            selectedIds = emptySet()
                        },
                        onDelete = {
                            deleteWithUndo(selectedIds.toList())
                            selectedIds = emptySet()
                        },
                        // What the old long-press menu offered for one article, as labeled buttons
                        onMarkAboveAsRead = { id ->
                            pagedFeedItems.itemSnapshotList.firstOrNull { it?.id == id }?.let { viewModel.markBeforeAsRead(it.cursor) }
                            selectedIds = emptySet()
                        },
                        onMarkBelowAsRead = { id ->
                            pagedFeedItems.itemSnapshotList.firstOrNull { it?.id == id }?.let { viewModel.markAfterAsRead(it.cursor) }
                            selectedIds = emptySet()
                        },
                        onShare = { id ->
                            pagedFeedItems.itemSnapshotList.firstOrNull { it?.id == id }?.let { item ->
                                val intent =
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            item.link?.let { putExtra(Intent.EXTRA_TEXT, stripTrackingParameters(it)) }
                                            putExtra(Intent.EXTRA_TITLE, item.title)
                                            type = "text/plain"
                                        },
                                        null,
                                    )
                                activityLauncher.startActivity(openAdjacentIfSuitable = false, intent = intent)
                            }
                        },
                        onOpenInBrowser = { id ->
                            viewModel.openArticleInBrowser(
                                itemId = id,
                                openInBrowser = { link -> activityLauncher.openLinkInBrowser(link) },
                                onNoArticleLink = {
                                    coroutineScope.launch { toastMaker.makeToast(R.string.no_article_link_browser) }
                                },
                            )
                        },
                    ),
            )
        }
    }
}

/** Articles picked with multi-select, and what can be done to all of them at once. */
@Immutable
class FeedSelection(
    val ids: Set<Long>,
    val onToggle: (Long) -> Unit,
    val onClear: () -> Unit,
    val onMarkRead: (Boolean) -> Unit,
    val onDelete: () -> Unit,
    val onMarkAboveAsRead: (Long) -> Unit = {},
    val onMarkBelowAsRead: (Long) -> Unit = {},
    val onShare: (Long) -> Unit = {},
    val onOpenInBrowser: (Long) -> Unit = {},
) {
    val active: Boolean get() = ids.isNotEmpty()
}

/**
 * Shown under the list while articles are selected. Every action is a labeled button; with one
 * article selected it also offers what the old long-press menu did (read above/below, share,
 * open in the browser).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedSelectionBar(
    selection: FeedSelection,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            modifier =
                Modifier.padding(horizontal = FeederTokens.Space.lg, vertical = FeederTokens.Space.sm),
        ) {
            Text(
                text = stringResource(R.string.n_selected, selection.ids.size),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FeederTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(FeederTokens.Space.xs),
            ) {
                ItemActionButton(Icons.Outlined.TaskAlt, stringResource(R.string.item_action_mark_read), { selection.onMarkRead(true) })
                ItemActionButton(Icons.Outlined.MarkEmailUnread, stringResource(R.string.item_action_mark_unread), { selection.onMarkRead(false) })
                ItemActionButton(Icons.Outlined.Delete, stringResource(R.string.action_delete_article), selection.onDelete, danger = true)
                val single = selection.ids.singleOrNull()
                if (single != null) {
                    ItemActionButton(Icons.Default.VerticalAlignTop, stringResource(R.string.item_action_read_above), { selection.onMarkAboveAsRead(single) })
                    ItemActionButton(Icons.Default.VerticalAlignBottom, stringResource(R.string.item_action_read_below), { selection.onMarkBelowAsRead(single) })
                    ItemActionButton(Icons.Default.Share, stringResource(R.string.share), { selection.onShare(single) })
                    ItemActionButton(Icons.Default.OpenInBrowser, stringResource(R.string.item_action_browser), { selection.onOpenInBrowser(single) })
                }
                ItemActionButton(Icons.Default.Close, stringResource(R.string.item_action_cancel), selection.onClear)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    viewState: FeedScreenViewState,
    onRefreshVisible: () -> Unit,
    onRefreshAll: () -> Unit,
    onMarkAllAsRead: () -> Unit,
    onShowToolbarMenu: (Boolean) -> Unit,
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
    onAddFeed: () -> Unit,
    onEditFeed: (Long) -> Unit,
    onShowEditDialog: () -> Unit,
    onDismissEditDialog: () -> Unit,
    onDeleteFeeds: (Iterable<Long>) -> Unit,
    onShowDeleteDialog: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onRenameTag: (String, String) -> Unit,
    onShowRenameTagDialog: () -> Unit,
    onDismissRenameTagDialog: () -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    onExportOPML: () -> Unit,
    onImportSavedArticles: () -> Unit,
    onExportSavedArticles: () -> Unit,
    drawerState: DrawerState,
    markAsUnread: (Long, Boolean) -> Unit,
    markAsReadOnSwipe: (id: Long, unread: Boolean, saved: Boolean) -> Unit,
    markBeforeAsRead: (FeedItemCursor) -> Unit,
    markAfterAsRead: (FeedItemCursor) -> Unit,
    onOpenFeedItem: (Long) -> Unit,
    onOpenFeedItemInReader: (Long) -> Unit,
    onOpenFeedItemInCustomTab: (Long) -> Unit,
    onOpenFeedItemInBrowser: (Long) -> Unit,
    onSetBookmark: (Long, Boolean) -> Unit,
    filterCallback: FeedListFilterCallback,
    onShowSearchBar: (Boolean) -> Unit,
    searchCallback: (String) -> Unit,
    feedListState: LazyListState,
    feedGridState: LazyStaggeredGridState,
    pagedFeedItems: LazyPagingItems<FeedListItem>,
    translatedFeedCards: TranslatedFeedCards,
    onTranslateFeedCard: (FeedListItem) -> Unit,
    modifier: Modifier = Modifier,
    readAloudProgress: StateFlow<ReadAloudProgress>? = null,
    ttsOnSeekTo: (Long) -> Unit = {},
    ttsOnSetSpeed: (Float) -> Unit = {},
    ttsOnGoToSpokenText: () -> Unit = {},
    readAloudNeighbors: ReadAloudNeighbors = ReadAloudNeighbors(),
    readAloudAutoContinue: Boolean = false,
    ttsOnSkipArticle: (next: Boolean) -> Unit = {},
    ttsOnSetAutoContinue: (Boolean) -> Unit = {},
    ttsDiagnostics: () -> String = { "" },
    onSetFeedListGrouping: (FeedListGrouping) -> Unit = {},
    onSetSorting: (SortingOptions) -> Unit = {},
    onMarkGroupAsRead: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    onDeleteItem: ((Long) -> Unit)? = null,
    selection: FeedSelection? = null,
    onManageFeeds: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val closeMenuText = stringResource(id = R.string.close_menu)
    val focusRequester = remember { FocusRequester() }
    val flipMode = rememberFlipModePreference()

    LaunchedEffect(viewState.searchBarVisible) {
        if (viewState.searchBarVisible) {
            focusRequester.requestFocus()
        }
    }

    if (flipMode.enabled) {
        HideMainBar()
        // Flip view takes the whole screen: no bars, tabs or buttons until the user exits it.
        // A new pager per feed, so each feed opens at its first page.
        key(viewState.currentFeedOrTag) {
            FeedFlipContent(
                viewState = viewState,
                pagedFeedItems = pagedFeedItems,
                translatedFeedCards = translatedFeedCards,
                onTranslateFeedCard = onTranslateFeedCard,
                markAsUnread = markAsUnread,
                onSetBookmark = onSetBookmark,
                onItemClick = onOpenFeedItem,
                onOpenNavDrawer = {
                    coroutineScope.launch {
                        if (drawerState.isOpen) {
                            drawerState.close()
                        } else {
                            drawerState.open()
                        }
                    }
                },
                onAddFeed = onAddFeed,
                onExit = { flipMode.set(false) },
                onDeleteItem = onDeleteItem,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
            )
        }
        return
    }

    FeedScreen(
        modifier = modifier,
        viewState = viewState,
        snackbarHostState = snackbarHostState,
        hideFab = selection?.active == true,
        onRefreshVisible = onRefreshVisible,
        onOpenNavDrawer = {
            coroutineScope.launch {
                if (drawerState.isOpen) {
                    drawerState.close()
                } else {
                    drawerState.open()
                }
            }
        },
        onMarkAllAsRead = onMarkAllAsRead,
        ttsOnPlay = ttsOnPlay,
        ttsOnPause = ttsOnPause,
        ttsOnStop = ttsOnStop,
        ttsOnSkipNext = ttsOnSkipNext,
        ttsOnSelectLanguage = ttsOnSelectLanguage,
        readAloudProgress = readAloudProgress,
        ttsOnSeekTo = ttsOnSeekTo,
        ttsOnSetSpeed = ttsOnSetSpeed,
        ttsOnGoToSpokenText = ttsOnGoToSpokenText,
        readAloudNeighbors = readAloudNeighbors,
        readAloudAutoContinue = readAloudAutoContinue,
        ttsOnSkipArticle = ttsOnSkipArticle,
        ttsOnSetAutoContinue = ttsOnSetAutoContinue,
        ttsDiagnostics = ttsDiagnostics,
        podcastOnPlay = podcastOnPlay,
        podcastOnPause = podcastOnPause,
        podcastOnStop = podcastOnStop,
        podcastOnSeekBack = podcastOnSeekBack,
        podcastOnSeekForward = podcastOnSeekForward,
        podcastOnSeekTo = podcastOnSeekTo,
        onDismissDeleteDialog = onDismissDeleteDialog,
        onDismissEditDialog = onDismissEditDialog,
        onRenameTag = onRenameTag,
        onShowRenameTagDialog = onShowRenameTagDialog,
        onDismissRenameTagDialog = onDismissRenameTagDialog,
        onDelete = onDeleteFeeds,
        onEditFeed = onEditFeed,
        toolbarActions = {
            if (viewState.searchBarVisible) {
                SearchBar(
                    expanded = false,
                    onExpandedChange = { onShowSearchBar(it) },
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = viewState.search,
                            onQueryChange = searchCallback,
                            onSearch = searchCallback,
                            expanded = true,
                            onExpandedChange = onShowSearchBar,
                            placeholder = { Text(stringResource(R.string.search_verb)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        onShowSearchBar(false)
                                        searchCallback("")
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cancel_search),
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .focusRequester(focusRequester)
                                    .onKeyEventLikeEscape {
                                        onShowSearchBar(false)
                                        searchCallback("")
                                    },
                        )
                    },
                ) {
                }
            } else {
                val flipLabel = stringResource(if (flipMode.enabled) R.string.show_as_list else R.string.show_as_flip_pages)
                PlainTooltipBox(tooltip = { Text(flipLabel) }) {
                    IconButton(onClick = { flipMode.set(!flipMode.enabled) }) {
                        Icon(
                            if (flipMode.enabled) Icons.Outlined.ViewAgenda else Icons.Outlined.AutoStories,
                            contentDescription = flipLabel,
                        )
                    }
                }

                // Latest has the global search bar instead
                if (viewState.feedScreenTitle.type != FeedType.ALL_FEEDS) {
                    PlainTooltipBox(tooltip = { Text(stringResource(R.string.search_verb)) }) {
                        Box {
                            IconButton(
                                onClick = { onShowSearchBar(true) },
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search_verb),
                                )
                            }
                        }
                    }
                }

                PlainTooltipBox(tooltip = { Text(stringResource(R.string.open_menu)) }) {
                    Box {
                        IconButton(
                            onClick = { onShowToolbarMenu(true) },
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.open_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = viewState.showToolbarMenu,
                            onDismissRequest = { onShowToolbarMenu(false) },
                            modifier =
                                Modifier.onKeyEventLikeEscape {
                                    onShowToolbarMenu(false)
                                },
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    onMarkAllAsRead()
                                    onShowToolbarMenu(false)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.mark_all_as_read))
                                },
                            )
                            // Offline mode moved here from the filter line; a chip shows while it is on
                            DropdownMenuItem(
                                onClick = {
                                    filterCallback.setOfflineMode(!viewState.offlineMode)
                                    onShowToolbarMenu(false)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (viewState.offlineMode) Icons.Default.CloudQueue else Icons.Default.CloudOff,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(if (viewState.offlineMode) R.string.go_online else R.string.go_offline))
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = {
                                    onRefreshAll()
                                    onShowToolbarMenu(false)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.synchronize_feeds),
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.synchronize_feeds))
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onAddFeed()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.add_feed))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    if (viewState.visibleFeeds.size == 1) {
                                        onEditFeed(viewState.visibleFeeds.first().id)
                                    } else {
                                        onShowEditDialog()
                                    }
                                    onShowToolbarMenu(false)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.edit_feed))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    onShowDeleteDialog()
                                    onShowToolbarMenu(false)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.delete_feed))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onManageFeeds()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.manage_feeds))
                                },
                            )
                            if (viewState.feedScreenTitle.type == FeedType.TAG && !viewState.currentFeedOrTag.isFeed) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    onClick = {
                                        onShowToolbarMenu(false)
                                        onShowRenameTagDialog()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                        )
                                    },
                                    text = {
                                        Text(stringResource(id = R.string.rename_tag))
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onImport()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.import_feeds_from_opml))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onExportOPML()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.export_feeds_to_opml))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onImportSavedArticles()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.import_saved_articles))
                                },
                            )
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onExportSavedArticles()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.export_saved_articles))
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                    onSettings()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                    )
                                },
                                text = {
                                    Text(stringResource(id = R.string.action_settings))
                                },
                            )
                            // Hidden button for TalkBack
                            DropdownMenuItem(
                                onClick = {
                                    onShowToolbarMenu(false)
                                },
                                text = {},
                                modifier =
                                    Modifier
                                        .height(0.dp)
                                        .safeSemantics {
                                            contentDescription = closeMenuText
                                            role = Role.Button
                                        },
                            )
                        }
                    }
                }
            }
        },
    ) { innerModifier ->
        val screenType =
            when (isCompactDevice()) {
                true -> FeedScreenType.FeedList
                false -> FeedScreenType.FeedGrid
            }

        Column(modifier = innerModifier) {
            // Show tabs, Today and Sort and group stay on screen. The Saved articles list
            // is saved articles only, so it has no Show tabs.
            FeedListFilterBar(
                filter = viewState.filter,
                filterCallback = filterCallback,
                grouping = viewState.feedListGrouping,
                sorting = viewState.currentSorting,
                onSelectGrouping = onSetFeedListGrouping,
                onSelectSorting = onSetSorting,
                showTabs = viewState.currentFeedOrTag.isNotSavedArticles,
                unreadCount = viewState.feedScreenTitle.unreadCount,
                downloadedCount = viewState.downloadedCount,
                offlineMode = viewState.offlineMode,
            )

            when (screenType) {
                FeedScreenType.FeedGrid ->
                    FeedGridContent(
                        viewState = viewState,
                        onOpenNavDrawer = {
                            coroutineScope.launch {
                                if (drawerState.isOpen) {
                                    drawerState.close()
                                } else {
                                    drawerState.open()
                                }
                            }
                        },
                        onAddFeed = onAddFeed,
                        markAsUnread = markAsUnread,
                        markAsReadOnSwipe = markAsReadOnSwipe,
                        markBeforeAsRead = markBeforeAsRead,
                        markAfterAsRead = markAfterAsRead,
                        onItemClick = onOpenFeedItem,
                        onOpenFeedItemInReader = onOpenFeedItemInReader,
                        onOpenFeedItemInCustomTab = onOpenFeedItemInCustomTab,
                        onOpenFeedItemInBrowser = onOpenFeedItemInBrowser,
                        onSetBookmark = onSetBookmark,
                        onDeleteItem = onDeleteItem,
                        selection = selection,
                        gridState = feedGridState,
                        pagedFeedItems = pagedFeedItems,
                        translatedFeedCards = translatedFeedCards,
                        onTranslateFeedCard = onTranslateFeedCard,
                        modifier = Modifier.weight(1f),
                    )

                FeedScreenType.FeedList ->
                    FeedListContent(
                        viewState = viewState,
                        onOpenNavDrawer = {
                            coroutineScope.launch {
                                if (drawerState.isOpen) {
                                    drawerState.close()
                                } else {
                                    drawerState.open()
                                }
                            }
                        },
                        onAddFeed = onAddFeed,
                        markAsUnread = markAsUnread,
                        markAsReadOnSwipe = markAsReadOnSwipe,
                        markBeforeAsRead = markBeforeAsRead,
                        markAfterAsRead = markAfterAsRead,
                        onItemClick = onOpenFeedItem,
                        onOpenFeedItemInReader = onOpenFeedItemInReader,
                        onOpenFeedItemInCustomTab = onOpenFeedItemInCustomTab,
                        onOpenFeedItemInBrowser = onOpenFeedItemInBrowser,
                        onSetBookmark = onSetBookmark,
                        onDeleteItem = onDeleteItem,
                        selection = selection,
                        listState = feedListState,
                        onMarkGroupAsRead = onMarkGroupAsRead,
                        pagedFeedItems = pagedFeedItems,
                        translatedFeedCards = translatedFeedCards,
                        onTranslateFeedCard = onTranslateFeedCard,
                        modifier = Modifier.weight(1f),
                    )
            }

            if (selection?.active == true) {
                FeedSelectionBar(selection = selection)
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun FeedScreen(
    viewState: FeedScreenViewState,
    onRefreshVisible: () -> Unit,
    onOpenNavDrawer: () -> Unit,
    onMarkAllAsRead: () -> Unit,
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
    onDismissDeleteDialog: () -> Unit,
    onDismissEditDialog: () -> Unit,
    onRenameTag: (String, String) -> Unit,
    onShowRenameTagDialog: () -> Unit,
    onDismissRenameTagDialog: () -> Unit,
    onDelete: (Iterable<Long>) -> Unit,
    onEditFeed: (Long) -> Unit,
    toolbarActions: @Composable (RowScope.() -> Unit),
    modifier: Modifier = Modifier,
    readAloudProgress: StateFlow<ReadAloudProgress>? = null,
    ttsOnSeekTo: (Long) -> Unit = {},
    ttsOnSetSpeed: (Float) -> Unit = {},
    ttsOnGoToSpokenText: () -> Unit = {},
    readAloudNeighbors: ReadAloudNeighbors = ReadAloudNeighbors(),
    readAloudAutoContinue: Boolean = false,
    ttsOnSkipArticle: (next: Boolean) -> Unit = {},
    ttsOnSetAutoContinue: (Boolean) -> Unit = {},
    ttsDiagnostics: () -> String = { "" },
    snackbarHostState: SnackbarHostState? = null,
    // While articles are selected, the selection bar takes the button's place
    hideFab: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    var manuallyTriggeredRefresh by rememberSaveable {
        mutableStateOf(false)
    }
    val isRefreshing =
        remember(manuallyTriggeredRefresh, viewState.currentlySyncing) {
            (manuallyTriggeredRefresh || viewState.currentlySyncing)
        }

    LaunchedEffect(viewState.currentlySyncing) {
        if (manuallyTriggeredRefresh && viewState.currentlySyncing) {
            // A sync has happened so can safely set this to false now
            manuallyTriggeredRefresh = false
        }
    }

    LaunchedEffect(manuallyTriggeredRefresh) {
        // In the event that pulling doesn't trigger a refresh. Say if no feeds are present
        // or all feeds are so recent that no sync is triggered - or an error happens in sync
        // THEN we need to manually disable this variable so we don't get an infinite spinner
        if (manuallyTriggeredRefresh) {
            delay(5_000L)
            manuallyTriggeredRefresh = false
        }
    }

    val floatingActionButton: @Composable () -> Unit = {
        PlainTooltipBox(tooltip = { Text(stringResource(R.string.mark_all_as_read)) }) {
            FloatingActionButton(
                onClick = {
                    onMarkAllAsRead()
                    if (viewState.isOpenDrawerOnFab) {
                        onOpenNavDrawer()
                    }
                },
                modifier =
                    Modifier
                        .navigationBarsPadding(),
            ) {
                Icon(
                    Icons.Default.DoneAll,
                    contentDescription = stringResource(R.string.mark_all_as_read),
                )
            }
        }
    }
    val bottomBarVisibleState = remember { MutableTransitionState(viewState.isBottomBarVisible) }
    LaunchedEffect(viewState.isBottomBarVisible) {
        bottomBarVisibleState.targetState = viewState.isBottomBarVisible
    }

    val topAppBarState =
        key(viewState.currentFeedOrTag) {
            rememberTopAppBarState()
        }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    val pullRefreshState =
        rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = {
                manuallyTriggeredRefresh = true
                onRefreshVisible()
            },
        )

    Scaffold(
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
        topBar = {
            if (viewState.feedScreenTitle.type == FeedType.ALL_FEEDS && !viewState.searchBarVisible) {
                // Latest view of the Feeds tab: the global search bar (with the drawer button
                // inside it) and the Latest | By feed switch
                Column {
                    GlobalSearchTopBar(
                        leading = {
                            IconButton(onClick = onOpenNavDrawer) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.navigation_drawer_open))
                            }
                        },
                        actions = toolbarActions,
                    )
                    FeedsViewSwitch(view = com.nononsenseapps.feeder.ui.compose.navigation.FeedsView.LATEST)
                }
            } else {
                SensibleTopAppBar(
                    scrollBehavior = scrollBehavior,
                    title =
                        when (viewState.feedScreenTitle.type) {
                            FeedType.FEED, FeedType.TAG -> viewState.feedScreenTitle.title
                            FeedType.SAVED_ARTICLES -> stringResource(id = R.string.saved_articles)
                            FeedType.ALL_FEEDS -> stringResource(id = R.string.all_feeds)
                        }.let { title ->
                            if (viewState.feedScreenTitle.type == FeedType.SAVED_ARTICLES ||
                                !viewState.showTitleUnreadCount ||
                                viewState.feedScreenTitle.unreadCount == 0 ||
                                title == null
                            ) {
                                title
                            } else {
                                title + " ${
                                    stringResource(
                                        id = R.string.title_unread_count,
                                        viewState.feedScreenTitle.unreadCount,
                                    )
                                }"
                            }
                        } ?: "",
                    navigationIcon = {
                        IconButton(
                            onClick = onOpenNavDrawer,
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.navigation_drawer_open),
                            )
                        }
                    },
                    actions = toolbarActions,
                )
            }
        },
        bottomBar = {
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
                    onGoToSpokenText = ttsOnGoToSpokenText,
                    displayedArticleId = null,
                    onSelectSpeed = ttsOnSetSpeed,
                    diagnostics = ttsDiagnostics,
                    neighbors = readAloudNeighbors,
                    onSkipArticle = ttsOnSkipArticle,
                    autoContinue = readAloudAutoContinue,
                    onSetAutoContinue = ttsOnSetAutoContinue,
                    showNowReading = true,
                )
            }
        },
        floatingActionButton = {
            if (viewState.showFab && !hideFab) {
                AnimatedVisibility(
                    // The read-aloud panel has no room for the button, so it floats above it
                    visible =
                        (bottomBarVisibleState.isIdle && !bottomBarVisibleState.targetState) ||
                            (bottomBarVisibleState.targetState && !viewState.podcastPlayerState.isVisible),
                    enter = scaleIn(animationSpec = tween(256)),
                    exit = scaleOut(animationSpec = tween(256)),
                ) {
                    floatingActionButton()
                }
            }
        },
        modifier =
            modifier
                // The order is important! PullToRefresh MUST come first
                .pullRefresh(state = pullRefreshState)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        contentWindowInsets = WindowInsets.statusBars,
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .padding(padding),
        ) {
            content(
                Modifier,
            )

            PullRefreshIndicator(
                isRefreshing,
                pullRefreshState,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter),
            )

            viewState.translationModelDownloadProgress?.let { progress ->
                TranslationModelDownloadProgress(
                    progress = progress,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                )
            }
        }

        if (viewState.showDeleteDialog) {
            DeleteFeedDialog(
                feeds =
                    ImmutableHolder(
                        viewState.visibleFeeds.map {
                            DeletableFeed(it.id, it.displayTitle)
                        },
                    ),
                onDismiss = onDismissDeleteDialog,
                onDelete = onDelete,
            )
        }

        if (viewState.showEditDialog) {
            EditFeedDialog(
                feeds =
                    ImmutableHolder(
                        viewState.visibleFeeds.map {
                            DeletableFeed(
                                it.id,
                                it.displayTitle,
                            )
                        },
                    ),
                onDismiss = onDismissEditDialog,
                onEdit = onEditFeed,
            )
        }
        if (viewState.showRenameTagDialog) {
            RenameTagDialog(
                currentTagName = viewState.feedScreenTitle.title ?: "",
                onDismiss = onDismissRenameTagDialog,
                onRename = { newName ->
                    onRenameTag(viewState.feedScreenTitle.title ?: "", newName)
                },
            )
        }
    }
}

@Composable
fun FeedListContent(
    viewState: FeedScreenViewState,
    onOpenNavDrawer: () -> Unit,
    onAddFeed: () -> Unit,
    markAsUnread: (Long, Boolean) -> Unit,
    markAsReadOnSwipe: (id: Long, unread: Boolean, saved: Boolean) -> Unit,
    markBeforeAsRead: (FeedItemCursor) -> Unit,
    markAfterAsRead: (FeedItemCursor) -> Unit,
    onItemClick: (Long) -> Unit,
    onOpenFeedItemInReader: (Long) -> Unit,
    onOpenFeedItemInCustomTab: (Long) -> Unit,
    onOpenFeedItemInBrowser: (Long) -> Unit,
    onSetBookmark: (Long, Boolean) -> Unit,
    listState: LazyListState,
    pagedFeedItems: LazyPagingItems<FeedListItem>,
    translatedFeedCards: TranslatedFeedCards,
    onTranslateFeedCard: (FeedListItem) -> Unit,
    modifier: Modifier = Modifier,
    onMarkGroupAsRead: (String) -> Unit = {},
    onDeleteItem: ((Long) -> Unit)? = null,
    selection: FeedSelection? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val activityLauncher: ActivityLauncher by LocalDI.current.instance()

    Box(modifier = modifier) {
        AnimatedVisibility(
            enter = fadeIn(),
            exit = fadeOut(),
            visible = !viewState.haveVisibleFeedItems,
        ) {
            // Keeping the Box behind so the scrollability doesn't override clickable
            // Separate box because scrollable will ignore max size.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            )
            NothingToRead(
                modifier = Modifier,
                onOpenOtherFeed = onOpenNavDrawer,
                onAddFeed = onAddFeed,
            )
        }

        val grouping = viewState.feedListGrouping
        // Groups the user has collapsed, by collapseKey. Survives opening an article and
        // coming back; cleared when the grouping changes.
        var collapsedGroups by rememberSaveable(grouping) { mutableStateOf(ArrayList<String>()) }
        val unreadByGroup =
            remember(viewState.feedListGroups) {
                viewState.feedListGroups.associate { it.key to it.unread }
            }
        val toggleGroup: (String) -> Unit = { key ->
            collapsedGroups =
                ArrayList(if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key)
        }
        // Long press on a header: collapse every group, or expand them all if all are collapsed
        val toggleAllGroups: () -> Unit = {
            val allKeys = viewState.feedListGroups.map { collapseKey(grouping, it.key) }
            collapsedGroups =
                if (allKeys.isNotEmpty() && collapsedGroups.containsAll(allKeys)) {
                    ArrayList()
                } else {
                    ArrayList(allKeys)
                }
        }

        // Divided rows, no gaps (collapsed groups have no height and must not leave gaps either)
        val arrangement = Arrangement.spacedBy(0.dp)

        AnimatedVisibility(
            enter = fadeIn(),
            exit = fadeOut(),
            visible = viewState.haveVisibleFeedItems,
        ) {
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = arrangement,
                contentPadding =
                    if (viewState.isBottomBarVisible) {
                        PaddingValues(0.dp)
                    } else {
                        WindowInsets.navigationBars
                            .only(
                                WindowInsetsSides.Bottom,
                            ).asPaddingValues()
                    },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeSemantics {
                            testTag = "feed_list"
                            collectionInfo = CollectionInfo(pagedFeedItems.itemCount, 1)
                        },
            ) {
                /*
                This is a trick to make the list stay at item 0 when updates come in IF it is
                scrolled to the top.
                 */
                item(
                    key = "SpacerScrollTrick",
                    contentType = "SpacerScrollTrick",
                ) {
                    Spacer(modifier = Modifier.fillMaxWidth())
                }
                items(
                    count = pagedFeedItems.itemCount,
                    key = pagedFeedItems.itemKey { it.id },
                    contentType = pagedFeedItems.itemContentType { it.contentType() },
                ) { itemIndex ->
                    val loadedItem = pagedFeedItems[itemIndex] ?: PLACEHOLDER_ITEM
                    val previewItem = translatedFeedCards.merge(loadedItem)

                    val itemCoroutineScope = rememberCoroutineScope()
                    var itemWasVisible by remember(previewItem.id) { mutableStateOf(false) }
                    // Last known position relative to the top edge of the list. Only items which
                    // have been scrolled past the top may be marked as read - items which leave at
                    // the bottom (scrolling back up, or being pushed down by newly synced items)
                    // must stay unread. Deliberately not snapshot state - it is written from the
                    // layout phase and only read once the item leaves composition, so making it
                    // observable would dirty the snapshot on every scroll frame.
                    val itemScrolledOffTop = remember(previewItem.id) { AtomicBoolean(false) }

                    // Marks items which have been visible long enough as read once they leave
                    // composition, which happens when they scroll out of the viewport
                    DisposableEffect(previewItem.id, markAsUnread) {
                        onDispose {
                            if (itemWasVisible && itemScrolledOffTop.get()) {
                                coroutineScope.launch {
                                    logDebug(LOG_TAG, "Marking ${previewItem.id} as read")
                                    markAsUnread(previewItem.id, false)
                                }
                            }
                        }
                    }

                    // Section header above the first article of each group. peek() reads the
                    // previous row without triggering a page load.
                    val groupKey =
                        if (grouping != FeedListGrouping.NONE && loadedItem !== PLACEHOLDER_ITEM) {
                            collapseKey(grouping, loadedItem.groupKey)
                        } else {
                            null
                        }
                    val collapsed = groupKey != null && groupKey in collapsedGroups
                    if (groupKey != null) {
                        val previous = if (itemIndex > 0) pagedFeedItems.peek(itemIndex - 1) else null
                        if (previous == null || previous.groupKey != loadedItem.groupKey) {
                            FeedListGroupHeader(
                                text = rememberGroupHeaderText(loadedItem, grouping),
                                collapsed = collapsed,
                                onToggle = { toggleGroup(groupKey) },
                                unreadCount = unreadByGroup[loadedItem.groupKey] ?: 0,
                                onMarkGroupAsRead = { onMarkGroupAsRead(loadedItem.groupKey) },
                                onToggleAll = toggleAllGroups,
                            )
                        }
                    }

                    if (!collapsed) {
                        // Only articles that are shown get their preview translated
                        LaunchedEffect(loadedItem.id, loadedItem.title, loadedItem.snippet, translatedFeedCards.generation, onTranslateFeedCard) {
                            onTranslateFeedCard(loadedItem)
                        }

                        SwipeableFeedItemPreview(
                            onSwipe = { currentState ->
                                markAsReadOnSwipe(
                                    previewItem.id,
                                    !currentState,
                                    previewItem.bookmarked,
                                )
                            },
                            onDelete = onDeleteItem?.let { delete -> { delete(previewItem.id) } },
                            selectionMode = selection?.active == true,
                            selected = selection?.ids?.contains(previewItem.id) == true,
                            onToggleSelect = selection?.let { sel -> { sel.onToggle(previewItem.id) } },
                            filter = viewState.filter,
                            item = previewItem,
                            showThumbnail = viewState.showThumbnails,
                            swipeAsRead = viewState.swipeAsRead,
                            onMarkAboveAsRead = {
                                markBeforeAsRead(previewItem.cursor)
                                if (viewState.filter.onlyUnread) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(0)
                                    }
                                }
                            },
                            onMarkBelowAsRead = {
                                markAfterAsRead(previewItem.cursor)
                            },
                            onToggleBookmark = {
                                onSetBookmark(previewItem.id, !previewItem.bookmarked)
                            },
                            onShareItem = {
                                val intent =
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            if (previewItem.link != null) {
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    stripTrackingParameters(previewItem.link),
                                                )
                                            }
                                            putExtra(Intent.EXTRA_TITLE, previewItem.title)
                                            type = "text/plain"
                                        },
                                        null,
                                    )
                                activityLauncher.startActivity(
                                    openAdjacentIfSuitable = false,
                                    intent = intent,
                                )
                            },
                            onItemClick = {
                                onItemClick(previewItem.id)
                            },
                            onOpenFeedItemInReader = {
                                onOpenFeedItemInReader(previewItem.id)
                            },
                            onOpenFeedItemInCustomTab = {
                                onOpenFeedItemInCustomTab(previewItem.id)
                            },
                            onOpenFeedItemInBrowser = {
                                onOpenFeedItemInBrowser(previewItem.id)
                            },
                            modifier =
                                Modifier
                                    // Deliberately not animateItem(). The list is not an animated
                                    // list: entries appear and disappear when the database says so
                                    // (opening an article, a sync landing, a filter changing), and
                                    // those transitions are not worth the cost here. Every frame
                                    // spent driving N item animations competes with the scroll
                                    // itself, and the animator places and clips each entry
                                    // separately - which is exactly how an entry and its separator
                                    // end up on different offsets. Weighting everything towards
                                    // scrolling being exact and cheap is the more responsive
                                    // behavior on a feed list of thousands of rows.
                                    .safeSemantics {
                                        collectionItemInfo =
                                            CollectionItemInfo(
                                                rowIndex = itemIndex,
                                                rowSpan = 1,
                                                columnIndex = 1,
                                                columnSpan = 1,
                                            )
                                    }.let { modifier ->
                                        if (viewState.markAsReadOnScroll && previewItem.unread) {
                                            modifier.trackVisibility(0.9f) { info ->
                                                // Remembered so the disposable effect can tell in which
                                                // direction the item left the screen
                                                itemScrolledOffTop.set(info.isScrolledOffTop)
                                                if (info.isAboveThreshold) {
                                                    // Using itemCoroutineScope because that scope gets destroyed when item scrolls off screen
                                                    // So implicitly, if user is scrolling very fast, the coroutine will be cancelled
                                                    // before marking as read
                                                    itemCoroutineScope.launch {
                                                        delay(REQUIRED_VISIBLE_TIME_FOR_MARK_AS_READ)
                                                        logDebug(LOG_TAG, "Item $itemIndex marking as wasVisible")
                                                        itemWasVisible = true
                                                        // Marks as read in disposable effect
                                                    }
                                                }
                                            }
                                        } else {
                                            modifier
                                        }
                                    },
                            swipeEnabled = !listState.isScrollInProgress,
                        )

                        // Emitted inside the entry's own item, so the list positions it from the same
                        // measurement that positions the row above it and the two cannot drift apart.
                        //
                        // A filled Box rather than HorizontalDivider: the divider draws through a Canvas
                        // whose colour comes from DividerTokens, which resolved to nothing visible on this
                        // surface. A background modifier has no token and no Canvas, so what is drawn is
                        // exactly the colour written here.
                        if (itemIndex < pagedFeedItems.itemCount - 1) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                    }
                }
                /*
                This item is providing padding for the FAB
                 */
                if (viewState.showFab && !viewState.isBottomBarVisible) {
                    item(
                        key = "SpacerForFab",
                        contentType = "SpacerForFab",
                    ) {
                        Spacer(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height((56 + 16).dp),
                        )
                    }
                }
            }
        }

        // Sticky header: once the list has scrolled, the group of the topmost article stays
        // pinned above the list. Index 0 of the list is the scroll trick spacer.
        if (grouping != FeedListGrouping.NONE && viewState.haveVisibleFeedItems) {
            val stickyIndex by remember(listState) {
                derivedStateOf {
                    val first = listState.firstVisibleItemIndex
                    if (first > 1 || (first == 1 && listState.firstVisibleItemScrollOffset > 0)) {
                        first - 1
                    } else {
                        -1
                    }
                }
            }
            val stickyItem =
                if (stickyIndex in 0 until pagedFeedItems.itemCount) {
                    pagedFeedItems.peek(stickyIndex)
                } else {
                    null
                }
            if (stickyItem != null) {
                val stickyKey = collapseKey(grouping, stickyItem.groupKey)
                FeedListGroupHeader(
                    text = rememberGroupHeaderText(stickyItem, grouping),
                    collapsed = stickyKey in collapsedGroups,
                    onToggle = { toggleGroup(stickyKey) },
                    unreadCount = unreadByGroup[stickyItem.groupKey] ?: 0,
                    onMarkGroupAsRead = { onMarkGroupAsRead(stickyItem.groupKey) },
                    onToggleAll = toggleAllGroups,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedGridContent(
    viewState: FeedScreenViewState,
    onOpenNavDrawer: () -> Unit,
    onAddFeed: () -> Unit,
    markAsUnread: (Long, Boolean) -> Unit,
    markAsReadOnSwipe: (id: Long, unread: Boolean, saved: Boolean) -> Unit,
    markBeforeAsRead: (FeedItemCursor) -> Unit,
    markAfterAsRead: (FeedItemCursor) -> Unit,
    onItemClick: (Long) -> Unit,
    onOpenFeedItemInReader: (Long) -> Unit,
    onOpenFeedItemInCustomTab: (Long) -> Unit,
    onOpenFeedItemInBrowser: (Long) -> Unit,
    onSetBookmark: (Long, Boolean) -> Unit,
    gridState: LazyStaggeredGridState,
    pagedFeedItems: LazyPagingItems<FeedListItem>,
    translatedFeedCards: TranslatedFeedCards,
    onTranslateFeedCard: (FeedListItem) -> Unit,
    modifier: Modifier = Modifier,
    onDeleteItem: ((Long) -> Unit)? = null,
    selection: FeedSelection? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val activityLauncher: ActivityLauncher by LocalDI.current.instance()

    Box(modifier = modifier) {
        AnimatedVisibility(
            enter = fadeIn(),
            exit = fadeOut(),
            visible = !viewState.haveVisibleFeedItems,
        ) {
            // Keeping the Box behind so the scrollability doesn't override clickable
            // Separate box because scrollable will ignore max size.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
            )
            NothingToRead(
                modifier = Modifier,
                onOpenOtherFeed = onOpenNavDrawer,
                onAddFeed = onAddFeed,
            )
        }

        val arrangement = Arrangement.spacedBy(LocalDimens.current.gutter)

        AnimatedVisibility(
            enter = fadeIn(),
            exit = fadeOut(),
            visible = viewState.haveVisibleFeedItems,
        ) {
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Fixed(if (viewState.forceSingleColumn) 1 else LocalDimens.current.feedScreenColumns),
                contentPadding =
                    if (viewState.isBottomBarVisible) {
                        PaddingValues(0.dp)
                    } else {
                        WindowInsets.navigationBars
                            .only(
                                WindowInsetsSides.Bottom,
                            ).addMargin(LocalDimens.current.margin)
                            .asPaddingValues()
                    },
                verticalItemSpacing = LocalDimens.current.gutter,
                horizontalArrangement = arrangement,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = pagedFeedItems.itemCount,
                    key = pagedFeedItems.itemKey { it.id },
                    contentType = pagedFeedItems.itemContentType { it.contentType() },
                ) { itemIndex ->
                    val loadedItem = pagedFeedItems[itemIndex] ?: PLACEHOLDER_ITEM
                    val previewItem = translatedFeedCards.merge(loadedItem)

                    val itemCoroutineScope = rememberCoroutineScope()
                    var itemWasVisible by remember(previewItem.id) { mutableStateOf(false) }
                    // Last known position relative to the top edge of the list. Only items which
                    // have been scrolled past the top may be marked as read - items which leave at
                    // the bottom (scrolling back up, or being pushed down by newly synced items)
                    // must stay unread. Deliberately not snapshot state - it is written from the
                    // layout phase and only read once the item leaves composition, so making it
                    // observable would dirty the snapshot on every scroll frame.
                    val itemScrolledOffTop = remember(previewItem.id) { AtomicBoolean(false) }

                    LaunchedEffect(loadedItem.id, loadedItem.title, loadedItem.snippet, translatedFeedCards.generation, onTranslateFeedCard) {
                        onTranslateFeedCard(loadedItem)
                    }

                    // Marks items which have been visible long enough as read once they leave
                    // composition, which happens when they scroll out of the viewport
                    DisposableEffect(previewItem.id, markAsUnread) {
                        onDispose {
                            if (itemWasVisible && itemScrolledOffTop.get()) {
                                coroutineScope.launch {
                                    logDebug(LOG_TAG, "Marking ${previewItem.id} as read")
                                    markAsUnread(previewItem.id, false)
                                }
                            }
                        }
                    }

                    SwipeableFeedItemPreview(
                        onSwipe = { currentState ->
                            markAsReadOnSwipe(
                                previewItem.id,
                                !currentState,
                                previewItem.bookmarked,
                            )
                        },
                        onDelete = onDeleteItem?.let { delete -> { delete(previewItem.id) } },
                        selectionMode = selection?.active == true,
                        selected = selection?.ids?.contains(previewItem.id) == true,
                        onToggleSelect = selection?.let { sel -> { sel.onToggle(previewItem.id) } },
                        filter = viewState.filter,
                        item = previewItem,
                        showThumbnail = viewState.showThumbnails,
                        swipeAsRead = viewState.swipeAsRead,
                        onMarkAboveAsRead = {
                            markBeforeAsRead(previewItem.cursor)
                            if (viewState.filter.onlyUnread) {
                                coroutineScope.launch {
                                    gridState.scrollToItem(0)
                                }
                            }
                        },
                        onMarkBelowAsRead = {
                            markAfterAsRead(previewItem.cursor)
                        },
                        onToggleBookmark = {
                            onSetBookmark(previewItem.id, !previewItem.bookmarked)
                        },
                        onShareItem = {
                            val intent =
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        if (previewItem.link != null) {
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                stripTrackingParameters(previewItem.link),
                                            )
                                        }
                                        putExtra(Intent.EXTRA_TITLE, previewItem.title)
                                        type = "text/plain"
                                    },
                                    null,
                                )
                            activityLauncher.startActivity(
                                openAdjacentIfSuitable = false,
                                intent = intent,
                            )
                        },
                        onItemClick = {
                            onItemClick(previewItem.id)
                        },
                        onOpenFeedItemInReader = {
                            onOpenFeedItemInReader(previewItem.id)
                        },
                        onOpenFeedItemInCustomTab = {
                            onOpenFeedItemInCustomTab(previewItem.id)
                        },
                        onOpenFeedItemInBrowser = {
                            onOpenFeedItemInBrowser(previewItem.id)
                        },
                        modifier =
                            if (viewState.markAsReadOnScroll && previewItem.unread) {
                                Modifier
                                    .trackVisibility(0.9f) { info ->
                                        // Remembered so the disposable effect can tell in which
                                        // direction the item left the screen
                                        itemScrolledOffTop.set(info.isScrolledOffTop)
                                        if (info.isAboveThreshold) {
                                            // Using itemCoroutineScope because that scope gets destroyed when item scrolls off screen
                                            // So implicitly, if user is scrolling very fast, the coroutine will be cancelled
                                            // before marking as read
                                            itemCoroutineScope.launch {
                                                delay(REQUIRED_VISIBLE_TIME_FOR_MARK_AS_READ)
                                                logDebug(LOG_TAG, "Item $itemIndex marking as wasVisible")
                                                itemWasVisible = true
                                                // Marks as read in disposable effect
                                            }
                                        }
                                    }.then(
                                        // Disable item animations during refresh to prevent scroll position issues
                                        if (!viewState.currentlySyncing) {
                                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                        } else {
                                            Modifier
                                        },
                                    )
                            } else {
                                Modifier.then(
                                    // Disable item animations during refresh to prevent scroll position issues
                                    if (!viewState.currentlySyncing) {
                                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    } else {
                                        Modifier
                                    },
                                )
                            },
                        swipeEnabled = !gridState.isScrollInProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslationModelDownloadProgress(
    progress: BergamotModelDownloadProgress,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        TranslationProgressContent(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Immutable
data class FeedOrTag(
    val id: Long,
    val tag: String,
)

@Suppress("unused")
val FeedOrTag.isFeed
    get() = id > ID_UNSET

val FeedOrTag.isSavedArticles
    get() = id == ID_SAVED_ARTICLES

val FeedOrTag.isNotSavedArticles
    get() = !isSavedArticles

enum class FeedScreenType {
    FeedGrid,
    FeedList,
}

// See https://issuetracker.google.com/issues/177245496#comment24
@Composable
fun <T : Any> LazyPagingItems<T>.rememberLazyListState(): LazyListState {
    // After recreation, LazyPagingItems first return 0 items, then the cached items.
    // This behavior/issue is resetting the LazyListState scroll position.
    // Below is a workaround. More info: https://issuetracker.google.com/issues/177245496.
    //
    // The fallback is latched: it applies only until items have been seen once and is never
    // returned afterwards. Testing `itemCount == 0` on every recomposition meant that any event
    // which momentarily empties the page also swapped in a brand new LazyListState - a paging
    // invalidation (a sync landing, an entry being marked read) reports zero items before the new
    // page arrives. Replacing the state instance mid-refresh makes the list lay itself out from
    // scratch, which reads as a flicker and a jump back to the top. Latching keeps the re-creation
    // workaround while removing that churn.
    val realState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    val emptyState = remember(this) { LazyListState(0, 0) }
    // Deliberately not snapshot state: it is read during composition only to choose an instance,
    // and must not itself trigger a recomposition.
    val hasHadItems = remember(this) { AtomicBoolean(itemCount > 0) }

    if (itemCount > 0) {
        hasHadItems.set(true)
    }

    return if (hasHadItems.get()) realState else emptyState
}

private const val REQUIRED_VISIBLE_TIME_FOR_MARK_AS_READ = 500L

private val PLACEHOLDER_ITEM =
    FeedListItem(
        id = ID_UNSET,
        title = "",
        snippet = "",
        feedTitle = "",
        unread = true,
        pubDate = "",
        image = null,
        link = null,
        bookmarked = false,
        feedImageUrl = null,
        primarySortTime = Instant.EPOCH,
        rawPubDate = null,
        wordCount = 0,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTooltipBox(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = {
            PlainTooltip {
                tooltip()
            }
        },
        modifier = modifier,
        content = content,
    )
}
