package com.nononsenseapps.feeder.model

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.text.BreakIterator
import java.util.Locale

data class SpeechSegment(
    val start: Int,
    val end: Int,
    val startMillis: Long = 0,
    val endMillis: Long = 0,
)

/** Sentence boundaries also provide a reliable highlight when the engine has no word timing. */
internal fun speechSegments(
    text: String,
    locale: Locale,
    maxLength: Int,
    firstChunkMaxLength: Int = maxLength,
): List<SpeechSegment> {
    require(maxLength >= 2)
    require(firstChunkMaxLength >= 2)
    val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var cursor = start
            while (cursor < end) {
                val chunkLength = if (isEmpty()) minOf(maxLength, firstChunkMaxLength) else maxLength
                var limit = minOf(cursor + chunkLength, end)
                if (limit < end) {
                    val space = text.lastIndexOf(' ', limit - 1)
                    if (space > cursor) limit = space + 1
                    if (limit > cursor && Character.isHighSurrogate(text[limit - 1])) limit--
                }
                if (text.substring(cursor, limit).isNotBlank()) add(SpeechSegment(cursor, limit))
                cursor = limit
            }
            start = end
            end = iterator.next()
        }
    }
}

/** Drops the segments before [offset], keeping the sentence that contains it. */
internal fun List<SpeechSegment>.startingAt(offset: Int): List<SpeechSegment> = if (offset <= 0) this else dropWhile { it.end <= offset }

internal fun List<SpeechSegment>.segmentAt(positionMillis: Long): SpeechSegment? = lastOrNull { positionMillis >= it.startMillis }?.takeIf { positionMillis < it.endMillis }

/** Streams engine PCM to disk; never retains an article's audio in memory. */
internal class SpeechWaveFile(
    file: File,
) : Closeable {
    private val output =
        RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(44))
        }
    private var sampleRate = 0
    private var channels = 0
    private var bits = 0
    private var format = 0
    private var dataSize = 0L

    @Synchronized
    fun audioFormat(): SpeechPcmFormat = SpeechPcmFormat(sampleRate, channels, bits, format == 3)

    @Synchronized
    fun sizeBytes(): Long = dataSize

    @Synchronized
    fun setFormat(
        rate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        floatingPoint: Boolean,
    ) {
        require(rate > 0 && channelCount in 1..2 && bitsPerSample in setOf(8, 16, 32))
        val newFormat = if (floatingPoint) 3 else 1
        check(sampleRate == 0 || (sampleRate == rate && channels == channelCount && bits == bitsPerSample && format == newFormat)) {
            "Speech engine changed audio format within an article"
        }
        sampleRate = rate
        channels = channelCount
        bits = bitsPerSample
        format = newFormat
    }

    @Synchronized
    fun append(bytes: ByteArray) {
        check(sampleRate > 0)
        check(dataSize + bytes.size <= 256L * 1024 * 1024) { "Read-aloud audio exceeded cache limit" }
        output.write(bytes)
        dataSize += bytes.size
    }

    @Synchronized
    fun durationMillis(): Long = if (sampleRate == 0) 0 else dataSize * 1000 / (sampleRate * channels * (bits / 8))

    @Synchronized
    fun finish() {
        check(dataSize > 0)
        val padding = (dataSize % 2).toInt()
        if (padding != 0) output.write(0)
        output.seek(0)
        output.writeBytes("RIFF")
        writeInt((dataSize + padding + 36).toInt())
        output.writeBytes("WAVEfmt ")
        writeInt(16)
        writeShort(format)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(sampleRate * channels * bits / 8)
        writeShort(channels * bits / 8)
        writeShort(bits)
        output.writeBytes("data")
        writeInt(dataSize.toInt())
    }

    private fun writeInt(value: Int) = output.writeInt(Integer.reverseBytes(value))

    private fun writeShort(value: Int) =
        output.writeShort(
            java.lang.Short
                .reverseBytes(value.toShort())
                .toInt(),
        )

    @Synchronized
    override fun close() = output.close()
}

/** How an engine's PCM encoding is stored: as is, or reduced to 16-bit when the player can't take it directly. */
internal class EnginePcm private constructor(
    val bits: Int,
    val floatingPoint: Boolean,
    private val inputWidth: Int,
) {
    private var remainder = ByteArray(0)

    /** Returns bytes in the stored format; keeps partial samples split across callbacks. */
    fun convert(bytes: ByteArray): ByteArray {
        if (inputWidth * 8 == bits) return bytes
        val input = if (remainder.isEmpty()) bytes else remainder + bytes
        val samples = input.size / inputWidth
        remainder = input.copyOfRange(samples * inputWidth, input.size)
        val output = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Little endian: the two most significant bytes are the last two of each sample.
            output[i * 2] = input[i * inputWidth + inputWidth - 2]
            output[i * 2 + 1] = input[i * inputWidth + inputWidth - 1]
        }
        return output
    }

    companion object {
        // AudioFormat.ENCODING_PCM_24BIT_PACKED and ENCODING_PCM_32BIT (API 31), inlined as values.
        private const val ENCODING_PCM_24BIT_PACKED = 21
        private const val ENCODING_PCM_32BIT = 22

        fun forAudioFormat(audioFormat: Int): EnginePcm =
            when (audioFormat) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> EnginePcm(8, false, 1)
                android.media.AudioFormat.ENCODING_PCM_16BIT -> EnginePcm(16, false, 2)
                android.media.AudioFormat.ENCODING_PCM_FLOAT -> EnginePcm(32, true, 4)
                ENCODING_PCM_24BIT_PACKED -> EnginePcm(16, false, 3)
                ENCODING_PCM_32BIT -> EnginePcm(16, false, 4)
                else -> error("Unsupported speech audio format $audioFormat")
            }

        fun forWave(
            floatingPoint: Boolean,
            bitsPerSample: Int,
        ): EnginePcm =
            when {
                floatingPoint && bitsPerSample == 32 -> EnginePcm(32, true, 4)
                floatingPoint -> error("Unsupported speech audio format float$bitsPerSample")
                bitsPerSample == 8 -> EnginePcm(8, false, 1)
                bitsPerSample == 16 -> EnginePcm(16, false, 2)
                bitsPerSample == 24 -> EnginePcm(16, false, 3)
                bitsPerSample == 32 -> EnginePcm(16, false, 4)
                else -> error("Unsupported speech audio format pcm$bitsPerSample")
            }
    }
}

internal data class WavePcmData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val floatingPoint: Boolean,
    val dataOffset: Long,
    val dataLength: Long,
)

/**
 * Some engines write the requested file without reporting audio through the progress callbacks.
 * Reads the PCM layout of such a file so its samples can be used instead.
 */
internal fun readWavePcm(file: File): WavePcmData? {
    if (!file.isFile || file.length() < 44) return null
    RandomAccessFile(file, "r").use { input ->
        fun intLe(): Int = Integer.reverseBytes(input.readInt())

        fun shortLe(): Int =
            java.lang.Short
                .reverseBytes(input.readShort())
                .toInt() and 0xFFFF

        fun tag(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        if (tag() != "RIFF") return null
        input.readInt()
        if (tag() != "WAVE") return null
        var format: IntArray? = null
        while (input.filePointer + 8 <= input.length()) {
            val id = tag()
            val size = intLe().toLong() and 0xFFFFFFFFL
            val body = input.filePointer
            when (id) {
                "fmt " -> {
                    var code = shortLe()
                    val channels = shortLe()
                    val rate = intLe()
                    input.readInt()
                    input.readShort()
                    val bits = shortLe()
                    if (code == 0xFFFE && size >= 26) {
                        input.seek(body + 24)
                        code = shortLe()
                    }
                    format = intArrayOf(code, channels, rate, bits)
                }
                "data" -> {
                    val (code, channels, rate, bits) = format ?: return null
                    if (code != 1 && code != 3) return null
                    val available = input.length() - body
                    val length = if (size == 0L || size > available) available else size
                    return WavePcmData(rate, channels, bits, code == 3, body, length)
                }
            }
            input.seek(body + size + (size and 1))
        }
    }
    return null
}
