package com.nononsenseapps.feeder.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.MainActivity
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import org.kodein.di.direct
import org.kodein.di.instance

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    private var session: MediaSession? = null
    private val holder: TTSStateHolder by lazy { (application as FeederApplication).di.direct.instance() }

    // Previous / next article for the media controls, and auto-continue when an article ends
    private lateinit var queue: ReadAloudQueue

    override fun onCreate() {
        super.onCreate()
        queue = (application as FeederApplication).di.direct.instance()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.read_article)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_f) },
        )
        val player =
            ExoPlayer
                .Builder(this)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2000, 15000, 250, 500).build())
                .build()
                .apply {
                    setSeekBackIncrementMs(10_000)
                    setSeekForwardIncrementMs(10_000)
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                            .build(),
                        true,
                    )
                    setHandleAudioBecomingNoisy(true)
                    setWakeMode(C.WAKE_MODE_LOCAL)
                }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sessionPlayer =
            object : ForwardingPlayer(player) {
                private fun bounded(positionMs: Long): Long = positionMs.coerceIn(0, holder.progress.value.generatedMillis)

                override fun seekTo(positionMs: Long) = player.seekTo(bounded(positionMs))

                override fun seekTo(
                    mediaItemIndex: Int,
                    positionMs: Long,
                ) = player.seekTo(mediaItemIndex, bounded(positionMs))

                override fun seekBack() = seekTo(player.currentPosition - 10_000)

                override fun seekForward() = seekTo(player.currentPosition + 10_000)

                // Previous / next move to the neighbouring article in the list
                override fun seekToNext() = queue.skip(next = true)

                override fun seekToNextMediaItem() = queue.skip(next = true)

                override fun seekToPrevious() = queue.skip(next = false)

                override fun seekToPreviousMediaItem() = queue.skip(next = false)

                override fun hasNextMediaItem(): Boolean = true

                override fun hasPreviousMediaItem(): Boolean = true

                override fun isCommandAvailable(command: Int): Boolean = command in ARTICLE_COMMANDS || super.isCommandAvailable(command)

                override fun getAvailableCommands(): Player.Commands = withArticleCommands(super.getAvailableCommands())

                private val wrappedListeners = HashMap<Player.Listener, Player.Listener>()

                override fun addListener(listener: Player.Listener) {
                    val wrapped =
                        object : Player.Listener by listener {
                            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(withArticleCommands(availableCommands))
                        }
                    wrappedListeners[listener] = wrapped
                    super.addListener(wrapped)
                }

                override fun removeListener(listener: Player.Listener) {
                    super.removeListener(wrappedListeners.remove(listener) ?: listener)
                }
            }
        session =
            MediaSession
                .Builder(this, sessionPlayer)
                .setSessionActivity(activity)
                .setMediaButtonPreferences(
                    listOf(
                        // The back / forward slots stay free for previous / next article. Custom
                        // commands rather than player commands: the platform session (lock screen,
                        // control center, OEM media panels) only shows custom buttons as extras,
                        // and Android shows the first two of them.
                        CommandButton
                            .Builder(CommandButton.ICON_SKIP_BACK_10)
                            .setSessionCommand(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                            .setDisplayName(getString(R.string.skip_back_10))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton
                            .Builder(CommandButton.ICON_SKIP_FORWARD_10)
                            .setSessionCommand(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                            .setDisplayName(getString(R.string.skip_forward_10))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton
                            .Builder(CommandButton.ICON_STOP)
                            .setSessionCommand(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                            .setDisplayName(getString(R.string.stop_reading))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                    ),
                ).setCallback(
                    object : MediaSession.Callback {
                        override fun onConnectAsync(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): ListenableFuture<MediaSession.ConnectionResult> {
                            // Accept every controller with full commands: lock screen, control center,
                            // headsets, watches and OEM media panels are not all "trusted" system apps.
                            // Build from the defaults explicitly. super.onConnect() returns an EMPTY
                            // placeholder, and the platform session (lock screen, control center)
                            // copies the media notification controller's commands, so starting from
                            // it left Android with no play/pause and no notification.
                            // External controls can operate this article, not replace its local audio.
                            val commands =
                                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                                    .buildUpon()
                                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                                    .build()
                            return Futures.immediateFuture(
                                MediaSession.ConnectionResult
                                    .AcceptedResultBuilder(session, controller)
                                    .setAvailablePlayerCommands(commands)
                                    .setAvailableSessionCommands(
                                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                            .buildUpon()
                                            .add(SessionCommand(ACTION_STOP, Bundle.EMPTY))
                                            .add(SessionCommand(ACTION_SEEK_BACK, Bundle.EMPTY))
                                            .add(SessionCommand(ACTION_SEEK_FORWARD, Bundle.EMPTY))
                                            .build(),
                                    ).build(),
                            )
                        }

                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            customCommand: SessionCommand,
                            args: Bundle,
                        ): ListenableFuture<SessionResult> {
                            when (customCommand.customAction) {
                                ACTION_STOP -> holder.stop()
                                ACTION_SEEK_BACK -> sessionPlayer.seekBack()
                                ACTION_SEEK_FORWARD -> sessionPlayer.seekForward()
                                else -> return super.onCustomCommand(session, controller, customCommand, args)
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    },
                ).build()
        holder.attach(this, player)
        // The Compose UI uses the application facade, not a bound MediaController.
        addSession(checkNotNull(session))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_PREPARE) {
            // Every foreground-service start must be acknowledged, even if Stop or
            // a newer request already consumed this intent's pending preparation.
            showPreparationNotification()
            holder.prepare()
            if (holder.progress.value.articleId != null && holder.progress.value.preparationProgress == null) {
                session?.let { onUpdateNotification(it, it.player.playWhenReady) }
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        notificationUpdates++
        // Clearing the previous playlist must not demote an active synthesis job.
        if (holder.progress.value.preparationProgress != null) {
            lastNotificationUpdate = "preparation"
            showPreparationNotification()
        } else {
            val synthesizing = holder.progress.value.isDurationEstimated && holder.progress.value.error == null
            lastNotificationUpdate = "media, foreground=${startInForegroundRequired || synthesizing}"
            try {
                super.onUpdateNotification(session, startInForegroundRequired || synthesizing)
            } catch (e: RuntimeException) {
                lastNotificationError = e.toString()
                throw e
            }
        }
    }

    private var notificationUpdates = 0
    private var lastNotificationUpdate = "none"
    private var lastNotificationError: String? = null

    /** What Android sees of read aloud, shown in a dialog so problems can be diagnosed from a screenshot. */
    internal fun diagnostics(): String =
        buildString {
            val current = session ?: return "Media session: none"
            val player = current.player
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(
                "Player: state=${playerStateName(player.playbackState)} playWhenReady=${player.playWhenReady} " +
                    "playing=${player.isPlaying} live=${player.isCurrentMediaItemLive} items=${player.mediaItemCount}",
            )
            appendLine(
                "Player commands: playPause=${player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)} " +
                    "seekBack=${player.isCommandAvailable(Player.COMMAND_SEEK_BACK)} " +
                    "currentItem=${player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)}",
            )
            appendLine("Session added to service: ${isSessionAdded(current)}")
            appendLine("Controllers (${current.connectedControllers.size}):")
            for (controller in current.connectedControllers) {
                appendLine(
                    "  ${controller.packageName} v${controller.controllerVersion} trusted=${controller.isTrusted} " +
                        "notification=${current.isMediaNotificationController(controller)}",
                )
            }
            val platform = android.media.session.MediaController(this@ReadAloudService, current.platformToken)
            val state = platform.playbackState
            appendLine(
                "Platform state: ${state?.state} actions=0x${java.lang.Long.toHexString(state?.actions ?: 0)} " +
                    "speed=${state?.playbackSpeed} position=${state?.position}",
            )
            appendLine("Platform custom actions: ${state?.customActions?.map { it.action.substringAfterLast('.') }}")
            appendLine("Platform title: ${platform.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}")
            val manager = getSystemService(NotificationManager::class.java)
            appendLine("Notifications enabled: ${manager.areNotificationsEnabled()}")
            appendLine("Channel importance: ${manager.getNotificationChannel(CHANNEL_ID)?.importance}")
            appendLine("Notification updates: $notificationUpdates, last: $lastNotificationUpdate")
            appendLine("Last notification error: ${lastNotificationError ?: "none"}")
            appendLine("Foreground service type: $foregroundServiceType")
            val active = manager.activeNotifications
            appendLine("Active notifications (${active.size}):")
            for (posted in active) {
                val notification = posted.notification
                appendLine(
                    "  id=${posted.id} channel=${notification.channelId} " +
                        "style=${notification.extras.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')} " +
                        "foreground=${notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0} " +
                        "session=${notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)} " +
                        "actions=${notification.actions?.size ?: 0}",
                )
            }
        }

    private fun playerStateName(state: Int): String =
        when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> state.toString()
        }

    private fun showPreparationNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.read_article), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(this, 1, Intent(this, ReadAloudStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_f)
                .setContentTitle(getString(R.string.read_article))
                .setContentText(getString(R.string.read_aloud_preparing))
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_reading), stop)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    internal fun setArticle(articleId: Long) {
        val intent =
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData("$DEEP_LINK_BASE_URI/article/$articleId".toUri())
        session?.setSessionActivity(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    override fun onDestroy() {
        holder.detach(this)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private val ARTICLE_COMMANDS =
            setOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )

        private fun withArticleCommands(commands: Player.Commands): Player.Commands =
            commands
                .buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                ).build()

        const val ACTION_PREPARE = "com.nononsenseapps.feeder.READ_ALOUD_PREPARE"
        private const val ACTION_STOP = "com.nononsenseapps.feeder.READ_ALOUD_STOP"
        private const val ACTION_SEEK_BACK = "com.nononsenseapps.feeder.READ_ALOUD_SEEK_BACK"
        private const val ACTION_SEEK_FORWARD = "com.nononsenseapps.feeder.READ_ALOUD_SEEK_FORWARD"
        private const val CHANNEL_ID = "read-aloud-preparation"
        private const val NOTIFICATION_ID = 2401
    }
}

/** The notification grants Stop access without exporting an unrestricted service action. */
class ReadAloudStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val holder: TTSStateHolder = (context.applicationContext as FeederApplication).di.direct.instance()
        holder.stop()
    }
}
