/**
 * Podcast transcripts for the web app, shown as subtitles that follow playback (same behavior as
 * the Android app):
 *
 * 1. The feed's own transcript (Podcasting 2.0 `podcast:transcript`: VTT, SRT or JSON).
 * 2. Otherwise, on request, speech to text in this browser with Whisper (whisper.worker.ts): no
 *    account, no cost, the audio stays on this device. Long episodes go in pieces (first 30 s,
 *    then 5 minutes), each saved as it finishes, so lines appear while it runs and a stop carries
 *    on from the last piece.
 *
 * Kept in their own IndexedDB database, so the app's main database schema is untouched.
 */
import Dexie, { type EntityTable } from 'dexie'
import { useSyncExternalStore } from 'react'
import { apiHeaders, proxyFetch } from './api'
import type { Item } from './db'
import { bestTranscriptLink, findTranscriptLinks, parseTranscript, type TranscriptCue } from './transcriptFormats'
import type { WhisperDevice, WhisperRequest, WhisperResponse } from './whisper.worker'

export type TranscriptSource = 'feed' | 'browser'
export type TranscriptStatus = 'unknown' | 'checking' | 'none' | 'ready' | 'loading-model' | 'getting-audio' | 'transcribing' | 'paused' | 'failed'

export interface TranscriptState {
  status: TranscriptStatus
  cues: TranscriptCue[]
  source: TranscriptSource | null
  /** 0..1 for downloads and transcribing */
  progress: number
  doneMs: number
  durationMs: number
  error: string | null
}

interface StoredTranscript {
  itemId: string
  source: TranscriptSource
  cues: TranscriptCue[]
  complete: boolean
  doneMs: number
  durationMs: number
}

class TranscriptDb extends Dexie {
  transcripts!: EntityTable<StoredTranscript, 'itemId'>
  constructor() {
    super('feeder-transcripts')
    this.version(1).stores({ transcripts: 'itemId' })
  }
}

const tdb = new TranscriptDb()
const RATE = 16_000
// Under one 30 s Whisper window with the carry, so the first lines come quickly and cleanly
const FIRST_PIECE = 27 * RATE
const PIECE = 5 * 60 * RATE
const MAX_CARRY = 2 * RATE
/** About how big the Whisper model download is, for the button's note. */
export const WHISPER_MODEL_MB = 80

const EMPTY: TranscriptState = { status: 'unknown', cues: [], source: null, progress: 0, doneMs: 0, durationMs: 0, error: null }
const states = new Map<string, TranscriptState>()
const listeners = new Set<() => void>()

function set(id: string, change: Partial<TranscriptState>) {
  states.set(id, { ...(states.get(id) ?? EMPTY), ...change })
  listeners.forEach((l) => l())
}

export function transcriptState(id: string): TranscriptState {
  return states.get(id) ?? EMPTY
}

export function useTranscript(id: string): TranscriptState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => transcriptState(id),
  )
}

/** Finds the episode's transcript: saved in this browser, else in its feed. Does nothing if already known. */
export async function loadTranscript(item: Pick<Item, 'id' | 'feedUrl' | 'guid' | 'enclosureUrl'>): Promise<void> {
  const status = transcriptState(item.id).status
  if (status !== 'unknown' && status !== 'failed') return
  set(item.id, { status: 'checking', error: null })
  try {
    const stored = await tdb.transcripts.get(item.id)
    if (stored) {
      set(item.id, {
        status: stored.complete ? 'ready' : 'paused',
        cues: stored.cues,
        source: stored.source,
        doneMs: stored.doneMs,
        durationMs: stored.durationMs,
        progress: stored.durationMs ? stored.doneMs / stored.durationMs : 0,
      })
      return
    }
    const cues = await fromFeed(item)
    if (cues.length) {
      await tdb.transcripts.put({ itemId: item.id, source: 'feed', cues, complete: true, doneMs: 0, durationMs: 0 })
      set(item.id, { status: 'ready', cues, source: 'feed' })
    } else set(item.id, { status: 'none' })
  } catch (e) {
    // No feed transcript reachable (offline…): making one here still works
    set(item.id, { status: 'none', error: e instanceof Error ? e.message : String(e) })
  }
}

async function fromFeed(item: Pick<Item, 'feedUrl' | 'guid' | 'enclosureUrl'>): Promise<TranscriptCue[]> {
  const feed = await proxyFetch(item.feedUrl)
  const link = bestTranscriptLink(findTranscriptLinks(feed.text, item.guid, item.enclosureUrl ?? null))
  if (!link) return []
  const file = await proxyFetch(link.url)
  return parseTranscript(file.text, link.type, link.url)
}

/** Forgets the transcript (one made here can then be made again). */
export async function deleteTranscript(item: Pick<Item, 'id' | 'feedUrl' | 'guid' | 'enclosureUrl'>) {
  if (running?.id === item.id) stopTranscribing()
  await tdb.transcripts.delete(item.id)
  states.delete(item.id)
  listeners.forEach((l) => l())
  await loadTranscript(item)
}

// --- Speech to text --------------------------------------------------------------------------

let worker: Worker | null = null
let nextId = 1
const pending = new Map<number, { resolve: (c: TranscriptCue[]) => void; reject: (e: Error) => void; offsetMs: number }>()
export let onModelProgress: ((fraction: number) => void) | null = null
/** For audiobook transcripts: the model download's progress while no episode is being done. */
export function setModelProgressListener(l: ((fraction: number) => void) | null) {
  onModelProgress = l
}
let running: { id: string; stop: boolean } | null = null

function getWorker(): Worker {
  if (worker) return worker
  const w = new Worker(new URL('./whisper.worker.ts', import.meta.url), { type: 'module' })
  w.onmessage = (e: MessageEvent<WhisperResponse>) => {
    const m = e.data
    if (m.type === 'progress') onModelProgress?.(m.total ? Math.min(1, m.loaded / m.total) : 0)
    else if (m.type === 'ready') {
      if (running && transcriptState(running.id).status === 'loading-model') set(running.id, { status: 'transcribing', progress: 0 })
    } else if (m.type === 'result') {
      const p = pending.get(m.id)
      pending.delete(m.id)
      p?.resolve(chunksToCues(m.chunks, p.offsetMs))
    } else if (m.type === 'error') {
      const p = m.id != null ? pending.get(m.id) : undefined
      if (m.id != null) pending.delete(m.id)
      p?.reject(new Error(m.message))
    }
  }
  worker = w
  return w
}

/**
 * Whisper's chunks to cues; its markers for silence and music are not speech. Pieces of one
 * sentence (Whisper often splits at a pause) are joined into one line.
 */
export function chunksToCues(chunks: { start: number; end: number | null; text: string }[], offsetMs: number): TranscriptCue[] {
  const cues = chunks
    .map((c) => ({ startMs: offsetMs + Math.round(c.start * 1000), endMs: offsetMs + Math.round((c.end ?? c.start + 2) * 1000), text: c.text.trim() }))
    .filter((c) => c.text && !/^[[(].*[\])]$/.test(c.text))
  const lines: TranscriptCue[] = []
  for (const cue of cues) {
    const last = lines[lines.length - 1]
    const words = (t: string) => t.split(/\s+/).length
    if (last && !/[.?!…"”]$/.test(last.text) && cue.startMs - last.endMs < 1000 && words(last.text) + words(cue.text) <= 30) {
      lines[lines.length - 1] = { ...last, endMs: cue.endMs, text: `${last.text} ${cue.text}` }
    } else lines.push(cue)
  }
  return lines
}

let device: WhisperDevice | null = null

export function transcribePiece(samples: Float32Array, offsetMs: number): Promise<TranscriptCue[]> {
  const id = nextId++
  const chosen: WhisperDevice = device ?? ('gpu' in navigator ? 'webgpu' : 'wasm')
  return new Promise<TranscriptCue[]>((resolve, reject) => {
    pending.set(id, { resolve, reject, offsetMs })
    const msg: WhisperRequest = { type: 'transcribe', id, samples, device: chosen }
    getWorker().postMessage(msg, [samples.buffer])
  }).then(
    (cues) => {
      device = chosen
      return cues
    },
    (e: Error) => {
      // No usable GPU after all: the CPU path is slower but works everywhere
      if (chosen === 'webgpu' && !device) {
        worker?.terminate()
        worker = null
        device = 'wasm'
        return Promise.reject(Object.assign(e, { retry: true }))
      }
      throw e
    },
  )
}

/** The start of the quietest 100 ms between `from` and `to`, plus half of it. */
export function quietestCut(samples: Float32Array, from: number, to: number): number {
  const window = RATE / 10
  let best = from
  let bestEnergy = Infinity
  for (let start = from; start + window <= to; start += window / 2) {
    let energy = 0
    for (let i = start; i < start + window; i++) energy += Math.abs(samples[i])
    if (energy < bestEnergy) {
      bestEnergy = energy
      best = start
    }
  }
  return best + window / 2
}

/** The episode's audio, straight from its host when it allows it, else through this app's server. */
async function fetchAudio(url: string, onProgress: (f: number) => void): Promise<ArrayBuffer> {
  let res: Response
  try {
    res = await fetch(url)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
  } catch {
    res = await fetch(`/api/audio?url=${encodeURIComponent(url)}`, { headers: apiHeaders() })
    if (!res.ok) throw new Error(`Could not get the episode audio (HTTP ${res.status})`)
  }
  const total = Number(res.headers.get('content-length') ?? 0)
  if (!res.body || !total) return res.arrayBuffer()
  const reader = res.body.getReader()
  const parts: Uint8Array[] = []
  let got = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    parts.push(value)
    got += value.byteLength
    onProgress(Math.min(1, got / total))
  }
  const all = new Uint8Array(got)
  let at = 0
  for (const p of parts) {
    all.set(p, at)
    at += p.byteLength
  }
  return all.buffer
}

/** Decodes to 16 kHz mono, what Whisper reads. */
export async function decode16k(bytes: ArrayBuffer): Promise<Float32Array> {
  const ctx = new OfflineAudioContext(1, 1, RATE)
  const audio = await ctx.decodeAudioData(bytes)
  if (audio.numberOfChannels === 1) return audio.getChannelData(0)
  const mono = new Float32Array(audio.length)
  for (let c = 0; c < audio.numberOfChannels; c++) {
    const data = audio.getChannelData(c)
    for (let i = 0; i < mono.length; i++) mono[i] += data[i] / audio.numberOfChannels
  }
  return mono
}

/** Transcribes the episode in this browser, or carries on where it stopped. One at a time. */
export async function transcribe(item: Pick<Item, 'id' | 'enclosureUrl'>): Promise<void> {
  if (!item.enclosureUrl) return
  if (running && running.id !== item.id) stopTranscribing()
  if (running) return
  const job = { id: item.id, stop: false }
  running = job
  const stored = await tdb.transcripts.get(item.id)
  const cues = stored?.source === 'browser' ? [...stored.cues] : []
  let doneMs = stored?.source === 'browser' ? stored.doneMs : 0
  try {
    set(item.id, { status: 'getting-audio', source: 'browser', cues, progress: 0, error: null })
    const bytes = await fetchAudio(item.enclosureUrl, (f) => set(item.id, { progress: f }))
    if (job.stop) throw new Error('stopped')
    const samples = await decode16k(bytes)
    const durationMs = Math.round((samples.length / RATE) * 1000)
    let start = Math.round((doneMs / 1000) * RATE)
    set(item.id, { status: 'loading-model', durationMs, doneMs, progress: 0 })
    onModelProgress = (f) => {
      if (transcriptState(item.id).status === 'loading-model') set(item.id, { progress: f })
    }
    while (start < samples.length - RATE / 4) {
      if (job.stop) throw new Error('stopped')
      const target = start === 0 ? FIRST_PIECE : PIECE
      const end = start + target + MAX_CARRY >= samples.length ? samples.length : quietestCut(samples, start + target, start + target + MAX_CARRY)
      const offsetMs = Math.round((start / RATE) * 1000)
      let piece: TranscriptCue[]
      try {
        piece = await transcribePiece(samples.slice(start, end), offsetMs)
      } catch (e) {
        if ((e as { retry?: boolean }).retry) piece = await transcribePiece(samples.slice(start, end), offsetMs)
        else throw e
      }
      if (job.stop) throw new Error('stopped')
      cues.push(...piece)
      start = end
      doneMs = Math.round((end / RATE) * 1000)
      await tdb.transcripts.put({ itemId: item.id, source: 'browser', cues: [...cues], complete: false, doneMs, durationMs })
      set(item.id, { status: 'transcribing', cues: [...cues], doneMs, progress: Math.min(1, doneMs / durationMs) })
    }
    await tdb.transcripts.put({ itemId: item.id, source: 'browser', cues: [...cues], complete: true, doneMs: durationMs, durationMs })
    set(item.id, { status: 'ready', cues: [...cues], doneMs: durationMs, progress: 1 })
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    if (job.stop || message === 'stopped') set(item.id, { status: cues.length ? 'paused' : 'none' })
    else set(item.id, { status: 'failed', error: message })
  } finally {
    if (running === job) running = null
    onModelProgress = null
  }
}

export function stopTranscribing() {
  if (!running) return
  running.stop = true
  // The piece in progress can't be interrupted inside the model: a fresh worker drops it
  worker?.terminate()
  worker = null
  for (const p of pending.values()) p.reject(new Error('stopped'))
  pending.clear()
}

/** Every transcript saved in this browser, as plain lines by item id, for global search. */
export async function storedTranscriptLines(): Promise<Map<string, { startMs: number; text: string }[]>> {
  const all = await tdb.transcripts.toArray()
  return new Map(all.map((t) => [t.itemId, t.cues.map((c) => ({ startMs: c.startMs, text: c.text }))]))
}
