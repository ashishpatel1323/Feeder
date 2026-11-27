package com.nononsenseapps.feeder.model.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the web app's "Downloads to Player" tests in web/src/lib/__tests__/media.test.ts. */
class MoveRulesTest {
    @Test
    fun putsAVideoInYouTubeChannel() {
        assertEquals("YouTube/Tech Channel" to "How to fix a b.mp4", MoveRules.playerPath("How to: fix a/b?", "Tech  Channel"))
        assertEquals("YouTube/Unknown channel" to "Video.mp4", MoveRules.playerPath("", ""))
        assertEquals("Podcasts/Show" to "Ep 1.mp3", MoveRules.playerPath("Ep 1", "Show", MoveRules.PODCASTS, "mp3"))
        assertEquals("Ends with dots", MoveRules.safeName("Ends with dots...", "x"))
    }

    @Test
    fun keepsEnglishSubtitlesFirst() {
        assertEquals("1.subs.en.vtt", MoveRules.pickSubtitle(listOf("1.subs.de.vtt", "1.subs.en-US.vtt", "1.subs.en.vtt")))
        assertEquals("1.subs.en-GB.vtt", MoveRules.pickSubtitle(listOf("1.subs.de.vtt", "1.subs.en-GB.vtt")))
        assertEquals("1.subs.fr.vtt", MoveRules.pickSubtitle(listOf("1.subs.fr.vtt")))
        assertNull(MoveRules.pickSubtitle(emptyList()))
    }
}
