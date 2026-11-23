package com.nononsenseapps.feeder.model.playlists

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Shows the playlist's audio player to Android: notification, lock screen, Bluetooth and steering
 * wheel buttons. Next and Previous go to [PlaylistPlayer], so they move through the playlist
 * (into articles read aloud, too) rather than within one episode.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaylistPlaybackService : MediaSessionService() {
    private val playlistPlayer: PlaylistPlayer by lazy { (application as FeederApplication).di.direct.instance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playlist_playback_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_f) },
        )
        scope.launch {
            playlistPlayer.player
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
                            .Builder(this@PlaylistPlaybackService, PlaylistForwardingPlayer(player, playlistPlayer))
                            // Read aloud, Downloads and YouTube have their own sessions: ids must differ
                            .setId(SESSION_ID)
                            .setSessionActivity(playlistsIntent())
                            .build()
                            .also { addSession(it) }
                }
        }
    }

    private fun playlistsIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = "$DEEP_LINK_BASE_URI/playlists".toUri()
            }
        return PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends it; while playing it keeps going
        if (session?.player?.playWhenReady != true) playlistPlayer.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        session?.let {
            removeSession(it)
            // The player is PlaylistPlayer's: only the session goes
            it.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "feeder-playlist"
        private const val CHANNEL_ID = "playlist-playback"
        private const val NOTIFICATION_ID = 2404
    }
}

/** Always offers Next and Previous, and sends them to the playlist. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaylistForwardingPlayer(
    player: Player,
    private val playlist: PlaylistPlayer,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super
            .getAvailableCommands()
            .buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ).build()

    override fun isCommandAvailable(command: Int): Boolean =
        when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> true
            else -> super.isCommandAvailable(command)
        }

    override fun hasNextMediaItem(): Boolean = playlist.state.value.hasNext

    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = playlist.next()

    override fun seekToNextMediaItem() = playlist.next()

    override fun seekToPrevious() = playlist.previous()

    override fun seekToPreviousMediaItem() = playlist.previous()
}
