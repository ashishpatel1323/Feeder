package com.nononsenseapps.feeder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class ReadAloudTimelineTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun splitSentencesPreservedTextAndRespectedEngineLimit() {
        val text = "First sentence. Second sentence! " + "a".repeat(2000) + " \uD83D\uDE00 End."
        val parts = speechSegments(text, Locale.ENGLISH, 100)
        assertTrue(parts.all { it.end - it.start <= 100 })
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(parts.none { Character.isHighSurrogate(text[it.end - 1]) })
    }

    @Test fun blankTextHadNoSegments() {
        assertTrue(speechSegments(" \n\t", Locale.ENGLISH, 50).isEmpty())
    }

    @Test fun longOpeningSentenceUsedAShortStartupChunkWithoutLosingText() {
        val text = "A long opening sentence ".repeat(100) + "."
        val parts = speechSegments(text, Locale.ENGLISH, 700, firstChunkMaxLength = 180)
        assertTrue(parts.first().end <= 180)
        assertTrue(parts.drop(1).all { it.end - it.start <= 700 })
        assertEquals(text, parts.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test fun seekingSelectedTheCorrectSentenceAtBoundaries() {
        val parts = listOf(SpeechSegment(0, 10, 0, 500), SpeechSegment(10, 20, 500, 1000))
        assertEquals(parts[0], parts.segmentAt(499))
        assertEquals(parts[1], parts.segmentAt(500))
        assertNull(parts.segmentAt(1000))
        assertNull(parts.segmentAt(-1))
    }

    @Test fun wrotePlayablePcmWaveHeaderAndExactDuration() {
        val file = folder.newFile()
        SpeechWaveFile(file).use {
            it.setFormat(24000, 1, 16, false)
            it.append(ByteArray(24000))
            it.append(ByteArray(24000))
            assertEquals(1000L, it.durationMillis())
            it.finish()
        }
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(48036, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(24000, header.getInt(24))
        assertEquals(48000, header.getInt(40))
        assertEquals(48044, bytes.size)
    }

    @Test fun paddedOddSizedEightBitPcm() {
        val file = folder.newFile()
        SpeechWaveFile(file).use {
            it.setFormat(8000, 1, 8, false)
            it.append(byteArrayOf(1, 2, 3))
            it.finish()
        }
        assertEquals(48L, file.length())
        assertEquals(3, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectedFormatChanges() {
        SpeechWaveFile(folder.newFile()).use {
            it.setFormat(24000, 1, 16, false)
            it.setFormat(48000, 1, 16, false)
        }
    }

    @Test fun reduced24BitSamplesSplitAcrossCallbacksTo16Bit() {
        val pcm = EnginePcm.forAudioFormat(21)
        assertEquals(16, pcm.bits)
        // Samples 0x123456 and 0xABCDEF, little endian, split mid-sample.
        val first = pcm.convert(byteArrayOf(0x56, 0x34, 0x12, 0xEF.toByte()))
        val second = pcm.convert(byteArrayOf(0xCD.toByte(), 0xAB.toByte()))
        assertEquals(listOf<Byte>(0x34, 0x12), first.toList())
        assertEquals(listOf(0xCD.toByte(), 0xAB.toByte()), second.toList())
    }

    @Test fun keptSupportedFormatsUnchanged() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertTrue(EnginePcm.forAudioFormat(2).convert(bytes) === bytes)
        assertTrue(EnginePcm.forWave(true, 32).convert(bytes) === bytes)
    }

    @Test fun readWrittenWaveFileLayout() {
        val file = folder.newFile("engine.wav")
        val data = ByteArray(10) { it.toByte() }
        val header =
            ByteBuffer.allocate(44 + 12).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + 12 + data.size)
                put("WAVE".toByteArray())
                put("LIST".toByteArray())
                putInt(4)
                put("INFO".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(22050)
                putInt(22050 * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(data.size)
            }
        file.writeBytes(header.array().copyOf(header.position()) + data)
        val wave = readWavePcm(file)!!
        assertEquals(22050, wave.sampleRate)
        assertEquals(1, wave.channels)
        assertEquals(16, wave.bitsPerSample)
        assertEquals(false, wave.floatingPoint)
        assertEquals(file.length() - data.size, wave.dataOffset)
        assertEquals(data.size.toLong(), wave.dataLength)
    }

    @Test fun ignoredFilesThatAreNotWaves() {
        val file = folder.newFile("empty.wav")
        assertNull(readWavePcm(file))
    }
}
