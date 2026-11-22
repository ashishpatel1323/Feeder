package com.nononsenseapps.feeder.model.offline

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.MainActivity
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Background play for the Downloads playlist: a media session around [DownloadsPlayback]'s
 * player, so it keeps going with the screen off or in other apps, with notification and
 * lock-screen controls (play/pause, next, previous). Tapping the notification opens Downloads.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadsPlaybackService : MediaSessionService() {
    private val playback: DownloadsPlayback by lazy { (application as FeederApplication).di.direct.instance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.downloads_background_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_f) },
        )
        scope.launch {
            combine(playback.player, playback.background) { player, background -> player.takeIf { background } }
                .distinctUntilChanged { a, b -> a === b }
                .collect { player ->
                    session?.let {
                        removeSession(it)
                        it.release()
                    }
                    session = null
                    if (player == null) {
                        stopSelf()
                        return@collect
                    }
                    session =
                        MediaSession
                            .Builder(this@DownloadsPlaybackService, player)
                            // Read aloud and YouTube have their own sessions: ids must differ
                            .setId(SESSION_ID)
                            .setSessionActivity(downloadsIntent())
                            .build()
                            .also { addSession(it) }
                }
        }
    }

    private fun downloadsIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "$DEEP_LINK_BASE_URI/downloads".toUri()
            }
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends it; while playing it keeps going
        if (session?.player?.playWhenReady != true) playback.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        session?.let {
            removeSession(it)
            // The player is DownloadsPlayback's: only the session goes
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "feeder-downloads"
        private const val CHANNEL_ID = "downloads-playback"
        private const val NOTIFICATION_ID = 2403
    }
}
