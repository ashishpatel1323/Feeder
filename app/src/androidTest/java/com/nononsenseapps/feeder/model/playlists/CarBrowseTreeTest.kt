package com.nononsenseapps.feeder.model.playlists

import android.content.ComponentName
import android.util.Log
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Browses the car tree the way Android Auto does: root tabs, then the lists under them. */
@RunWith(AndroidJUnit4::class)
class CarBrowseTreeTest {
    @Test
    fun browsesTabsAndLists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(context, ComponentName(context, PlaylistPlaybackService::class.java))
        val thread = android.os.HandlerThread("browser").apply { start() }
        val handler = android.os.Handler(thread.looper)

        fun <T> on(block: () -> com.google.common.util.concurrent.ListenableFuture<T>): T {
            val box = java.util.concurrent.LinkedBlockingQueue<com.google.common.util.concurrent.ListenableFuture<T>>()
            handler.post { box.put(block()) }
            return box.take().get(10, TimeUnit.SECONDS)
        }
        val browser = on { MediaBrowser.Builder(context, token).setApplicationLooper(thread.looper).buildAsync() }
        val root = on { browser.getLibraryRoot(null) }.value!!
        val tabs = on { browser.getChildren(root.mediaId, 0, 50, null) }.value!!
        assertEquals(listOf("playlists", "podcasts", "upnext", "downloads"), tabs.map { it.mediaId })
        for (tab in tabs) {
            val kids = on { browser.getChildren(tab.mediaId, 0, 50, null) }.value!!
            Log.i("CAR_TREE", "${tab.mediaMetadata.title}: ${kids.size} -> ${kids.take(5).joinToString { "${it.mediaMetadata.title} [${it.mediaMetadata.subtitle}]" }}")
        }
        val playlists = on { browser.getChildren("playlists", 0, 50, null) }.value!!
        assertTrue(playlists.isNotEmpty())
        val firstList = on { browser.getChildren(playlists.first().mediaId, 0, 50, null) }.value!!
        Log.i("CAR_TREE", "${playlists.first().mediaMetadata.title}: ${firstList.take(4).joinToString { "${it.mediaMetadata.title}" }}")
        // Pick a playlist the way the car does, then press Next and Pause
        val podcasts = playlists.first { it.mediaMetadata.title.toString() == "New podcast episodes" }
        val playAll = on { browser.getChildren(podcasts.mediaId, 0, 50, null) }.value!!.first()
        handler.post {
            browser.setMediaItem(playAll)
            browser.prepare()
            browser.play()
        }
        Thread.sleep(10_000)
        val first = java.util.concurrent.LinkedBlockingQueue<String>()
        handler.post { first.put("${browser.isPlaying} ${browser.currentMediaItemIndex}/${browser.mediaItemCount} ${browser.mediaMetadata.title} @${browser.currentPosition}") }
        Log.i("CAR_TREE", "after pick: ${first.take()}")
        handler.post { browser.seekToNext() }
        Thread.sleep(8_000)
        handler.post { first.put("${browser.isPlaying} ${browser.currentMediaItemIndex}/${browser.mediaItemCount} ${browser.mediaMetadata.title} @${browser.currentPosition}") }
        Log.i("CAR_TREE", "after next: ${first.take()}")
        handler.post { browser.pause() }
        Thread.sleep(1_000)
        handler.post { first.put("${browser.isPlaying}") }
        Log.i("CAR_TREE", "after pause playing=${first.take()}")
        // An article read aloud shows in the same car player
        val reads = playlists.first { it.mediaMetadata.title.toString() == "Short reads" }
        val readAll = on { browser.getChildren(reads.mediaId, 0, 50, null) }.value!!.first()
        handler.post {
            browser.setMediaItem(readAll)
            browser.play()
        }
        Thread.sleep(12_000)
        handler.post { first.put("${browser.isPlaying} ${browser.currentMediaItemIndex}/${browser.mediaItemCount} ${browser.mediaMetadata.title} ${browser.mediaMetadata.subtitle} @${browser.currentPosition}/${browser.duration}") }
        Log.i("CAR_TREE", "article: ${first.take()}")
        handler.post { browser.seekToNext() }
        Thread.sleep(10_000)
        handler.post { first.put("${browser.isPlaying} ${browser.currentMediaItemIndex}/${browser.mediaItemCount} ${browser.mediaMetadata.title}") }
        Log.i("CAR_TREE", "article next: ${first.take()}")
        handler.post { browser.pause() }
        Thread.sleep(1_000)
        handler.post { browser.release() }
    }
}
