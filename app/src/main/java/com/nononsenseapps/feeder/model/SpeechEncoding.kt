package com.nononsenseapps.feeder.model

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compresses a speech WAV file into AAC in an .m4a file (about 0.35 MB a minute instead of about
 * 2.8 MB), for audio kept on the device. Returns false when the device can't encode it; the
 * caller then keeps the WAV.
 */
internal fun encodeSpeechToAac(
    wav: File,
    out: File,
    bitRate: Int = 48_000,
): Boolean {
    val data = readWavePcm(wav) ?: return false
    val bytesPerSample = data.bitsPerSample / 8
    val frameBytes = bytesPerSample * data.channels
    if (frameBytes <= 0) return false
    val codec =
        runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        }.getOrNull() ?: return false
    var muxer: MediaMuxer? = null
    var ok = false
    try {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, data.sampleRate, data.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        RandomAccessFile(wav, "r").use { input ->
            input.seek(data.dataOffset)
            var remaining = data.dataLength
            var framesIn = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteArray(16 * 1024 / 2 * frameBytes)
            while (!outputDone) {
                var progressed = false
                // Feed every free input buffer, then take every finished output, without waiting
                while (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) break
                    progressed = true
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    // 16-bit samples in, whatever the WAV holds
                    val maxFrames = minOf(buffer.remaining() / (2 * data.channels), chunk.size / frameBytes)
                    val want = minOf(remaining, maxFrames.toLong() * frameBytes).toInt()
                    val read = if (want > 0) input.read(chunk, 0, want) else -1
                    val presentationUs = framesIn * 1_000_000L / data.sampleRate
                    if (read <= 0) {
                        codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val frames = read / frameBytes
                        toPcm16(chunk, frames * data.channels, data.bitsPerSample, data.floatingPoint, buffer)
                        codec.queueInputBuffer(index, 0, frames * data.channels * 2, presentationUs, 0)
                        framesIn += frames
                        remaining -= read
                    }
                }
                while (!outputDone) {
                    val out = codec.dequeueOutputBuffer(info, if (progressed) 0 else 5_000)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    progressed = true
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    } else if (out >= 0) {
                        val buffer = codec.getOutputBuffer(out)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
        ok = track >= 0
    } catch (e: Exception) {
        android.util.Log.w("FEEDER_READ_ALOUD", "Could not compress speech audio", e)
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        if (!ok) out.delete()
    }
    return ok
}

/** Writes [count] samples from [src] (8/16/24/32-bit integer or 32-bit float, little endian) as 16-bit into [dst]. */
private fun toPcm16(
    src: ByteArray,
    count: Int,
    bits: Int,
    floatingPoint: Boolean,
    dst: ByteBuffer,
) {
    dst.order(ByteOrder.LITTLE_ENDIAN)
    val width = bits / 8
    for (i in 0 until count) {
        val o = i * width
        val v: Int =
            when {
                floatingPoint && bits == 32 -> {
                    val f = Float.fromBits((src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or ((src[o + 2].toInt() and 0xFF) shl 16) or (src[o + 3].toInt() shl 24))
                    (f.coerceIn(-1f, 1f) * 32767).toInt()
                }
                bits == 8 -> ((src[o].toInt() and 0xFF) - 128) shl 8
                // The two most significant bytes of wider samples
                else -> (src[o + width - 2].toInt() and 0xFF) or (src[o + width - 1].toInt() shl 8)
            }
        dst.putShort(v.toShort())
    }
}
