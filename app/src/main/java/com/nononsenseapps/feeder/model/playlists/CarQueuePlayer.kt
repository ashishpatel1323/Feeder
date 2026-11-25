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
    /** Shown instead while no playlist plays and an audiobook is open. */
    private val audiobook: com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer? = null,
    /** The audiobook's cover, for the car and the notification. */
    private val audiobookCover: (String) -> ByteArray? = { null },
) : SimpleBasePlayer(looper) {
    private val onAudiobook: Boolean
        get() = !playlist.state.value.active && audiobook?.state?.value?.active == true

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
        if (onAudiobook) return audiobookState(builder)
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

    /** The open audiobook, with its chapters as the queue. */
    private fun audiobookState(builder: State.Builder): State {
        val a = audiobook!!.state.value
        val cover = a.bookId?.let(audiobookCover)
        val chapters =
            a.chapters.ifEmpty {
                listOf(
                    com.nononsenseapps.feeder.model.audiobooks
                        .Chapter(0, 0, 0, 0, a.title),
                )
            }
        val items =
            chapters.mapIndexed { k, c ->
                MediaItemData
                    .Builder("ab:$k")
                    .setMediaItem(
                        MediaItem
                            .Builder()
                            .setMediaId("abch:$k")
                            .setMediaMetadata(
                                MediaMetadata
                                    .Builder()
                                    .setTitle(c.title)
                                    .setArtist(a.author.ifBlank { a.title })
                                    .setAlbumTitle(a.title)
                                    .setSubtitle(a.title)
                                    .apply { cover?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .build(),
                            ).build(),
                    ).setDurationUs(if (c.lengthMs > 0) c.lengthMs * 1000 else androidx.media3.common.C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
        val index = a.chapter.coerceIn(0, items.size - 1)
        val pos = if (a.chapters.isEmpty()) a.ms else a.inChapterMs
        return builder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(if (a.isPlaying) PositionSupplier.getExtrapolating(pos, a.speed) else PositionSupplier.getConstant(pos))
            .setPlaybackParameters(PlaybackParameters(a.speed))
            .setPlaybackState(if (a.loading && !a.isPlaying) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(a.isPlaying || a.loading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (onAudiobook) {
            audiobook!!.toggle(play = playWhenReady)
            return Futures.immediateVoidFuture()
        }
        playlist.toggle(play = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        if (onAudiobook) {
            audiobook!!.pause()
            return Futures.immediateVoidFuture()
        }
        playlist.toggle(play = false)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (onAudiobook) {
            val a = audiobook!!
            val st = a.state.value
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> a.nextChapter()
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> a.previousChapter()
                else ->
                    if (st.chapters.isNotEmpty() && mediaItemIndex != st.chapter && mediaItemIndex in st.chapters.indices) {
                        a.jumpToChapter(mediaItemIndex)
                    } else if (positionMs >= 0) {
                        val c = st.currentChapter
                        if (c != null) a.seekToBookMs(c.bookMs + positionMs) else a.seekBy(positionMs - st.ms)
                    }
            }
            return Futures.immediateVoidFuture()
        }
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
        if (onAudiobook) {
            audiobook!!.setSpeed(playbackParameters.speed)
            return Futures.immediateVoidFuture()
        }
        playlist.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }
}
