package com.nononsenseapps.feeder.ui.compose.feedarticle

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureReasonTest {
    @Test
    fun keepsYtDlpsErrorLine() {
        val e =
            RuntimeException(
                "WARNING: [youtube] No supported JavaScript runtime could be found.\n" +
                    "ERROR: [youtube] RotCje2J5Ik: Sign in to confirm you're not a bot. Use --cookies\n",
            )
        assertEquals("Sign in to confirm you're not a bot. Use --cookies", failureReason(e))
    }

    @Test
    fun otherErrorsUseTheirMessage() {
        assertEquals("yt-dlp found no stream", failureReason(IllegalStateException("yt-dlp found no stream")))
    }
}
