package com.nononsenseapps.feeder.model.playlists

import android.net.Uri
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What the car shows for one queued item. */
data class CarItemInfo(
    val title: String,
    val artist: String,
    val artwork: String?,
    val kind: ItemKind,
)

/**
 * The player Android Auto, the notification and the lock screen see for a playlist: one queue,
 * whether the current item is an episode playing in [PlaylistPlayer]'s audio player or an article
 * read aloud. It shows [PlaylistPlayer]'s state and sends every button press back to it.
 */
@UnstableApi
class CarQueuePlayer(
    looper: Looper,
    private val playlist: PlaylistPlayer,
    private val info: (Long) -> CarItemInfo?,
    /** Turns what the car picked (a media id from the browse tree) into a queue to play. */
    private val onPick: (mediaId: String) -> Unit,
) : SimpleBasePlayer(looper) {
    /** Call when [PlaylistPlayer]'s state or the item details changed. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = playlist.state.value
        val commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                ).build()
        val builder =
            State
                .Builder()
                .setAvailableCommands(commands)
                .setSeekBackIncrementMs(if (s.kind == ItemKind.ARTICLE) 10_000 else 15_000)
                .setSeekForwardIncrementMs(if (s.kind == ItemKind.ARTICLE) 10_000 else 30_000)
        if (!s.active) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val items =
            s.ids.mapIndexed { i, id ->
                val d = info(id)
                val current = i == s.index
                val title = if (current) s.title.ifBlank { d?.title.orEmpty() } else d?.title.orEmpty()
                val artist = if (current) s.feedTitle.ifBlank { d?.artist.orEmpty() } else d?.artist.orEmpty()
                val art = (if (current) s.artwork else null) ?: d?.artwork
                val kind = if (current) s.kind else d?.kind
                MediaItemData
                    .Builder("$i:$id")
                    .setMediaItem(
                        MediaItem
                            .Builder()
                            .setMediaId("q:$i")
                            .setMediaMetadata(
                                MediaMetadata
                                    .Builder()
                                    .setTitle(title)
                                    .setArtist(artist)
                                    .setAlbumTitle(s.name)
                                    .setSubtitle(if (kind == ItemKind.ARTICLE) "Article, read aloud · $artist" else artist)
                                    .setArtworkUri(art?.let(::safeUri))
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .build(),
                            ).build(),
                    ).setDurationUs(if (current && s.durationMs > 0) s.durationMs * 1000 else androidx.media3.common.C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
        val speed = if (s.kind == ItemKind.ARTICLE) playlist.articleSpeed() else s.audioSpeed
        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(s.index.coerceIn(0, items.size - 1))
            .setContentPositionMs(
                if (s.isPlaying) {
                    PositionSupplier.getExtrapolating(s.positionMs, speed)
                } else {
                    PositionSupplier.getConstant(s.positionMs)
                },
            ).setPlaybackParameters(PlaybackParameters(speed))
            .setPlaybackState(if (s.loading && !s.isPlaying) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(s.isPlaying || s.loading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    private fun safeUri(url: String): Uri? = runCatching { url.toUri() }.getOrNull()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playlist.toggle(play = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        playlist.toggle(play = false)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val s = playlist.state.value
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playlist.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playlist.previous()
            else ->
                if (mediaItemIndex != s.index && mediaItemIndex in s.ids.indices) {
                    playlist.jump(mediaItemIndex)
                } else if (positionMs >= 0) {
                    playlist.seekBy(positionMs - s.positionMs)
                }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val picked = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
        picked?.mediaId?.let(onPick)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> {
        mediaItems.firstOrNull()?.mediaId?.let(onPick)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        playlist.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }
}
