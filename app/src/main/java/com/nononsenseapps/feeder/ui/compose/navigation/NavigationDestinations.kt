package com.nononsenseapps.feeder.ui.compose.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavArgumentBuilder
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.nononsenseapps.feeder.base.diAwareViewModel
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.NavigationDeepLinkViewModel
import com.nononsenseapps.feeder.ui.compose.discover.DiscoverScreen
import com.nononsenseapps.feeder.ui.compose.downloads.DownloadsScreen
import com.nononsenseapps.feeder.ui.compose.editfeed.CreateFeedScreen
import com.nononsenseapps.feeder.ui.compose.editfeed.CreateFeedScreenViewModel
import com.nononsenseapps.feeder.ui.compose.editfeed.EditFeedScreen
import com.nononsenseapps.feeder.ui.compose.editfeed.EditFeedScreenViewModel
import com.nononsenseapps.feeder.ui.compose.feed.FeedScreen
import com.nononsenseapps.feeder.ui.compose.feedarticle.ArticleScreen
import com.nononsenseapps.feeder.ui.compose.home.HomeScreen
import com.nononsenseapps.feeder.ui.compose.managefeeds.ManageFeedsScreen
import com.nononsenseapps.feeder.ui.compose.playlists.PlaylistsScreen
import com.nononsenseapps.feeder.ui.compose.searchfeed.SearchFeedScreen
import com.nononsenseapps.feeder.ui.compose.settings.SettingsScreen
import com.nononsenseapps.feeder.ui.compose.settings.TextSettingsScreen
import com.nononsenseapps.feeder.ui.compose.settings.TextSettingsViewModel
import com.nononsenseapps.feeder.ui.compose.sync.SyncScreen
import com.nononsenseapps.feeder.ui.compose.sync.SyncScreenViewModel
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import com.nononsenseapps.feeder.util.logDebug

private const val LOG_TAG = "FEEDER_NAV"

sealed class NavigationDestination(
    protected val path: String,
    protected val navArguments: List<NavigationArgument>,
    val deepLinks: List<NavDeepLink>,
    private val enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = {
        fadeIn()
    },
    private val exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = {
        fadeOut()
    },
    private val popEnterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = {
        fadeIn()
    },
    private val popExitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?)? = {
        fadeOut()
    },
) {
    val arguments: List<NamedNavArgument> = navArguments.map { it.namedNavArgument }

    val route: String

    init {
        val completePath =
            (
                listOf(path) +
                    navArguments
                        .asSequence()
                        .filterIsInstance<PathParamArgument>()
                        .map { "{${it.name}}" }
                        .toList()
            ).joinToString(separator = "/")

        val queryParams =
            navArguments
                .asSequence()
                .filterIsInstance<QueryParamArgument>()
                .map { "${it.name}={${it.name}}" }
                .joinToString(prefix = "?", separator = "&")

        route =
            if (queryParams == "?") {
                completePath
            } else {
                completePath + queryParams
            }
    }

    fun register(
        navGraphBuilder: NavGraphBuilder,
        navController: NavController,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        navGraphBuilder.composable(
            route = route,
            arguments = arguments,
            deepLinks = deepLinks,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition,
        ) { backStackEntry ->
            RegisterScreen(
                navController = navController,
                backStackEntry = backStackEntry,
                navDrawerListState = navDrawerListState,
                mainActivityViewModel = mainActivityViewModel,
            )
        }
    }

    @Composable
    protected abstract fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    )
}

sealed class NavigationArgument(
    val name: String,
    builder: NavArgumentBuilder.() -> Unit,
) {
    val namedNavArgument = navArgument(name, builder)
}

class QueryParamArgument(
    name: String,
    builder: NavArgumentBuilder.() -> Unit,
) : NavigationArgument(name, builder)

class PathParamArgument(
    name: String,
    builder: NavArgumentBuilder.() -> Unit,
) : NavigationArgument(name, builder)

data object SearchFeedDestination : NavigationDestination(
    path = "search/feed",
    navArguments =
        listOf(
            QueryParamArgument("feedUrl") {
                type = NavType.StringType
                defaultValue = null
                nullable = true
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        feedUrl: String? = null,
    ) {
        val params =
            queryParams {
                +("feedUrl" to feedUrl)
            }

        navController.navigate(path + params) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        SearchFeedScreen(
            onNavigateUp = {
                navController.popBackStack()
            },
            searchFeedViewModel = backStackEntry.diAwareViewModel(),
            {
                AddFeedDestination.navigate(
                    navController,
                    feedUrl = it.url,
                    feedTitle = it.title,
                    feedImage = it.feedImage,
                )
            },
            initialFeedUrl = backStackEntry.arguments?.getString("feedUrl"),
        )
    }
}

data object DownloadsDestination : NavigationDestination(
    path = "downloads",
    navArguments = emptyList(),
    // The background-play notification opens Downloads
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/downloads"
            },
        ),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        DownloadsScreen(
            onNavigateUp = { navController.popBackStack() },
            viewModel = backStackEntry.diAwareViewModel(),
        )
    }
}

/** Playlists to listen to (smart and hand-made), with the Now playing bar and car mode. */
data object PlaylistsDestination : NavigationDestination(
    path = "playlists",
    navArguments =
        listOf(
            // A playlist to open right away (from global search), "" for the list of playlists
            QueryParamArgument("open") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    // The playlist notification opens Playlists
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/playlists"
            },
        ),
) {
    fun navigate(
        navController: NavController,
        open: String = "",
    ) {
        navController.navigate(if (open.isEmpty()) path else "$path?open=${Uri.encode(open)}") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val viewModel: com.nononsenseapps.feeder.ui.compose.playlists.PlaylistsViewModel = backStackEntry.diAwareViewModel()
        val open = remember { backStackEntry.arguments?.getString("open").orEmpty() }
        // Only the first time: Back from the open playlist then shows the list
        var opened by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(open) {
            if (open.isNotEmpty() && !opened) viewModel.open(open)
            opened = true
        }
        PlaylistsScreen(
            viewModel = viewModel,
            onNavigateUp = { navController.popBackStack() },
            onOpenArticle = { id -> ArticleDestination.navigate(navController, itemId = id) },
        )
    }
}

/** Global search: articles, episodes, videos, transcripts, highlights, feeds, folders and playlists. */
data object GlobalSearchDestination : NavigationDestination(
    path = "search/global",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        com.nononsenseapps.feeder.ui.compose.search.GlobalSearchScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            nav =
                com.nononsenseapps.feeder.ui.compose.search.GlobalSearchNav(
                    onBack = { navController.popBackStack() },
                    onOpenArticle = { id -> ArticleDestination.navigate(navController, itemId = id) },
                    onOpenFeed = { id -> FeedDestination.navigate(navController, feedId = id, keepBackStack = true) },
                    onOpenFolder = { tag -> FeedDestination.navigate(navController, tag = tag, keepBackStack = true) },
                    onOpenPlaylist = { id -> PlaylistsDestination.navigate(navController, open = id) },
                    onOpenLink = { link -> runCatching { uriHandler.openUri(link) } },
                    onOpenBook = { book, cfi -> EbookReaderDestination.navigate(navController, book, cfi) },
                    onOpenZim = { book -> ZimReaderDestination.navigate(navController, book) },
                    onAddFeed = { SearchFeedDestination.navigate(navController) },
                    onDiscover = { DiscoverDestination.navigate(navController) },
                    onPlaylists = { PlaylistsDestination.navigate(navController) },
                    onHighlights = { HighlightsDestination.navigate(navController) },
                ),
        )
    }
}

/** Every highlight, grouped by article, with filters, search and share as image. */
data object HighlightsDestination : NavigationDestination(
    path = "highlights",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.highlights.HighlightsScreen(
            onNavigateUp = { navController.popBackStack() },
            onOpenArticle = { id -> ArticleDestination.navigate(navController, itemId = id) },
            onOpenBook = { book, cfi -> EbookReaderDestination.navigate(navController, book, cfi) },
        )
    }
}

/** Manage feeds: every feed by folder, with sorting, quick settings, stats, moving and deleting. */
data object ManageFeedsDestination : NavigationDestination(
    path = "manage/feeds",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        ManageFeedsScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            onNavigateUp = { navController.popBackStack() },
            onAddFeed = { SearchFeedDestination.navigate(navController) },
            onShowArticles = { id -> FeedDestination.navigate(navController, feedId = id, keepBackStack = true) },
            onEditFeed = { id -> EditFeedDestination.navigate(navController, feedId = id) },
        )
    }
}

/** Home: every feed as a tile, with type and folder filters. The start screen. */
data object HomeDestination : NavigationDestination(
    path = "home",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            popUpTo(navController.graph.id) {
                inclusive = true
            }
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        HomeScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            onOpenFeed = { id -> FeedDestination.navigate(navController, feedId = id, keepBackStack = true) },
            onOpenFolderArticles = { tag -> FeedDestination.navigate(navController, tag = tag, keepBackStack = true) },
            onAddFeed = { SearchFeedDestination.navigate(navController) },
            onManageFeeds = { ManageFeedsDestination.navigate(navController) },
            onEditFeed = { id -> EditFeedDestination.navigate(navController, feedId = id) },
        )
    }
}

/** Library tab: Saved, Highlights, Downloads, Settings and Sync. */
data object LibraryDestination : NavigationDestination(
    path = "library",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.library.LibraryScreen(
            onOpenSaved = { FeedDestination.navigate(navController, feedId = ID_SAVED_ARTICLES, keepBackStack = true) },
            onHighlights = { HighlightsDestination.navigate(navController) },
            onDownloads = { DownloadsDestination.navigate(navController) },
            onSettings = { SettingsDestination.navigate(navController) },
            onSync = { SyncScreenDestination.navigate(navController, syncCode = "", secretKey = "") },
        )
    }
}

/** The Ebooks tab: books from Dropbox and the phone, read with foliate-js like on the web. */
data object EbooksDestination : NavigationDestination(
    path = "ebooks",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.ebooks.EbooksScreen(
            onOpenBook = { EbookReaderDestination.navigate(navController, it, null) },
        )
    }
}

/** The Audiobooks tab: books from Dropbox's /Audiobooks, streamed or downloaded. */
data object AudiobooksDestination : NavigationDestination(
    path = "audiobooks",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.audiobooks.AudiobooksScreen(
            onOpenBook = { AudiobookPlayerDestination.navigate(navController, it) },
        )
    }
}

/** One audiobook's player: chapters, speed, sleep timer. */
data object AudiobookPlayerDestination : NavigationDestination(
    path = "audiobooks/play",
    navArguments =
        listOf(
            QueryParamArgument("book") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        book: String,
    ) {
        navController.navigate("$path?book=${Uri.encode(book)}") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.audiobooks.AudiobookPlayerScreen(
            bookId = backStackEntry.arguments?.getString("book").orEmpty(),
            onNavigateUp = { navController.popBackStack() },
        )
    }
}

/** Reads one ebook, optionally at a place (an EPUB CFI, from a highlight). */
data object EbookReaderDestination : NavigationDestination(
    path = "ebooks/read",
    navArguments =
        listOf(
            QueryParamArgument("book") {
                type = NavType.StringType
                defaultValue = ""
            },
            QueryParamArgument("at") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        book: String,
        at: String?,
    ) {
        navController.navigate("$path?book=${Uri.encode(book)}&at=${Uri.encode(at.orEmpty())}") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.ebooks.EbookReaderScreen(
            bookId = backStackEntry.arguments?.getString("book").orEmpty(),
            at = backStackEntry.arguments?.getString("at")?.ifBlank { null },
            onNavigateUp = { navController.popBackStack() },
        )
    }
}

/** The ZIM tab: offline websites (Wikipedia, Stack Exchange, zimit copies…) kept on the phone. */
data object ZimDestination : NavigationDestination(
    path = "zim",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.zim.ZimLibraryScreen(
            onOpenBook = { ZimReaderDestination.navigate(navController, it) },
        )
    }
}

/** Reads one ZIM book. */
data object ZimReaderDestination : NavigationDestination(
    path = "zim/read",
    navArguments =
        listOf(
            QueryParamArgument("book") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        book: String,
    ) {
        navController.navigate("$path?book=${Uri.encode(book)}") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        com.nononsenseapps.feeder.ui.compose.zim.ZimReaderScreen(
            bookId = backStackEntry.arguments?.getString("book").orEmpty(),
            onNavigateUp = { navController.popBackStack() },
        )
    }
}

/** Discover: find new text feeds, podcasts and YouTube channels and add them to folders. */
data object DiscoverDestination : NavigationDestination(
    path = "discover",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        DiscoverScreen(
            viewModel = backStackEntry.diAwareViewModel(),
            onOpenFeed = { id -> FeedDestination.navigate(navController, feedId = id, keepBackStack = true) },
        )
    }
}

data object TextSettingsDestination : NavigationDestination(
    path = "font/add",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val textSettingsViewModel: TextSettingsViewModel = backStackEntry.diAwareViewModel()

        TextSettingsScreen(
            onNavigateUp = {
                navController.popBackStack()
            },
            textSettingsViewModel = textSettingsViewModel,
        )
    }
}

data object AddFeedDestination : NavigationDestination(
    path = "add/feed",
    navArguments =
        listOf(
            PathParamArgument("feedUrl") {
                type = NavType.StringType
            },
            QueryParamArgument("feedTitle") {
                type = NavType.StringType
                defaultValue = ""
            },
            QueryParamArgument("feedImage") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        feedUrl: String,
        feedTitle: String = "",
        feedImage: String = "",
    ) {
        val params =
            queryParams {
                +("feedTitle" to feedTitle)
                +("feedImage" to feedImage)
            }

        navController.navigate("$path/${Uri.encode(feedUrl)}$params") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val createFeedScreenViewModel: CreateFeedScreenViewModel = backStackEntry.diAwareViewModel()

        CreateFeedScreen(
            onNavigateUp = {
                navController.popBackStack()
            },
            createFeedScreenViewModel = createFeedScreenViewModel,
        ) { feedId ->
            FeedDestination.navigate(navController, feedId = feedId)
        }
    }
}

data object EditFeedDestination : NavigationDestination(
    path = "edit/feed",
    navArguments =
        listOf(
            PathParamArgument("feedId") {
                type = NavType.LongType
            },
        ),
    deepLinks = emptyList(),
) {
    fun navigate(
        navController: NavController,
        feedId: Long,
    ) {
        navController.navigate("$path/$feedId") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val editFeedScreenViewModel: EditFeedScreenViewModel = backStackEntry.diAwareViewModel()
        EditFeedScreen(
            onNavigateUp = {
                navController.popBackStack()
            },
            editFeedScreenViewModel = editFeedScreenViewModel,
            onOk = { feedId ->
                FeedDestination.navigate(navController, feedId = feedId)
            },
            onShowArticles = { feedId ->
                FeedDestination.navigate(navController, feedId = feedId)
            },
            onDeleted = {
                // The article and feed screens behind this page are gone with the feed
                FeedDestination.navigate(navController, feedId = ID_ALL_FEEDS)
            },
        )
    }
}

data object SettingsDestination : NavigationDestination(
    path = "settings",
    navArguments = emptyList(),
    deepLinks = emptyList(),
) {
    fun navigate(navController: NavController) {
        navController.navigate(path) {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        SettingsScreen(
            onNavigateUp = {
                if (!navController.popBackStack()) {
                    FeedDestination.navigate(navController)
                }
            },
            onNavigateToSyncScreen = {
                SyncScreenDestination.navigate(
                    navController = navController,
                    syncCode = "",
                    secretKey = "",
                )
            },
            onNavigateToTextSettingsScreen = {
                TextSettingsDestination.navigate(navController)
            },
            settingsViewModel = backStackEntry.diAwareViewModel(),
        )
    }
}

data object FeedDestination : NavigationDestination(
    path = "feed",
    navArguments =
        listOf(
            QueryParamArgument("id") {
                type = NavType.LongType
                defaultValue = ID_UNSET
            },
            QueryParamArgument("tag") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/feed?id={id}&tag={tag}"
            },
        ),
) {
    fun navigate(
        navController: NavController,
        feedId: Long = ID_UNSET,
        tag: String = "",
        /** From Home: keep Home under the list, so Back returns to it. */
        keepBackStack: Boolean = false,
    ) {
        val params =
            queryParams {
                if (feedId != ID_UNSET) {
                    +("id" to "$feedId")
                }
                +("tag" to tag)
            }

        logDebug(LOG_TAG, "Navigate to $path$params. Current: ${navController.currentDestination?.route}")

        if (keepBackStack) {
            navController.navigate("$path$params") { launchSingleTop = true }
            return
        }

        navController.navigate("$path$params") {
            // Back to Home (the Feeds tab's tiles) only, so a stack doesn't build up as feeds
            // are picked in the drawer, and the bottom bar's tabs keep their place
            popUpTo(HomeDestination.route)
            // Avoid multiple copies of the same destination when
            // re-selecting the same item
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val feedId =
            remember {
                backStackEntry.arguments?.getLong("id")
                    ?: ID_UNSET
            }
        val tag =
            remember {
                backStackEntry.arguments?.getString("tag")
                    ?: ""
            }

        val navigationDeepLinkViewModel: NavigationDeepLinkViewModel =
            backStackEntry.diAwareViewModel()

        // Saved with this back stack entry: false only the first time the list is shown, not
        // when coming back to it from an article
        var listOpened by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(feedId, tag) {
            logDebug(LOG_TAG, "FeedDestinationScreen setCurrent: $feedId, $tag")
            if (feedId != ID_UNSET || tag.isNotBlank()) {
                navigationDeepLinkViewModel.setCurrentFeedAndTag(
                    feedId = feedId,
                    tag = tag,
                    freshList = !listOpened,
                )
            }
            listOpened = true
        }
        FeedScreen(
            navController = navController,
            viewModel = backStackEntry.diAwareViewModel(),
            navDrawerListState = navDrawerListState,
        )
    }
}

/**
 * Direction of the article swipe that is opening the next article, so it can slide in from
 * the side it was swiped from.
 */
enum class ArticleSwipeDirection {
    NONE,
    NEXT,
    PREVIOUS,
}

private var pendingArticleSwipe = ArticleSwipeDirection.NONE

data object ArticleDestination : NavigationDestination(
    path = "reader",
    navArguments =
        listOf(
            PathParamArgument("itemId") {
                type = NavType.LongType
            },
        ),
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/article/{itemId}"
            },
        ),
    enterTransition = {
        when (pendingArticleSwipe) {
            ArticleSwipeDirection.NEXT -> slideInHorizontally { it }
            ArticleSwipeDirection.PREVIOUS -> slideInHorizontally { -it }
            ArticleSwipeDirection.NONE -> fadeIn()
        }
    },
) {
    fun navigate(
        navController: NavController,
        itemId: Long,
    ) {
        pendingArticleSwipe = ArticleSwipeDirection.NONE
        navController.navigate("$path/$itemId") {
            launchSingleTop = true
        }
    }

    /**
     * Replaces the open article with a neighbour from the list, so back still returns to the
     * list.
     */
    fun swipeTo(
        navController: NavController,
        itemId: Long,
        direction: ArticleSwipeDirection,
    ) {
        pendingArticleSwipe = direction
        navController.navigate("$path/$itemId") {
            popUpTo(this@ArticleDestination.route) {
                inclusive = true
            }
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val itemId =
            remember {
                backStackEntry.arguments?.getLong("itemId")
                    ?: error("Missing mandatory argument: itemId")
            }

        val navigationDeepLinkViewModel: NavigationDeepLinkViewModel =
            backStackEntry.diAwareViewModel()

        LaunchedEffect(itemId) {
            navigationDeepLinkViewModel.setCurrentArticle(itemId = itemId)
        }

        ArticleScreen(
            onNavigateUp = {
                if (!navController.popBackStack()) {
                    FeedDestination.navigate(navController)
                }
            },
            // Tapping the feed's name opens its page: stats and settings, with a button to its articles
            onNavigateToFeed = { feedId ->
                if (feedId > 0) {
                    EditFeedDestination.navigate(navController, feedId = feedId)
                } else {
                    FeedDestination.navigate(navController, feedId = feedId)
                }
            },
            viewModel = backStackEntry.diAwareViewModel(),
            mainActivityViewModel = mainActivityViewModel,
            onNavigateToArticle = { ArticleDestination.navigate(navController, it) },
            onSwipeToArticle = { id, direction -> swipeTo(navController, id, direction) },
        )
    }
}

data object SyncScreenDestination : NavigationDestination(
    path = "sync",
    navArguments =
        listOf(
            QueryParamArgument("syncCode") {
                type = NavType.StringType
                defaultValue = ""
            },
            QueryParamArgument("secretKey") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    deepLinks =
        listOf(
            navDeepLink {
                uriPattern = "$DEEP_LINK_BASE_URI/sync/join?sync_code={syncCode}&key={secretKey}"
            },
        ),
) {
    fun navigate(
        navController: NavController,
        syncCode: String,
        secretKey: String,
    ) {
        val params =
            queryParams {
                if (syncCode.isNotBlank()) {
                    +("syncCode" to syncCode)
                }
                if (secretKey.isNotBlank()) {
                    +("secretKey" to secretKey)
                }
            }

        navController.navigate("$path$params") {
            launchSingleTop = true
        }
    }

    @Composable
    override fun RegisterScreen(
        navController: NavController,
        backStackEntry: NavBackStackEntry,
        navDrawerListState: LazyListState,
        mainActivityViewModel: MainActivityViewModel,
    ) {
        val syncRemoteViewModel = backStackEntry.diAwareViewModel<SyncScreenViewModel>()

        SyncScreen(
            onNavigateUp = {
                if (!navController.popBackStack()) {
                    SettingsDestination.navigate(navController)
                }
            },
            viewModel = syncRemoteViewModel,
        )
    }
}

fun queryParams(block: QueryParamsBuilder.() -> Unit): String = QueryParamsBuilder().apply { block() }.toString()

class QueryParamsBuilder {
    private val sb = StringBuilder()

    operator fun Pair<String, String?>.unaryPlus() {
        appendIfNotEmpty(first, second)
    }

    private fun appendIfNotEmpty(
        name: String,
        value: String?,
    ) {
        if (value?.isNotEmpty() != true) {
            return
        }

        when {
            sb.isEmpty() -> sb.append("?")
            else -> sb.append("&")
        }

        sb.append("$name=${Uri.encode(value)}")
    }

    override fun toString(): String = sb.toString()
}
