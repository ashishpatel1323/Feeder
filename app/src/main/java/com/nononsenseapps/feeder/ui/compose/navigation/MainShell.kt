package com.nononsenseapps.feeder.ui.compose.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.model.PodcastPlayerStateHolder
import com.nononsenseapps.feeder.ui.compose.feedarticle.PodcastPlayerBar
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * The app shell: the screens, with the bottom bar (Feeds, Discover, Playlists, Library) under the
 * tab screens. The reader, search, settings and full-screen modes have no bar. Screens that have no
 * player of their own get the podcast mini player just above the bar.
 */
@Composable
fun MainShell(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val entry by navController.currentBackStackEntryAsState()
    val tab = entry?.mainTab()
    val nav =
        remember(navController) {
            MainNav(
                openSearch = { GlobalSearchDestination.navigate(navController) },
                showFeedsView = { view -> navController.showFeedsView(context, view) },
            )
        }
    val showBar = tab != null && nav.hiders == 0

    val di = LocalDI.current
    val player: PodcastPlayerStateHolder by di.instance()
    val playerState by player.playerState.collectAsState()
    val showPlayer = showBar && playerState.isVisible && entry?.wantsSharedMiniPlayer() == true
    val audiobook: com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer by di.instance()
    val audiobookState by audiobook.state.collectAsState()
    val showAudiobook = showBar && !showPlayer && audiobookState.active && entry?.wantsSharedMiniPlayer() == true

    // The Feeds tab opens on the view used last; Home is the start screen, so switch once to Latest
    LaunchedEffect(navController) {
        if (FeedsViewPreference.isLatest(context) &&
            navController.currentBackStack.value.count { it.destination.route != null } == 1 &&
            navController.currentDestination?.route == HomeDestination.route
        ) {
            navController.showFeedsView(context, FeedsView.LATEST)
        }
    }

    // Remember the view in use, also when Back leaves Latest for the tiles
    LaunchedEffect(entry) {
        val e = entry ?: return@LaunchedEffect
        when {
            e.destination.route == HomeDestination.route -> FeedsViewPreference.setLatest(context, false)
            e.destination.route == FeedDestination.route && e.arguments?.getLong("id") == ID_ALL_FEEDS ->
                FeedsViewPreference.setLatest(context, true)
        }
    }

    CompositionLocalProvider(LocalMainNav provides nav) {
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBar) {
                    Column {
                        if (showPlayer) {
                            PodcastPlayerBar(
                                viewState = playerState,
                                onPlay = player::play,
                                onPause = player::pause,
                                onStop = player::stop,
                                onSeekBack = { player.seekBy(-10_000) },
                                onSeekForward = { player.seekBy(30_000) },
                                onSeekTo = player::seekTo,
                            )
                        }
                        if (showAudiobook) {
                            com.nononsenseapps.feeder.ui.compose.audiobooks.AudiobookMiniBar(
                                onOpen = { AudiobookPlayerDestination.navigate(navController, it) },
                            )
                        }
                        MainBottomBar(current = tab, onSelect = { navController.openTab(context, it, tab) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                content()
            }
        }
    }
}
