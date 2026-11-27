package com.nononsenseapps.feeder.model.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A YouTube download's video-only file and sound-only file become one MP4 with both tracks. */
@RunWith(AndroidJUnit4::class)
class StreamJoinerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation()

    private fun asset(name: String): File {
        val out = File(ctx.targetContext.cacheDir, name)
        ctx.context.assets
            .open("media/$name")
            .use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    @Test
    fun joinsVideoAndSoundIntoOneMp4() {
        val out = File(ctx.targetContext.cacheDir, "joined.mp4")
        StreamJoiner.join(asset("video-only.mp4"), asset("sound-only.m4a"), out, webm = false)
        val ex = MediaExtractor()
        ex.setDataSource(out.absolutePath)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        ex.release()
        assertEquals(2, mimes.size)
        assertTrue(mimes.any { it.startsWith("video/") })
        assertTrue(mimes.any { it.startsWith("audio/") })
    }
}
