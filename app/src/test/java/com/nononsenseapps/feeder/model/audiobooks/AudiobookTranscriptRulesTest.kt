package com.nononsenseapps.feeder.model.audiobooks

import com.nononsenseapps.feeder.model.audiobooks.AudiobookTranscriptRules.FIRST_PIECE_MS
import com.nononsenseapps.feeder.model.audiobooks.AudiobookTranscriptRules.PIECE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobookTranscriptRulesTest {
    private fun piece(
        s: Long,
        e: Long,
        vararg cues: Pair<Long, String>,
    ) = TranscriptPiece(s, e, cues.map { PieceCue(it.first, it.first + 1000, it.second) })

    private val min = 60_000L

    @Test
    fun mergeKeepsEarlierPieceWhereTheyOverlap() {
        val a = piece(0, 10 * min, 0L to "a1", 5 * min to "a2")
        val b = piece(8 * min, 15 * min, 8 * min to "b1", 12 * min to "b2")
        assertEquals(listOf("a1", "a2", "b2"), AudiobookTranscriptRules.merge(listOf(b, a)).map { it.text })
    }

    @Test
    fun rangesJoinTouchingPieces() {
        val r = AudiobookTranscriptRules.ranges(listOf(piece(5 * min, 10 * min), piece(0, 5 * min), piece(20 * min, 25 * min)))
        assertEquals(listOf(0..10 * min, 20 * min..25 * min), r)
        assertEquals(10 * min, AudiobookTranscriptRules.readyUntil(r, 3 * min))
        assertEquals(15 * min, AudiobookTranscriptRules.readyUntil(r, 15 * min))
    }

    @Test
    fun firstPieceIsShortAtThePlayPosition() {
        val t = AudiobookTranscriptRules.nextTarget(listOf(60 * min), emptyList(), emptyList(), 7 * min)!!
        assertEquals(0, t.i)
        assertEquals(7 * min, t.startMs)
        assertEquals(7 * min + FIRST_PIECE_MS, t.endMs)
    }

    @Test
    fun carriesOnFromWhereTheTranscriptEndsAndStopsAtTheNextPiece() {
        val pieces = listOf(piece(0, 10 * min), piece(13 * min, 20 * min))
        val t = AudiobookTranscriptRules.nextTarget(listOf(60 * min), emptyList(), pieces, 2 * min)!!
        assertEquals(10 * min, t.startMs)
        assertEquals(13 * min, t.endMs)
        val t2 = AudiobookTranscriptRules.nextTarget(listOf(60 * min), emptyList(), pieces + piece(10 * min, 13 * min), 2 * min)!!
        assertEquals(20 * min, t2.startMs)
        assertEquals(20 * min + PIECE_MS, t2.endMs)
    }

    @Test
    fun staysInsideOneFile() {
        // Files of 12 and 30 minutes; ready to 10 min, so the piece ends with the first file
        val t = AudiobookTranscriptRules.nextTarget(listOf(12 * min, 30 * min), emptyList(), listOf(piece(0, 10 * min)), 1 * min)!!
        assertEquals(0, t.i)
        assertEquals(12 * min, t.endMs)
        val t2 = AudiobookTranscriptRules.nextTarget(listOf(12 * min, 30 * min), emptyList(), listOf(piece(0, 12 * min)), 1 * min)!!
        assertEquals(1, t2.i)
        assertEquals(0L, t2.startMs)
        assertEquals(12 * min, t2.bookStartMs)
    }

    @Test
    fun nothingWhenReadyFarEnoughAhead() {
        val chapters = listOf(Chapter(0, 0, 0, 10 * min, "1"), Chapter(0, 10 * min, 10 * min, 10 * min, "2"), Chapter(0, 20 * min, 20 * min, 40 * min, "3"))
        // Playing chapter 1: ready to the end of chapter 2 (20 min) is not 15 min ahead of 9 min, so on to 24
        assertEquals(24 * min, AudiobookTranscriptRules.aheadUntil(chapters, 9 * min, 60 * min))
        assertNull(AudiobookTranscriptRules.nextTarget(listOf(60 * min), chapters, listOf(piece(0, 25 * min)), 9 * min))
    }

    @Test
    fun unknownLengthsWait() {
        assertNull(AudiobookTranscriptRules.nextTarget(listOf(0L), emptyList(), emptyList(), 0))
    }

    @Test
    fun aSliverAtTheEndOfAFileIsSkipped() {
        val t = AudiobookTranscriptRules.nextTarget(listOf(35_060L, 35_000L), emptyList(), listOf(piece(0, 34_900)), 0)!!
        assertEquals(1, t.i)
        assertEquals(0L, t.startMs)
    }
}
