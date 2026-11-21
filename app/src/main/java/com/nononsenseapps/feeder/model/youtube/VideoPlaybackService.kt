package com.nononsenseapps.feeder.model.youtube

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_UNSET
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
 * Background play for the YouTube player (its Background button): a media session around
 * [YouTubePlayback]'s player, so sound keeps going with the screen off or in other apps, with
 * notification and lock-screen controls. The player belongs to [YouTubePlayback]; this only
 * shows it to Android.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService() {
    private val playback: YouTubePlayback by lazy { (application as FeederApplication).di.direct.instance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.youtube_background_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_f) },
        )
        scope.launch {
            combine(playback.player, playback.state) { player, state ->
                if (state?.background == true && player != null) player to state.itemId else null
            }.distinctUntilChanged { a, b -> a?.first === b?.first && a?.second == b?.second }
                .collect { current ->
                    session?.let {
                        removeSession(it)
                        it.release()
                    }
                    session = null
                    if (current == null) {
                        stopSelf()
                        return@collect
                    }
                    val (player, itemId) = current
                    session =
                        MediaSession
                            .Builder(this@VideoPlaybackService, player)
                            // Read aloud has its own session: ids must differ
                            .setId(SESSION_ID)
                            .setSessionActivity(articleIntent(itemId))
                            .build()
                            .also { addSession(it) }
                }
        }
    }

    private fun articleIntent(itemId: Long): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                if (itemId > ID_UNSET) {
                    action = Intent.ACTION_VIEW
                    data = "$DEEP_LINK_BASE_URI/article/$itemId".toUri()
                }
            }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            // The player is YouTubePlayback's: only the session goes
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "feeder-youtube"
        private const val CHANNEL_ID = "youtube-background"
        private const val NOTIFICATION_ID = 2402
    }
}
