/// <reference lib="webworker" />
/**
 * Speech to text for podcast transcripts, off the main thread: OpenAI's Whisper (open source,
 * English base model) run by Transformers.js. The model downloads once from Hugging Face and the
 * browser caches it; the audio never leaves this device.
 */
import { pipeline, type AutomaticSpeechRecognitionPipeline } from '@huggingface/transformers'

export type WhisperDevice = 'wasm' | 'webgpu'

export type WhisperRequest = { type: 'transcribe'; id: number; samples: Float32Array; device: WhisperDevice }

export type WhisperResponse =
  | { type: 'progress'; loaded: number; total: number }
  | { type: 'ready'; device: WhisperDevice }
  | { type: 'result'; id: number; chunks: { start: number; end: number | null; text: string }[] }
  | { type: 'error'; id?: number; message: string }

const MODEL = 'onnx-community/whisper-base.en'
const scope = self as unknown as DedicatedWorkerGlobalScope
let asr: Promise<AutomaticSpeechRecognitionPipeline> | null = null

const post = (m: WhisperResponse) => scope.postMessage(m)

function load(device: WhisperDevice): Promise<AutomaticSpeechRecognitionPipeline> {
  if (asr) return asr
  const files = new Map<string, { loaded: number; total: number }>()
  // Typed loosely: the full pipeline() overloads are too much for the type checker
  const create = pipeline as unknown as (task: string, model: string, options: object) => Promise<AutomaticSpeechRecognitionPipeline>
  asr = create('automatic-speech-recognition', MODEL, {
    device,
    // The GPU wants a full-precision encoder; the CPU path stays small and quick
    dtype: device === 'webgpu' ? { encoder_model: 'fp32', decoder_model_merged: 'q4' } : 'q8',
    progress_callback: (p: { status: string; file?: string; loaded?: number; total?: number }) => {
      if (p.status !== 'progress' || !p.file) return
      files.set(p.file, { loaded: p.loaded ?? 0, total: p.total ?? 0 })
      let loaded = 0
      let total = 0
      for (const f of files.values()) {
        loaded += f.loaded
        total += f.total
      }
      post({ type: 'progress', loaded, total })
    },
  })
  asr.then(
    () => post({ type: 'ready', device }),
    () => (asr = null),
  )
  return asr
}

scope.onmessage = async (e: MessageEvent<WhisperRequest>) => {
  const m = e.data
  try {
    const model = await load(m.device)
    // Up to 30 s is one Whisper window; longer pieces go in overlapping 30 s windows
    const long = m.samples.length > 30 * 16_000
    const out = (await model(m.samples, long ? { return_timestamps: true, chunk_length_s: 30, stride_length_s: 5 } : { return_timestamps: true })) as {
      chunks?: { timestamp: [number, number | null]; text: string }[]
    }
    post({
      type: 'result',
      id: m.id,
      chunks: (out.chunks ?? []).map((c) => ({ start: c.timestamp[0], end: c.timestamp[1], text: c.text })),
    })
  } catch (err) {
    post({ type: 'error', id: m.id, message: err instanceof Error ? err.message : String(err) })
  }
}
