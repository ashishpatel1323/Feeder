/// <reference lib="webworker" />
/**
 * Runs the Kokoro text-to-speech model off the main thread. The model (from Hugging Face) is
 * downloaded once and cached by the browser; speech is generated entirely on this device.
 */
import { KokoroTTS } from 'kokoro-js'

export type KokoroDevice = 'wasm' | 'webgpu'

export type WorkerRequest =
  | { type: 'load'; device: KokoroDevice }
  | { type: 'generate'; id: number; text: string; voice: string }

export type WorkerResponse =
  | { type: 'progress'; loaded: number; total: number }
  | { type: 'ready'; device: KokoroDevice }
  | { type: 'audio'; id: number; samples: Float32Array; sampleRate: number }
  | { type: 'error'; id?: number; message: string }

const MODEL = 'onnx-community/Kokoro-82M-v1.0-ONNX'
const scope = self as unknown as DedicatedWorkerGlobalScope
let tts: Promise<KokoroTTS> | null = null

const post = (m: WorkerResponse, transfer: Transferable[] = []) => scope.postMessage(m, transfer)

function load(device: KokoroDevice): Promise<KokoroTTS> {
  if (tts) return tts
  // Per-file progress, summed into one bar
  const files = new Map<string, { loaded: number; total: number }>()
  tts = KokoroTTS.from_pretrained(MODEL, {
    // q8 is small and fine on the CPU; the GPU path wants full precision
    dtype: device === 'webgpu' ? 'fp32' : 'q8',
    device,
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
  tts.then(
    () => post({ type: 'ready', device }),
    (e) => {
      tts = null
      post({ type: 'error', message: e instanceof Error ? e.message : String(e) })
    },
  )
  return tts
}

scope.onmessage = async (e: MessageEvent<WorkerRequest>) => {
  const msg = e.data
  if (msg.type === 'load') {
    load(msg.device).catch(() => undefined)
    return
  }
  try {
    const model = await (tts ?? load('wasm'))
    // Generated at normal speed; the player changes speed, so cached audio stays valid
    const audio = await model.generate(msg.text, { voice: msg.voice as never, speed: 1 })
    const samples = audio.audio as Float32Array
    post({ type: 'audio', id: msg.id, samples, sampleRate: audio.sampling_rate }, [samples.buffer])
  } catch (err) {
    post({ type: 'error', id: msg.id, message: err instanceof Error ? err.message : String(err) })
  }
}
