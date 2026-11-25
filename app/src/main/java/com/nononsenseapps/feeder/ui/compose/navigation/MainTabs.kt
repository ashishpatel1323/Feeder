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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
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
import androidx.core.content.edit
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES

/**
 * The bottom bar's tabs. Search is not a tab: every tab's top bar is a "Search everything" bar
 * that opens global search.
 */
enum class MainTab {
    FEEDS,
    DISCOVER,
    PLAYLISTS,
    LIBRARY,
    ZIM,
}

/** Which tab a screen belongs to, or null for screens that show no bottom bar (reader, settings, search...). */
fun NavBackStackEntry.mainTab(): MainTab? =
    when (destination.route) {
        HomeDestination.route -> MainTab.FEEDS
        FeedDestination.route ->
            if (arguments?.getLong("id") == ID_SAVED_ARTICLES) MainTab.LIBRARY else MainTab.FEEDS
        DiscoverDestination.route -> MainTab.DISCOVER
        PlaylistsDestination.route -> MainTab.PLAYLISTS
        LibraryDestination.route, DownloadsDestination.route, HighlightsDestination.route -> MainTab.LIBRARY
        ZimDestination.route -> MainTab.ZIM
        else -> null
    }

/** Screens that show no mini player of their own, so the bottom bar shows it above the tabs. */
fun NavBackStackEntry.wantsSharedMiniPlayer(): Boolean =
    when (destination.route) {
        HomeDestination.route,
        DiscoverDestination.route,
        LibraryDestination.route,
        DownloadsDestination.route,
        HighlightsDestination.route,
        ZimDestination.route,
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

/** Switches the Feeds tab between Latest and By feed. Home stays under Latest, so Back returns to the tiles. */
fun NavController.showFeedsView(
    context: Context,
    latest: Boolean,
) {
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
 * Opens [tab]. Discover, Playlists and Library keep where they were (scroll, open playlist);
 * the Feeds tab and tapping the current tab go back to that tab's first screen.
 */
fun NavController.openTab(
    context: Context,
    tab: MainTab,
    current: MainTab?,
) {
    if (tab == MainTab.FEEDS) {
        showFeedsView(context, FeedsViewPreference.isLatest(context))
        return
    }
    val root =
        when (tab) {
            MainTab.DISCOVER -> DiscoverDestination.route
            MainTab.PLAYLISTS -> "playlists"
            MainTab.LIBRARY -> LibraryDestination.route
            MainTab.ZIM -> ZimDestination.route
            MainTab.FEEDS -> HomeDestination.route
        }
    if (tab == current) {
        val rootRoute = if (tab == MainTab.PLAYLISTS) PlaylistsDestination.route else root
        popBackStack(rootRoute, inclusive = false)
        return
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
) {
    NavigationBar(modifier) {
        MainTab.entries.forEach { tab ->
            val selected = tab == current
            val (filled, outlined, label) = tab.look()
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(if (selected) filled else outlined, contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

private fun MainTab.look(): Triple<ImageVector, ImageVector, Int> =
    when (this) {
        MainTab.FEEDS -> Triple(Icons.Filled.DynamicFeed, Icons.Outlined.DynamicFeed, R.string.tab_feeds)
        MainTab.DISCOVER -> Triple(Icons.Filled.Explore, Icons.Outlined.Explore, R.string.discover)
        MainTab.PLAYLISTS -> Triple(Icons.AutoMirrored.Filled.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic, R.string.playlists)
        MainTab.LIBRARY -> Triple(Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, R.string.tab_library)
        MainTab.ZIM -> Triple(Icons.Filled.Public, Icons.Outlined.Public, R.string.tab_zim)
    }

/** What the tab screens need from the app shell, so they don't each take these as parameters. */
@Stable
class MainNav(
    val openSearch: () -> Unit,
    val showFeedsView: (latest: Boolean) -> Unit,
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

/** Latest | By feed, under the Feeds tab's search bar. */
@Composable
fun FeedsViewSwitch(
    latest: Boolean,
    modifier: Modifier = Modifier,
) {
    val nav = LocalMainNav.current
    Column(modifier.fillMaxWidth().padding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp))) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = latest,
                onClick = { if (!latest) nav.showFeedsView(true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { SegmentedButtonDefaults.Icon(latest) { Icon(Icons.Default.ViewAgenda, null, Modifier.size(18.dp)) } },
                label = { Text(stringResource(R.string.feeds_view_latest)) },
            )
            SegmentedButton(
                selected = !latest,
                onClick = { if (latest) nav.showFeedsView(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { SegmentedButtonDefaults.Icon(!latest) { Icon(Icons.Default.GridView, null, Modifier.size(18.dp)) } },
                label = { Text(stringResource(R.string.feeds_view_by_feed)) },
            )
        }
    }
}
