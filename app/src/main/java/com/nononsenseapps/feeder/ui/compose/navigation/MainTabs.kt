package com.nononsenseapps.feeder.ui.compose.navigation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bottom bar's tabs. Search is not a tab: every tab's top bar is a "Search everything" bar
 * that opens global search.
 */
enum class MainTab {
    /** Home shelves (redesign): the first tab */
    HOME,
    FEEDS,
    YOUTUBE,
    PLAYLISTS,
    LIBRARY,

    /** Ebooks, Audiobooks and ZIM, as subtabs of one destination */
    BOOKS,
    HISTORY,

    /** Search everything (and browse), as a tab */
    SEARCH,
}

/** Bar tab names saved before Books existed: each of them is Books now. */
private val OLD_BOOK_TABS = setOf("EBOOKS", "AUDIOBOOKS", "ZIM")

/**
 * Which tabs the bottom bar shows, in order (Settings > Bottom bar tabs). Every tab is optional,
 * but at least one is picked. Without Library, the bar ends with a More tab (Library's screen),
 * which lists the tabs left out, Settings and Sync, so every screen stays reachable.
 */
object BottomBarTabs {
    private const val PREFS = "main_tabs"
    // v2: the redesign starts everyone on Home, Feeds, Library, Search once (Settings > Bottom bar tabs changes it)
    private const val KEY = "bar_tabs_v2"

    /** At most this many items in the bar, counting More. */
    const val MAX = 5
    val DEFAULT = listOf(MainTab.HOME, MainTab.FEEDS, MainTab.LIBRARY, MainTab.SEARCH)

    private val _tabs = MutableStateFlow<List<MainTab>?>(null)

    fun tabs(context: Context): StateFlow<List<MainTab>?> {
        if (_tabs.value == null) {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            _tabs.value = parse(saved)
        }
        return _tabs.asStateFlow()
    }

    fun set(
        context: Context,
        tabs: List<MainTab>,
    ) {
        val clean = normalize(tabs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, clean.joinToString(",") { it.name }) }
        _tabs.value = clean
    }

    /** Saved order, without unknown names, at most [MAX] items in the bar. */
    fun parse(saved: String?): List<MainTab> =
        if (saved.isNullOrBlank()) {
            DEFAULT
        } else {
            normalize(
                saved.split(',').mapNotNull { raw ->
                    val name = raw.trim()
                    if (name in OLD_BOOK_TABS) MainTab.BOOKS else MainTab.entries.firstOrNull { it.name == name }
                },
            )
        }

    fun normalize(tabs: List<MainTab>): List<MainTab> {
        val distinct = tabs.distinct()
        if (distinct.isEmpty()) return DEFAULT
        return distinct.take(limit(distinct))
    }

    /** How many tabs can be picked: one less without Library, to leave room for More. */
    fun limit(picked: Collection<MainTab>): Int = if (MainTab.LIBRARY in picked) MAX else MAX - 1

    /** What the bar shows for [picked]: those tabs, then More (Library's screen) when Library isn't one of them. */
    fun shown(picked: List<MainTab>): List<MainTab> = if (MainTab.LIBRARY in picked) picked else picked + MainTab.LIBRARY
}

/** The Feeds tab's views: Latest, By feed (the tiles) and Discover. */
enum class FeedsView { LATEST, BY_FEED, DISCOVER }

/**
 * Which tab a screen belongs to, or null for screens that show no bottom bar (reader, settings,
 * search...). A tab's screens belong to it when it is in [bar], else to Library (More).
 */
fun NavBackStackEntry.mainTab(bar: List<MainTab> = BottomBarTabs.DEFAULT): MainTab? = mainTabOf(destination.route, arguments?.getLong("id"), bar)

/** [mainTab] for a route ([feedId] is the feed page's id argument). */
fun mainTabOf(
    route: String?,
    feedId: Long?,
    bar: List<MainTab>,
): MainTab? {
    fun own(tab: MainTab) = if (tab in bar) tab else MainTab.LIBRARY
    return when (route) {
        HomeDestination.route -> own(MainTab.FEEDS)
        FeedDestination.route ->
            if (feedId == ID_SAVED_ARTICLES) MainTab.LIBRARY else own(MainTab.FEEDS)
        DiscoverDestination.route -> own(MainTab.FEEDS)
        PlaylistsDestination.route -> own(MainTab.PLAYLISTS)
        LibraryDestination.route, DownloadsDestination.route, HighlightsDestination.route -> MainTab.LIBRARY
        YouTubeDestination.route -> own(MainTab.YOUTUBE)
        BooksDestination.route, MediaDestination.route -> own(MainTab.BOOKS)
        HistoryDestination.route -> own(MainTab.HISTORY)
        ShelvesDestination.route -> own(MainTab.HOME)
        // Search is a full screen of its own unless it's a tab
        GlobalSearchDestination.route -> if (MainTab.SEARCH in bar) MainTab.SEARCH else null
        else -> null
    }
}

/** Screens that show no mini player of their own, so the bottom bar shows it above the tabs. */
fun NavBackStackEntry.wantsSharedMiniPlayer(): Boolean =
    when (destination.route) {
        HomeDestination.route,
        DiscoverDestination.route,
        LibraryDestination.route,
        DownloadsDestination.route,
        HighlightsDestination.route,
        MediaDestination.route,
        YouTubeDestination.route,
        BooksDestination.route,
        HistoryDestination.route,
        ShelvesDestination.route,
        -> true
        else -> false
    }

/** The Feeds tab opens on the view used last: the tile grid (By feed, the default) or Latest. */
object FeedsViewPreference {
    private const val PREFS = "main_tabs"
    private const val KEY_LATEST = "feeds_view_latest"

    fun isLatest(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LATEST, false)

    fun setLatest(
        context: Context,
        latest: Boolean,
    ) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_LATEST, latest) }
}

private val latestRoute = "feed?id=$ID_ALL_FEEDS&tag="

/**
 * Switches the Feeds tab between Latest, By feed and Discover. Home stays under the other two, so
 * Back returns to the tiles. Discover isn't remembered as the tab's start view.
 */
fun NavController.showFeedsView(
    context: Context,
    view: FeedsView,
) {
    if (view == FeedsView.DISCOVER) {
        navigate(DiscoverDestination.route) {
            popUpTo(HomeDestination.route) { saveState = true }
            launchSingleTop = true
        }
        return
    }
    val latest = view == FeedsView.LATEST
    FeedsViewPreference.setLatest(context, latest)
    if (latest) {
        navigate(latestRoute) {
            popUpTo(HomeDestination.route) { saveState = true }
            launchSingleTop = true
        }
    } else if (!popBackStack(HomeDestination.route, inclusive = false, saveState = true)) {
        HomeDestination.navigate(this)
    }
}

/**
 * Opens [tab]. Playlists and Library keep where they were (scroll, open playlist);
 * the Feeds tab and tapping the current tab go back to that tab's first screen.
 */
fun NavController.openTab(
    context: Context,
    tab: MainTab,
    current: MainTab?,
) {
    if (tab == MainTab.FEEDS) {
        showFeedsView(context, if (FeedsViewPreference.isLatest(context)) FeedsView.LATEST else FeedsView.BY_FEED)
        return
    }
    val root =
        when (tab) {
            MainTab.YOUTUBE -> YouTubeDestination.route
            MainTab.PLAYLISTS -> "playlists"
            MainTab.LIBRARY -> LibraryDestination.route
            MainTab.BOOKS -> BooksDestination.ROOT
            MainTab.HISTORY -> HistoryDestination.route
            MainTab.FEEDS -> HomeDestination.route
            MainTab.HOME -> ShelvesDestination.route
            MainTab.SEARCH -> GlobalSearchDestination.route
        }
    if (tab == current) {
        val rootRoute =
            when (tab) {
                MainTab.PLAYLISTS -> PlaylistsDestination.route
                MainTab.BOOKS -> BooksDestination.route
                else -> root
            }
        // A left-out tab's screens belong to More: its root may have left the stack (Feeds pops to Home)
        if (popBackStack(rootRoute, inclusive = false)) return
    }
    navigate(root) {
        popUpTo(HomeDestination.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MainBottomBar(
    current: MainTab?,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<MainTab> = BottomBarTabs.DEFAULT,
) {
    NavigationBar(modifier) {
        BottomBarTabs.shown(tabs).forEach { tab ->
            val selected = tab == current
            val (filled, outlined, label) = if (tab == MainTab.LIBRARY && tab !in tabs) moreLook() else tab.look()
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(if (selected) filled else outlined, contentDescription = null) },
                // A long label shrinks a little instead of wrapping
                label = {
                    Text(
                        stringResource(label),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                    )
                },
            )
        }
    }
}

/** Library's screen, when Library itself isn't picked: it holds the tabs left out and Settings. */
private fun moreLook(): Triple<ImageVector, ImageVector, Int> = Triple(Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz, R.string.tab_more)

internal fun MainTab.look(): Triple<ImageVector, ImageVector, Int> =
    when (this) {
        MainTab.HOME -> Triple(Icons.Filled.Home, Icons.Outlined.Home, R.string.tab_home)
        MainTab.SEARCH -> Triple(Icons.Filled.Search, Icons.Outlined.Search, R.string.tab_search)
        MainTab.FEEDS -> Triple(Icons.Filled.DynamicFeed, Icons.Outlined.DynamicFeed, R.string.tab_feeds)
        MainTab.PLAYLISTS -> Triple(Icons.AutoMirrored.Filled.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic, R.string.playlists)
        MainTab.YOUTUBE -> Triple(Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions, R.string.tab_youtube)
        MainTab.LIBRARY -> Triple(Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, R.string.tab_library)
        MainTab.BOOKS -> Triple(Icons.Filled.AutoStories, Icons.Outlined.AutoStories, R.string.tab_books)
        MainTab.HISTORY -> Triple(Icons.Filled.History, Icons.Outlined.History, R.string.tab_history)
    }

/** What the tab screens need from the app shell, so they don't each take these as parameters. */
@Stable
class MainNav(
    val openSearch: () -> Unit,
    val showFeedsView: (view: FeedsView) -> Unit,
) {
    /** Screens that cover everything (Flip, car mode) hide the bottom bar while they are shown. */
    var hiders by mutableIntStateOf(0)
}

val LocalMainNav = staticCompositionLocalOf { MainNav(openSearch = {}, showFeedsView = {}) }

/** Hides the bottom bar while this is in the composition. */
@Composable
fun HideMainBar() {
    val nav = LocalMainNav.current
    DisposableEffect(nav) {
        nav.hiders++
        onDispose { nav.hiders-- }
    }
}

/**
 * The top bar of every tab: one tap opens global search. [leading] replaces the search icon
 * (the Latest list puts its drawer button there); [actions] go at the end.
 */
@Composable
fun GlobalSearchTopBar(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalMainNav.current
    Surface(
        onClick = nav.openSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
            if (leading != null) {
                leading()
            } else {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                stringResource(R.string.search_everything),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** Latest | By feed | Discover, under the Feeds tab's search bar (Discover has it on top). */
@Composable
fun FeedsViewSwitch(
    view: FeedsView,
    modifier: Modifier = Modifier,
) {
    val nav = LocalMainNav.current
    val options =
        listOf(
            Triple(FeedsView.LATEST, Icons.Default.ViewAgenda, R.string.feeds_view_latest),
            Triple(FeedsView.BY_FEED, Icons.Default.GridView, R.string.feeds_view_by_feed),
            Triple(FeedsView.DISCOVER, Icons.Default.Explore, R.string.discover),
        )
    Column(modifier.fillMaxWidth().padding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp))) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { k, (v, icon, label) ->
                SegmentedButton(
                    selected = view == v,
                    onClick = { if (view != v) nav.showFeedsView(v) },
                    shape = SegmentedButtonDefaults.itemShape(k, options.size),
                    icon = { SegmentedButtonDefaults.Icon(view == v) { Icon(icon, null, Modifier.size(18.dp)) } },
                    // Six tabs share the width: a long label (Audiobooks) shrinks a little instead of wrapping
                    label = {
                        Text(
                            stringResource(label),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                        )
                    },
                )
            }
        }
    }
}
