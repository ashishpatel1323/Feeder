package com.nononsenseapps.feeder.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.background.runOnceSyncChainGetUpdates
import com.nononsenseapps.feeder.background.schedulePeriodicOrphanedFilesCleanup
import com.nononsenseapps.feeder.base.DIAwareComponentActivity
import com.nononsenseapps.feeder.model.PlaybackStatus
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.notifications.NotificationsWorker
import com.nononsenseapps.feeder.ui.compose.navigation.AddFeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleDestination
import com.nononsenseapps.feeder.ui.compose.navigation.DiscoverDestination
import com.nononsenseapps.feeder.ui.compose.navigation.DownloadsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.EditFeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.FeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.GlobalSearchDestination
import com.nononsenseapps.feeder.ui.compose.navigation.HighlightsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.HomeDestination
import com.nononsenseapps.feeder.ui.compose.navigation.LibraryDestination
import com.nononsenseapps.feeder.ui.compose.navigation.MainShell
import com.nononsenseapps.feeder.ui.compose.navigation.ManageFeedsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.PlaylistsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.SearchFeedDestination
import com.nononsenseapps.feeder.ui.compose.navigation.SettingsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.SyncScreenDestination
import com.nononsenseapps.feeder.ui.compose.navigation.TextSettingsDestination
import com.nononsenseapps.feeder.ui.compose.navigation.ZimReaderDestination
import com.nononsenseapps.feeder.ui.compose.playlists.AddToPlaylistHost
import com.nononsenseapps.feeder.ui.compose.utils.withAllProviders
import com.nononsenseapps.feeder.util.isLanLocalAddress
import com.nononsenseapps.feeder.util.resolvesToLanLocalAddress
import kotlinx.coroutines.launch
import org.kodein.di.instance

class MainActivity : DIAwareComponentActivity() {
    private val notificationsWorker: NotificationsWorker by instance()
    private val mainActivityViewModel: MainActivityViewModel by instance(arg = this)
    private val repository: Repository by instance()
    private val preferences: SharedPreferences by instance()
    private val ttsStateHolder: TTSStateHolder by instance()
    private var askedForReadAloudNotificationPermission = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var localNetworkMigrationStarted = false
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                runOnceRssSync(
                    di = di,
                    forceNetwork = false,
                    triggeredByUser = false,
                )
            }
        }

    override fun onStart() {
        super.onStart()
        notificationsWorker.runForever()
    }

    override fun onStop() {
        notificationsWorker.stopForever()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        mainActivityViewModel.setResumeTime()
        maybeRequestSync()
        maybeRequestLocalNetworkPermissionForExistingFeeds()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        if (mainActivityViewModel.isPagingMode.value && repository.isArticleOpen.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    mainActivityViewModel.emitScrollCommand(ScrollDirection.UP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    mainActivityViewModel.emitScrollCommand(ScrollDirection.DOWN)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun maybeRequestSync() =
        lifecycleScope.launch {
            if (!mainActivityViewModel.isOkToSyncAutomatically()) return@launch
            // Always pick up what other devices did meanwhile (where an episode was left, playlist
            // edits, read marks): one quick exchange, so switching devices carries on from there
            runOnceSyncChainGetUpdates(di)
            if (mainActivityViewModel.shouldSyncOnResume) {
                runOnceRssSync(
                    di = di,
                    forceNetwork = false,
                    triggeredByUser = false,
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mainActivityViewModel.ensurePeriodicSyncConfigured()

        // Configure daily cleanup of orphaned article files
        schedulePeriodicOrphanedFilesCleanup(di)

        enableEdgeToEdge()

        // Some phones (OxygenOS) hide the read-aloud media notification without notification
        // permission, and with it the lock-screen and control-center play/pause. Ask when reading
        // starts, from whichever screen it was started.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ttsStateHolder.ttsState.collect { state ->
                    if (state == PlaybackStatus.PLAYING) maybeRequestNotificationPermissionForReadAloud()
                }
            }
        }

        setContent {
            withAllProviders {
                AppContent()
            }
        }
    }

    private fun maybeRequestNotificationPermissionForReadAloud() {
        if (askedForReadAloudNotificationPermission ||
            Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Once per activity; Android itself stops showing the prompt after the user denies it twice.
        askedForReadAloudNotificationPermission = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeRequestLocalNetworkPermissionForExistingFeeds() {
        if (localNetworkMigrationStarted ||
            Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED ||
            preferences.getBoolean(KEY_LOCAL_NETWORK_PERMISSION_REQUESTED, false)
        ) {
            return
        }
        localNetworkMigrationStarted = true
        lifecycleScope.launch {
            if (preferences.getBoolean(KEY_LOCAL_NETWORK_PERMISSION_REQUESTED, false)) {
                return@launch
            }
            val feedUrls = repository.getFeedsOrderedByUrl().map { it.url }
            // Literal LAN hosts first, so the DNS lookups only run when none of those match
            val hasLocalFeed =
                feedUrls.any { it.isLanLocalAddress() } ||
                    feedUrls.distinctBy { it.host }.any { it.resolvesToLanLocalAddress() }
            if (!hasLocalFeed) {
                preferences.edit { putBoolean(KEY_LOCAL_NETWORK_PERMISSION_REQUESTED, true) }
                return@launch
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                localNetworkMigrationStarted = false
                return@launch
            }
            // Persist before launching so denial and activity recreation do not prompt again.
            preferences.edit { putBoolean(KEY_LOCAL_NETWORK_PERMISSION_REQUESTED, true) }
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    @Composable
    fun AppContent() {
        val navController = rememberNavController()
        val navDrawerListState = rememberLazyListState()

        MainShell(navController) {
            NavHost(navController, startDestination = HomeDestination.route) {
                HomeDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                DiscoverDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                FeedDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                ArticleDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                // Feed editing
                EditFeedDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                SearchFeedDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                GlobalSearchDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                AddFeedDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                // Settings
                SettingsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                // Sync settings
                SyncScreenDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                // Add Fonts
                TextSettingsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                DownloadsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                ManageFeedsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                PlaylistsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                HighlightsDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                LibraryDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.BooksDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.YouTubeDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.YouTubeManageDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.YouTubeVideoDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.YouTubeShortsDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.BottomBarTabsDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.EbookReaderDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.MediaDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.MediaPlayerDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.AudiobookPlayerDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                ZimReaderDestination.register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.HistoryDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
                com.nononsenseapps.feeder.ui.compose.navigation.ShelvesDestination
                    .register(this, navController, navDrawerListState, mainActivityViewModel)
            }
        }
        // "Playlist" on any article, episode or video opens this sheet
        AddToPlaylistHost()

        DisposableEffect(navController) {
            val listener =
                Consumer<Intent> { intent ->
                    if (!navController.handleDeepLink(intent)) {
                        Log.e(LOG_TAG, "NavController rejected intent: $intent")
                    }
                }
            addOnNewIntentListener(listener)
            onDispose { removeOnNewIntentListener(listener) }
        }
    }

    companion object {
        private const val LOG_TAG = "FEEDER_MAIN"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_LOCAL_NETWORK_PERMISSION_REQUESTED = "local_network_permission_requested"
    }
}
