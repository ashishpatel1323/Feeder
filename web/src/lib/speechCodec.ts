/**
 * Compresses natural-voice speech for keeping (Opus via WebCodecs, about 0.25 MB a minute instead
 * of 2.9 MB of 16-bit PCM), with plain 16-bit PCM where the browser has no Opus encoder.
 */

export interface StoredSpeech {
  codec: 'opus' | 'pcm16'
  sampleRate: number
  samples: number
  /** pcm16: one buffer of samples. opus: encoded packets, with their timestamps (µs). */
  data: ArrayBuffer[]
  times?: number[]
  bytes: number
}

const OPUS_BITRATE = 32_000

function pcm16(samples: Float32Array): ArrayBuffer {
  const out = new Int16Array(samples.length)
  for (let i = 0; i < samples.length; i++) {
    const v = Math.max(-1, Math.min(1, samples[i]))
    out[i] = v < 0 ? v * 0x8000 : v * 0x7fff
  }
  return out.buffer
}

async function opusSupported(sampleRate: number): Promise<boolean> {
  if (typeof AudioEncoder === 'undefined' || typeof AudioDecoder === 'undefined') return false
  try {
    const enc = await AudioEncoder.isConfigSupported({ codec: 'opus', sampleRate, numberOfChannels: 1, bitrate: OPUS_BITRATE })
    const dec = await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate, numberOfChannels: 1 })
    return !!enc.supported && !!dec.supported
  } catch {
    return false
  }
}

export async function encodeSpeech(samples: Float32Array, sampleRate: number): Promise<StoredSpeech> {
  if (!(await opusSupported(sampleRate))) {
    const buf = pcm16(samples)
    return { codec: 'pcm16', sampleRate, samples: samples.length, data: [buf], bytes: buf.byteLength }
  }
  const data: ArrayBuffer[] = []
  const times: number[] = []
  let failure: unknown = null
  const encoder = new AudioEncoder({
    output: (chunk) => {
      const buf = new ArrayBuffer(chunk.byteLength)
      chunk.copyTo(buf)
      data.push(buf)
      times.push(chunk.timestamp)
    },
    error: (e) => {
      failure = e
    },
  })
  encoder.configure({ codec: 'opus', sampleRate, numberOfChannels: 1, bitrate: OPUS_BITRATE })
  const frame = sampleRate // one second at a time
  for (let i = 0; i < samples.length; i += frame) {
    const part = samples.subarray(i, Math.min(samples.length, i + frame))
    const audio = new AudioData({ format: 'f32', sampleRate, numberOfFrames: part.length, numberOfChannels: 1, timestamp: Math.round((i / sampleRate) * 1e6), data: part.slice() })
    encoder.encode(audio)
    audio.close()
  }
  await encoder.flush()
  encoder.close()
  if (failure) throw failure
  return { codec: 'opus', sampleRate, samples: samples.length, data, times, bytes: data.reduce((n, b) => n + b.byteLength, 0) }
}

export async function decodeSpeech(s: StoredSpeech): Promise<{ samples: Float32Array; sampleRate: number }> {
  if (s.codec === 'pcm16') {
    const pcm = new Int16Array(s.data[0])
    const samples = new Float32Array(pcm.length)
    for (let i = 0; i < pcm.length; i++) samples[i] = pcm[i] / 0x8000
    return { samples, sampleRate: s.sampleRate }
  }
  const out = new Float32Array(s.samples)
  let at = 0
  let failure: unknown = null
  const decoder = new AudioDecoder({
    output: (audio) => {
      const n = Math.min(audio.numberOfFrames, out.length - at)
      if (n > 0) {
        const tmp = new Float32Array(audio.numberOfFrames)
        audio.copyTo(tmp, { planeIndex: 0, format: 'f32-planar' })
        out.set(tmp.subarray(0, n), at)
        at += n
      }
      audio.close()
    },
    error: (e) => {
      failure = e
    },
  })
  decoder.configure({ codec: 'opus', sampleRate: s.sampleRate, numberOfChannels: 1 })
  s.data.forEach((d, i) => decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: s.times?.[i] ?? 0, data: d })))
  await decoder.flush()
  decoder.close()
  if (failure) throw failure
  return { samples: out, sampleRate: s.sampleRate }
}
