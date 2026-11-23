package com.nononsenseapps.feeder.ui.compose.item

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.nononsenseapps.feeder.model.PodcastPlayerState
import com.nononsenseapps.feeder.model.PodcastPlayerStateHolder
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.playlists.ItemProgress
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * Feeder's own podcast player, as the Play / Resume / Pause buttons of rows, the article screen
 * and Flip use it: one episode at a time, resuming where it was left.
 */
@Stable
class ItemPlayer(
    private val holder: PodcastPlayerStateHolder,
    private val store: PlaylistStore,
    private val tts: TTSStateHolder,
    private val state: State<PodcastPlayerState>,
) {
    val playerState: PodcastPlayerState get() = state.value

    /** This item's episode is loaded in the player (playing, buffering or paused). */
    fun isLoaded(itemId: Long): Boolean = state.value.isVisible && state.value.itemId == itemId

    fun isPlaying(itemId: Long): Boolean = isLoaded(itemId) && (state.value.isPlaying || state.value.isLoading)

    /** Play, Resume or Pause: pauses this episode when it plays, else plays it from where it was left. */
    fun togglePodcast(
        itemId: Long,
        link: String,
        progress: ItemProgress? = null,
    ) {
        val s = state.value
        when {
            isLoaded(itemId) && (s.isPlaying || s.isLoading) -> holder.pause()
            isLoaded(itemId) -> holder.play()
            else -> {
                tts.stop()
                val start = if (progress?.partlyPlayed == true) store.resumeAt(itemId) else 0L
                holder.playLink(link, itemId, startAtSeconds = start)
            }
        }
    }

    fun seekBy(millis: Int) = holder.seekBy(millis)

    fun seekTo(millis: Int) = holder.seekTo(millis)

    fun stop() = holder.stop()
}

@Composable
fun rememberItemPlayer(): ItemPlayer {
    val di = LocalDI.current
    val holder: PodcastPlayerStateHolder by di.instance()
    val store: PlaylistStore by di.instance()
    val tts: TTSStateHolder by di.instance()
    val state = holder.playerState.collectAsState()
    return remember(holder, state) { ItemPlayer(holder, store, tts, state) }
}
