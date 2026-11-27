package com.nononsenseapps.feeder.model.transcript

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes an audio file (MP3, AAC/M4A, Opus…) with Android's own decoders into what Whisper
 * reads: 16 kHz mono floats. Streams, so an hour-long episode never sits in memory at once.
 */
class AudioDecoder(
    /** Points a fresh extractor at the audio: a file, a document URI or a streaming link. */
    private val source: (MediaExtractor) -> Unit,
    private val name: String = "audio",
) {
    constructor(file: File) : this({ it.setDataSource(file.absolutePath) }, file.name)

    /** Length of the audio in ms, or 0 if the file does not say. */
    val durationMs: Long by lazy {
        val extractor = MediaExtractor()
        try {
            source(extractor)
            audioTrack(extractor)?.let { (_, format) ->
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes from [startMs] to the end, giving 16 kHz mono samples to [onSamples] in pieces.
     * [onSamples] returns false to stop early.
     */
    fun decode(
        startMs: Long,
        onSamples: (FloatArray, Int) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source(extractor)
            val (track, format) = audioTrack(extractor) ?: error("No audio in $name")
            extractor.selectTrack(track)
            val startUs = startMs * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var resampler: Resampler? = null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var out = FloatArray(16384)

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm =
                            outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = null
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                        // Samples before the start (seeking lands on the frame before it) are skipped
                        val skip =
                            if (info.presentationTimeUs < startUs) {
                                (((startUs - info.presentationTimeUs) * rate) / 1_000_000L).toInt().coerceAtMost(frames)
                            } else {
                                0
                            }
                        val rs = resampler ?: Resampler(rate).also { resampler = it }
                        if (out.size < frames + 64) out = FloatArray(frames + 64)
                        var produced = 0
                        val shorts = if (floatPcm) null else buffer.asShortBuffer()
                        val floats = if (floatPcm) buffer.asFloatBuffer() else null
                        for (frame in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) {
                                val i = frame * channels + c
                                sum += floats?.get(i) ?: (shorts!!.get(i) / 32768f)
                            }
                            if (frame < skip) continue
                            produced = rs.push(sum / channels, out, produced)
                            if (produced >= out.size - 4) {
                                if (!onSamples(out, produced)) return
                                produced = 0
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (produced > 0 && !onSamples(out, produced)) return
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? =
        (0 until extractor.trackCount)
            .map { it to extractor.getTrackFormat(it) }
            .firstOrNull { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
}

/**
 * Turns mono samples at [sourceRate] into 16 kHz: a short moving average against aliasing, then
 * linear interpolation. Plenty for speech recognition.
 */
class Resampler(
    private val sourceRate: Int,
) {
    private val step = sourceRate / TARGET_RATE.toDouble()
    private val window = step.toInt().coerceAtLeast(1)
    private val history = FloatArray(window)
    private var historyIndex = 0
    private var historySum = 0f
    private var previous = 0f
    private var inputIndex = 0L
    private var nextOutput = 0.0

    /** Adds one sample; writes any output samples into [out] from [at]. Returns the new end. */
    fun push(
        sample: Float,
        out: FloatArray,
        at: Int,
    ): Int {
        historySum += sample - history[historyIndex]
        history[historyIndex] = sample
        historyIndex = (historyIndex + 1) % window
        val filtered = historySum / window
        var end = at
        if (sourceRate == TARGET_RATE) {
            out[end++] = sample
        } else {
            // Output samples falling between the previous input sample and this one
            while (nextOutput <= inputIndex) {
                val fraction = (nextOutput - (inputIndex - 1)).toFloat().coerceIn(0f, 1f)
                out[end++] = previous + (filtered - previous) * fraction
                nextOutput += step
            }
        }
        previous = filtered
        inputIndex++
        return end
    }

    companion object {
        const val TARGET_RATE = 16_000
    }
}
