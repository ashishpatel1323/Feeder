package com.nononsenseapps.feeder.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer
import com.nononsenseapps.feeder.model.history.CentralHistoryRecorder
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.playlists.PlaylistContext
import com.nononsenseapps.feeder.model.playlists.PlaylistPlayer
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.kodein.di.instance

internal val ContinueKindKey = ActionParameters.Key<String>("kind")
internal val ContinueIdKey = ActionParameters.Key<String>("id")
internal val ContinueItemKey = ActionParameters.Key<String>("item")
internal val ContinueSeekKey = ActionParameters.Key<Long>("seek")

/** Play/pause on a playlist or audiobook pill: starts it where it was left when it isn't loaded. */
class ContinuePlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as FeederApplication
        val kind = parameters[ContinueKindKey]?.let { runCatching { ContinueKind.valueOf(it) }.getOrNull() } ?: return
        val id = parameters[ContinueIdKey] ?: return
        try {
            when (kind) {
                ContinueKind.PLAYLIST -> {
                    val player: PlaylistPlayer by app.di.instance()
                    val s = player.state.value
                    if (s.active && s.playlistId == id) {
                        withContext(Dispatchers.Main) { player.toggle() }
                    } else {
                        resumePlaylist(app, id, parameters[ContinueItemKey].orEmpty())
                    }
                }
                ContinueKind.AUDIOBOOK -> {
                    val player: AudiobookPlayer by app.di.instance()
                    withContext(Dispatchers.Main) {
                        if (player.state.value.bookId == id) {
                            player.toggle(reason = AudiobookPlayer.REASON_WIDGET)
                        } else {
                            player.open(id, reason = AudiobookPlayer.REASON_WIDGET)
                        }
                    }
                }
                else -> return
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Play/pause from the widget failed", e)
        }
        ContinueWidget().updateAll(app)
    }
}

/** −10 s / +30 s on a playlist or audiobook pill. An audiobook not loaded is loaded first (paused). */
class ContinueSeekAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as FeederApplication
        val kind = parameters[ContinueKindKey]?.let { runCatching { ContinueKind.valueOf(it) }.getOrNull() } ?: return
        val id = parameters[ContinueIdKey] ?: return
        val ms = parameters[ContinueSeekKey] ?: return
        withContext(Dispatchers.Main) {
            when (kind) {
                ContinueKind.PLAYLIST -> {
                    val player: PlaylistPlayer by app.di.instance()
                    val s = player.state.value
                    if (s.active && s.playlistId == id) player.seekBy(ms)
                }
                ContinueKind.AUDIOBOOK -> {
                    val player: AudiobookPlayer by app.di.instance()
                    if (player.state.value.bookId != id) player.open(id, play = false, reason = AudiobookPlayer.REASON_WIDGET)
                    if (player.state.value.bookId == id) player.seekBy(ms)
                }
                else -> Unit
            }
        }
        ContinueWidget().updateAll(app)
    }
}

/** Plays playlist [playlistId] from the item with article key [itemKey] (or its first item). */
private suspend fun resumePlaylist(
    app: FeederApplication,
    playlistId: String,
    itemKey: String,
) {
    val store: PlaylistStore by app.di.instance()
    val dao: FeedItemDao by app.di.instance()
    val offlineFiles: OfflineFiles by app.di.instance()
    val offlineVideos: OfflineVideos by app.di.instance()
    val recorder: CentralHistoryRecorder by app.di.instance()
    val player: PlaylistPlayer by app.di.instance()
    val playlist = store.get(playlistId) ?: return
    val (ids, itemId) =
        withContext(Dispatchers.IO) {
            val candidates = dao.loadPlaylistCandidates().first()
            val downloaded = (offlineFiles.savedAudioIds() + offlineVideos.savedIds()).toSet()
            val ctx = PlaylistContext(positions = store.positions.value, downloaded = downloaded, speed = player.articleSpeed())
            playlist.items(candidates, ctx).map { it.id } to itemKey.takeIf { it.isNotEmpty() }?.let { recorder.itemIdOf(it) }
        }
    withContext(Dispatchers.Main) {
        when {
            ids.isNotEmpty() -> player.start(ids, playlist.name, playlist.id, ids.indexOf(itemId).coerceAtLeast(0))
            // Gone from a smart playlist since: play just that item, still as the playlist
            itemId != null -> player.start(listOf(itemId), playlist.name, playlist.id)
        }
    }
}

private const val LOG_TAG = "FEEDER_WIDGET"
