package com.nononsenseapps.feeder.model.ebooks

import com.nononsenseapps.feeder.model.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class BookAudioChunksTest {
    private val parts = listOf(SpeechSegment(0, 10), SpeechSegment(10, 25), SpeechSegment(25, 40), SpeechSegment(40, 52))

    @Test
    fun nothingMadeStartsAtTheBeginning() {
        assertEquals(parts, remainingParts(parts, 0))
    }

    @Test
    fun stoppedJobCarriesOnAfterTheLastSavedChunk() {
        assertEquals(parts.drop(2), remainingParts(parts, 25))
    }

    @Test
    fun allChunksMadeLeavesNothing() {
        assertEquals(emptyList<SpeechSegment>(), remainingParts(parts, 52))
    }

    @Test
    fun chunkBatchesResumeWithoutRepeatOrGap() {
        // A job stopped after its first batch of two sentences, then a new job
        val first = parts.chunked(2).first()
        val rest = remainingParts(parts, first.last().end)
        assertEquals(parts, first + rest)
    }

    @Test
    fun chunkTimelinesAreJoinedAfterTheAudioBefore() {
        val chunk = listOf(SpeechSegment(25, 40, 0, 1200), SpeechSegment(40, 52, 1200, 2000))
        assertEquals(
            listOf(SpeechSegment(25, 40, 5000, 6200), SpeechSegment(40, 52, 6200, 7000)),
            chunk.shiftedBy(5000),
        )
    }
}
