/**
 * Natural voice that runs in the browser (Kokoro, 82M parameters, Apache-2.0) via a worker.
 * English only. Voice ids are stored in settings as "kokoro:<voice>".
 */
import type { KokoroDevice, WorkerRequest, WorkerResponse } from './kokoro.worker'

export const KOKORO_PREFIX = 'kokoro:'

/** The better-rated voices from the model card, best first. */
export const KOKORO_VOICES: { id: string; label: string }[] = [
  { id: 'af_heart', label: 'Heart (US, female)' },
  { id: 'af_bella', label: 'Bella (US, female)' },
  { id: 'af_nicole', label: 'Nicole (US, female, soft)' },
  { id: 'am_fenrir', label: 'Fenrir (US, male)' },
  { id: 'am_michael', label: 'Michael (US, male)' },
  { id: 'am_puck', label: 'Puck (US, male)' },
  { id: 'bf_emma', label: 'Emma (UK, female)' },
  { id: 'bf_isabella', label: 'Isabella (UK, female)' },
  { id: 'bm_george', label: 'George (UK, male)' },
  { id: 'bm_fable', label: 'Fable (UK, male)' },
]

export const isKokoroVoice = (voiceURI: string) => voiceURI.startsWith(KOKORO_PREFIX)

export interface KokoroStatus {
  state: 'idle' | 'loading' | 'ready' | 'error'
  progress: number // 0..1 while loading
  device: KokoroDevice | null
  error: string | null
}

export interface KokoroAudio {
  samples: Float32Array
  sampleRate: number
}

type Listener = (s: KokoroStatus) => void

export const webGpuAvailable = () => typeof navigator !== 'undefined' && 'gpu' in navigator

class KokoroClient {
  private worker: Worker | null = null
  private nextId = 1
  private pending = new Map<number, { resolve: (a: KokoroAudio) => void; reject: (e: Error) => void }>()
  private listeners = new Set<Listener>()
  private readyWaiters: { resolve: () => void; reject: (e: Error) => void }[] = []
  status: KokoroStatus = { state: 'idle', progress: 0, device: null, error: null }

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    l(this.status)
    return () => this.listeners.delete(l)
  }

  private set(patch: Partial<KokoroStatus>) {
    this.status = { ...this.status, ...patch }
    this.listeners.forEach((l) => l(this.status))
  }

  /** Starts the one-time model download (or loads it from the browser cache). */
  load(useGpu: boolean): Promise<void> {
    const device: KokoroDevice = useGpu && webGpuAvailable() ? 'webgpu' : 'wasm'
    if (this.status.state === 'ready' && this.status.device === device) return Promise.resolve()
    if (this.status.state === 'ready' || (this.status.state === 'loading' && this.status.device !== device)) {
      // Switching between CPU and GPU needs a fresh model
      this.reset()
    }
    const wait = new Promise<void>((resolve, reject) => this.readyWaiters.push({ resolve, reject }))
    if (this.status.state !== 'loading') {
      this.set({ state: 'loading', progress: 0, device, error: null })
      this.ensureWorker().postMessage({ type: 'load', device } satisfies WorkerRequest)
    }
    return wait
  }

  generate(text: string, voice: string): Promise<KokoroAudio> {
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ensureWorker().postMessage({ type: 'generate', id, text, voice } satisfies WorkerRequest)
    })
  }

  private reset() {
    this.worker?.terminate()
    this.worker = null
    const err = new Error('Voice model restarted')
    this.pending.forEach((p) => p.reject(err))
    this.pending.clear()
    this.readyWaiters.forEach((w) => w.reject(err))
    this.readyWaiters = []
    this.set({ state: 'idle', progress: 0, device: null, error: null })
  }

  private ensureWorker(): Worker {
    if (this.worker) return this.worker
    const w = new Worker(new URL('./kokoro.worker.ts', import.meta.url), { type: 'module' })
    w.onmessage = (e: MessageEvent<WorkerResponse>) => {
      const m = e.data
      if (m.type === 'progress') {
        this.set({ progress: m.total ? Math.min(1, m.loaded / m.total) : 0 })
      } else if (m.type === 'ready') {
        this.set({ state: 'ready', progress: 1, device: m.device })
        this.readyWaiters.forEach((r) => r.resolve())
        this.readyWaiters = []
      } else if (m.type === 'audio') {
        this.pending.get(m.id)?.resolve({ samples: m.samples, sampleRate: m.sampleRate })
        this.pending.delete(m.id)
      } else if (m.type === 'error') {
        const err = new Error(m.message)
        if (m.id !== undefined) {
          this.pending.get(m.id)?.reject(err)
          this.pending.delete(m.id)
        } else {
          this.set({ state: 'error', error: m.message })
          this.readyWaiters.forEach((r) => r.reject(err))
          this.readyWaiters = []
        }
      }
    }
    w.onerror = (e) => {
      const err = new Error(e.message || 'Voice worker failed')
      this.set({ state: 'error', error: err.message })
      this.pending.forEach((p) => p.reject(err))
      this.pending.clear()
      this.readyWaiters.forEach((r) => r.reject(err))
      this.readyWaiters = []
    }
    this.worker = w
    return w
  }
}

export const kokoro = new KokoroClient()

/** 16-bit PCM WAV, so the audio plays in a normal <audio> element (speed, media keys). */
export function wavBlob({ samples, sampleRate }: KokoroAudio): Blob {
  const buf = new ArrayBuffer(44 + samples.length * 2)
  const v = new DataView(buf)
  const w = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)))
  w(0, 'RIFF')
  v.setUint32(4, 36 + samples.length * 2, true)
  w(8, 'WAVE')
  w(12, 'fmt ')
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, 1, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * 2, true)
  v.setUint16(32, 2, true)
  v.setUint16(34, 16, true)
  w(36, 'data')
  v.setUint32(40, samples.length * 2, true)
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true)
  }
  return new Blob([buf], { type: 'audio/wav' })
}
