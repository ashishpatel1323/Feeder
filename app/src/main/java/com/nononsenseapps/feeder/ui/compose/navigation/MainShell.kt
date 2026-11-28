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
    val barTabs by BottomBarTabs.tabs(context).collectAsState()
    val bar = barTabs ?: BottomBarTabs.DEFAULT
    val tab = entry?.mainTab(bar)
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
    // A YouTube video collapsed from its page (or still playing from the reader) shows above every tab
    val youtube: com.nononsenseapps.feeder.model.youtube.YouTubePlayback by di.instance()
    val youtubeState by youtube.state.collectAsState()
    val showYouTube = showBar && youtubeState != null

    // One player at a time (YouTube Music style): whatever starts last closes the others, so a
    // YouTube video and a podcast never stack two mini players
    val switcher = remember { PlayerSwitcher() }
    LaunchedEffect(playerState.audioUrl.takeIf { playerState.isVisible }, youtubeState?.key, audiobookState.bookId) {
        switcher.onChange(
            podcast = playerState.audioUrl.takeIf { playerState.isVisible },
            youtube = youtubeState?.key,
            book = audiobookState.bookId,
            stopPodcast = player::stop,
            stopYouTube = youtube::stop,
            closeBook = audiobook::close,
        )
    }

    // The Feeds tab opens on the view used last; Home is the start screen, so switch once to Latest
    LaunchedEffect(navController) {
        val atStart =
            navController.currentBackStack.value.count { it.destination.route != null } == 1 &&
                navController.currentDestination?.route == HomeDestination.route
        when {
            // Redesign: the app opens on the bar's first tab, Home's shelves
            atStart && bar.firstOrNull() == MainTab.HOME -> navController.openTab(context, MainTab.HOME, null)
            atStart && FeedsViewPreference.isLatest(context) -> navController.showFeedsView(context, FeedsView.LATEST)
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
                        if (showYouTube) {
                            com.nononsenseapps.feeder.ui.compose.youtube.YouTubeMiniPlayer(
                                onOpen = { YouTubeVideoDestination.navigate(navController, if (it.articleKey.isNotEmpty()) it.itemId else 0L, it.videoId) },
                            )
                        }
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
                                onOpen = { if (audiobookState.media) MediaPlayerDestination.navigate(navController, it) else AudiobookPlayerDestination.navigate(navController, it) },
                            )
                        }
                        MainBottomBar(current = tab, tabs = bar, onSelect = { navController.openTab(context, it, tab) })
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

/** Remembers which players were open, to tell which one just started. */
private class PlayerSwitcher {
    private var started = false
    private var podcast: Any? = null
    private var youtube: Any? = null
    private var book: Any? = null

    fun onChange(
        podcast: Any?,
        youtube: Any?,
        book: Any?,
        stopPodcast: () -> Unit,
        stopYouTube: () -> Unit,
        closeBook: () -> Unit,
    ) {
        val newPodcast = podcast != null && podcast != this.podcast
        val newYouTube = youtube != null && youtube != this.youtube
        val newBook = book != null && book != this.book
        val first = !started
        started = true
        this.podcast = podcast
        this.youtube = youtube
        this.book = book
        // Players already open when the app comes up stay as they are
        if (first) return
        when {
            newPodcast -> {
                if (youtube != null) stopYouTube()
                if (book != null) closeBook()
            }
            newYouTube -> {
                if (podcast != null) stopPodcast()
                if (book != null) closeBook()
            }
            newBook -> {
                if (podcast != null) stopPodcast()
                if (youtube != null) stopYouTube()
            }
        }
    }
}
