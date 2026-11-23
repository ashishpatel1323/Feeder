package com.nononsenseapps.feeder.model.playlists

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.ui.MainActivity
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.ByteArrayOutputStream

/**
 * Playlists in the car and on the phone's media controls. One media session with one queue
 * ([CarQueuePlayer]) for podcast episodes, saved videos and articles read aloud.
 *
 * Android Auto browses it: Playlists, Podcasts (artwork cards), Up next and Downloads. Picking an
 * item plays it with the rest of its list after it. The notification shows only for audio; read
 * aloud keeps its own notification while an article plays.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaylistPlaybackService : MediaLibraryService() {
    private val di by lazy { (application as FeederApplication).di }
    private val playlistPlayer: PlaylistPlayer by lazy { di.direct.instance() }
    private val store: PlaylistStore by lazy { di.direct.instance() }
    private val dao: FeedItemDao by lazy { di.direct.instance() }
    private val offlineFiles: OfflineFiles by lazy { di.direct.instance() }
    private val offlineVideos: OfflineVideos by lazy { di.direct.instance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null
    private lateinit var carPlayer: CarQueuePlayer

    @Volatile private var candidates: List<PlaylistCandidate> = emptyList()

    @Volatile private var byId: Map<Long, PlaylistCandidate> = emptyMap()

    @Volatile private var downloaded: Set<Long> = emptySet()
    private val tiles = HashMap<String, ByteArray>()

    /** Done once the articles have loaded, so the first browse after connecting isn't empty. */
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    private val ctx: PlaylistContext
        get() = PlaylistContext(positions = store.positions.value, downloaded = downloaded, speed = playlistPlayer.articleSpeed())

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
        carPlayer =
            CarQueuePlayer(
                Looper.getMainLooper(),
                playlistPlayer,
                info = { id ->
                    byId[id]?.let { CarItemInfo(it.title, it.feedTitle, it.image?.url ?: it.feedImage, it.kind) }
                },
                onPick = ::play,
            )
        session =
            MediaLibrarySession
                .Builder(this, carPlayer, LibraryCallback())
                // Read aloud, Downloads and YouTube have their own sessions: ids must differ
                .setId(SESSION_ID)
                .setSessionActivity(playlistsIntent())
                .setMediaButtonPreferences(
                    listOf(
                        CommandButton
                            .Builder(CommandButton.ICON_PLAYBACK_SPEED)
                            .setSessionCommand(SessionCommand(ACTION_SPEED, Bundle.EMPTY))
                            .setDisplayName(getString(R.string.queue_speed))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                    ),
                ).build()
        scope.launch {
            playlistPlayer.state.collect {
                carPlayer.refresh()
                session?.notifyChildrenChanged(UP_NEXT, it.ids.size, null)
            }
        }
        scope.launch {
            combine(
                dao.loadPlaylistCandidates(),
                offlineVideos.changes.map { withContext(Dispatchers.IO) { (offlineFiles.savedAudioIds() + offlineVideos.savedIds()).toSet() } },
            ) { list, down -> list to down }.collect { (list, down) ->
                candidates = list
                byId = list.associateBy { it.id }
                downloaded = down
                carPlayer.refresh()
                ready.complete(Unit)
            }
        }
        scope.launch {
            store.playlists.collect { session?.notifyChildrenChanged(PLAYLISTS, it.size, null) }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // Read aloud has its own notification for articles
        if (playlistPlayer.state.value.kind == ItemKind.ARTICLE) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused ends it; while playing it keeps going
        if (!playlistPlayer.state.value.isPlaying) playlistPlayer.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        carPlayer.release()
        super.onDestroy()
    }

    // ---- Picking something to play ----

    private fun play(mediaId: String) {
        val parts = mediaId.split(':')
        val c = ctx
        when (parts[0]) {
            "q" -> parts.getOrNull(1)?.toIntOrNull()?.let(playlistPlayer::jump)
            "plall", "plshuf", "pli" -> {
                val pl = store.get(parts.getOrNull(1) ?: return) ?: return
                var ids = pl.items(candidates, c, byId).map { it.id }
                if (parts[0] == "plshuf") ids = ids.shuffled()
                val start = parts.getOrNull(2)?.toLongOrNull()?.let { ids.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                playlistPlayer.start(ids, pl.name, pl.id, start)
            }
            "ep" -> {
                val feedId = parts.getOrNull(1)?.toLongOrNull() ?: return
                val items = episodes(feedId)
                val start = parts.getOrNull(2)?.toLongOrNull()?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
                playlistPlayer.start(items.map { it.id }, items.firstOrNull()?.feedTitle ?: "Podcast", null, start)
            }
            "dl" -> {
                val items = downloads()
                val start = parts.getOrNull(1)?.toLongOrNull()?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
                playlistPlayer.start(items.map { it.id }, getString(R.string.downloads), null, start)
            }
        }
    }

    private fun episodes(feedId: Long): List<PlaylistCandidate> = candidates.filter { it.feedId == feedId && it.kind == ItemKind.PODCAST }.sortedByDescending { it.sortTime }.take(MAX_EPISODES)

    private fun downloads(): List<PlaylistCandidate> = candidates.filter { it.kind != ItemKind.ARTICLE && it.id in downloaded }.sortedByDescending { it.sortTime }

    // ---- The browse tree ----

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_SPEED, Bundle.EMPTY))
                        .build(),
                ).build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_SPEED) playlistPlayer.cycleSpeed()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras =
                Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                }
            return Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Feeder", null), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { ready.await() }
                val all = children(parentId)
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size).coerceAtLeast(from)
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(if (pageSize > 0) all.subList(from, to) else all), params))
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId(mediaId).build(), null))

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)
    }

    private fun children(parentId: String): List<MediaItem> {
        val c = ctx
        return when {
            parentId == ROOT ->
                listOf(
                    folder(PLAYLISTS, getString(R.string.playlists), null),
                    folder(PODCASTS, "Podcasts", null, grid = true),
                    folder(UP_NEXT, getString(R.string.queue_up_next), null),
                    folder(DOWNLOADS, getString(R.string.downloads), null),
                )
            parentId == PLAYLISTS ->
                store.playlists.value.map { pl ->
                    val items = pl.items(candidates, c, byId)
                    val total = items.totalSeconds(c).first
                    folder(
                        "pl:${pl.id}",
                        pl.name,
                        "${if (pl.smart) "Smart" else "My playlist"} · ${items.size} items${if (total > 0) " · ${formatTotal(total)}" else ""}",
                        artworkData = tile(pl),
                    )
                }
            parentId.startsWith("pl:") -> {
                val pl = store.get(parentId.removePrefix("pl:")) ?: return emptyList()
                val items = pl.items(candidates, c, byId)
                listOf(
                    playable("plall:${pl.id}", "▶  ${getString(R.string.playlist_play_all)}", "${items.size} items"),
                    playable("plshuf:${pl.id}", "⤮  ${getString(R.string.playlist_shuffle)}", null),
                ) + items.map { itemEntry("pli:${pl.id}:${it.id}", it, c) }
            }
            parentId == PODCASTS ->
                candidates
                    .filter { it.kind == ItemKind.PODCAST }
                    .groupBy { it.feedId }
                    .map { (feedId, eps) ->
                        val unplayed = eps.count { it.status(c) == PlayStatus.UNREAD }
                        val inProgress = eps.any { it.status(c) == PlayStatus.IN_PROGRESS }
                        Triple(feedId, eps, if (inProgress) "In progress" else if (unplayed > 99) "99+ new" else if (unplayed > 0) "$unplayed new" else null)
                    }.sortedByDescending { (_, eps, _) -> eps.maxOf { it.sortTime } }
                    .map { (feedId, eps, badge) -> folder("feed:$feedId", eps.first().feedTitle, badge, artworkUri = eps.first().feedImage) }
            parentId.startsWith("feed:") -> {
                val feedId = parentId.removePrefix("feed:").toLongOrNull() ?: return emptyList()
                episodes(feedId).map { itemEntry("ep:$feedId:${it.id}", it, c) }
            }
            parentId == UP_NEXT -> {
                val s = playlistPlayer.state.value
                s.ids.mapIndexedNotNull { i, id -> byId[id]?.let { itemEntry("q:$i", it, c, playing = i == s.index) } }
            }
            parentId == DOWNLOADS -> downloads().map { itemEntry("dl:${it.id}", it, c) }
            else -> emptyList()
        }
    }

    private fun folder(
        id: String,
        title: String,
        subtitle: String?,
        grid: Boolean = false,
        artworkUri: String? = null,
        artworkData: ByteArray? = null,
    ): MediaItem {
        val extras =
            Bundle().apply {
                if (grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
        val meta =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
        artworkUri?.let { meta.setArtworkUri(it.toUri()) }
        artworkData?.let { meta.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(meta.build())
            .build()
    }

    private fun playable(
        id: String,
        title: String,
        subtitle: String?,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            ).build()

    private fun itemEntry(
        id: String,
        item: PlaylistCandidate,
        c: PlaylistContext,
        playing: Boolean = false,
    ): MediaItem {
        val secs = item.seconds(c)
        val subtitle =
            buildString {
                if (playing) append("Playing · ")
                append(item.kind.label)
                append(" · ")
                append(item.feedTitle)
                if (secs != null) append(" · ${formatTotal(secs)}")
                if (item.kind == ItemKind.YOUTUBE && !item.isDownloaded(c)) append(" · needs download")
            }
        val extras =
            Bundle().apply {
                val pos = c.positions[item.id]
                when {
                    item.read -> putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
                    item.status(c) == PlayStatus.IN_PROGRESS && pos != null -> {
                        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                        if (pos.second > 0) putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, pos.first.toDouble() / pos.second)
                    }
                    else -> putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
                }
            }
        val meta =
            MediaMetadata
                .Builder()
                .setTitle(item.title)
                .setSubtitle(subtitle)
                .setArtist(item.feedTitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(extras)
        (item.image?.url ?: item.feedImage)?.let { meta.setArtworkUri(it.toUri()) }
        return MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(meta.build())
            .build()
    }

    /** The playlist's colored tile with its emoji, as a PNG for the car. */
    private fun tile(pl: Playlist): ByteArray =
        tiles.getOrPut("${pl.icon}|${pl.color}") {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pl.color.toInt() })
            val text =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 120f
                    textAlign = Paint.Align.CENTER
                    color = android.graphics.Color.WHITE
                }
            canvas.drawText(pl.icon, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

    companion object {
        private const val SESSION_ID = "feeder-playlist"
        private const val CHANNEL_ID = "playlist-playback"
        private const val NOTIFICATION_ID = 2404
        private const val ACTION_SPEED = "com.nononsenseapps.feeder.playlist.SPEED"
        private const val ROOT = "root"
        private const val PLAYLISTS = "playlists"
        private const val PODCASTS = "podcasts"
        private const val UP_NEXT = "upnext"
        private const val DOWNLOADS = "downloads"
        private const val MAX_EPISODES = 50
    }
}
