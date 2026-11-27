/**
 * Live transcripts of audiobooks (same behavior as Android's AudiobookTranscripts). Turned on per
 * book from its transcript view; then, while the book plays here or its transcript is open,
 * Whisper works a chapter or two ahead of the play position in 5-minute pieces, each saved and
 * synced (kind "abtx") as it finishes.
 *
 * The Mac does it fastest: with the Feeder Mac app (or the dev server), ffmpeg decodes just the
 * stretch needed (/api/ytdl/pcm) and Whisper runs on the GPU. There it also works ahead of where
 * the book was left on another device (its synced position), for books whose transcript is on,
 * so the phone gets the lines without doing the work.
 *
 * Kept in their own IndexedDB database, so the app's main database schema is untouched.
 */
import Dexie, { type EntityTable } from 'dexie'
import { useEffect, useSyncExternalStore } from 'react'
import { apiHeaders } from '../api'
import { db, type Audiobook, type AudioTrack } from '../db'
import { temporaryLink } from '../dropbox'
import { decode16k, setModelProgressListener, transcribePiece } from '../transcripts'
import type { TranscriptCue } from '../transcriptFormats'
import { localTrackFile } from './library'
import { audiobookPlayer } from './player'
import { chaptersOf, trackStartMs } from './timeline'
import { mergePieces, nextTarget, pieceRanges, toPiece, type PieceTarget, type Range, type TranscriptPiece } from './transcriptRules'

interface StoredPiece extends TranscriptPiece {
  /** "<book id>:<start ms>", the sync key */
  key: string
  bookId: string
  synced: 0 | 1
}

interface BookSetting {
  bookId: string
  /** Transcribing ahead turned on (true) or off (false) here; unset follows other devices */
  wanted: boolean
}

class AbTranscriptDb extends Dexie {
  pieces!: EntityTable<StoredPiece, 'key'>
  books!: EntityTable<BookSetting, 'bookId'>
  constructor() {
    super('feeder-audiobook-transcripts')
    this.version(1).stores({ pieces: '&key, bookId, synced', books: '&bookId' })
  }
}

const tdb = new AbTranscriptDb()
const RATE = 16_000
/** Files this long or shorter can be decoded whole in the browser when ffmpeg isn't there. */
const BROWSER_DECODE_MAX_MS = 45 * 60_000
/** On the Mac, books played on another device within this long are transcribed ahead too. */
const OTHER_DEVICE_RECENT_MS = 12 * 3600_000

export const SYNC_KIND = 'abtx'

export interface AbTranscriptState {
  loaded: boolean
  cues: TranscriptCue[]
  ranges: Range[]
  wanted: boolean
  /** Whisper is working on this book, at this book time */
  workingAtMs: number | null
  /** 0..1 while the speech model downloads */
  modelProgress: number | null
  error: string | null
}

const EMPTY: AbTranscriptState = { loaded: false, cues: [], ranges: [], wanted: false, workingAtMs: null, modelProgress: null, error: null }
const states = new Map<string, AbTranscriptState>()
const listeners = new Set<() => void>()

function set(bookId: string, change: Partial<AbTranscriptState>) {
  states.set(bookId, { ...(states.get(bookId) ?? EMPTY), ...change })
  listeners.forEach((l) => l())
}

export function abTranscriptState(bookId: string): AbTranscriptState {
  return states.get(bookId) ?? EMPTY
}

async function piecesOf(bookId: string): Promise<StoredPiece[]> {
  return tdb.pieces.where('bookId').equals(bookId).toArray()
}

/** Whether Whisper should work ahead for the book: turned on here, or (unset here) pieces came from another device. */
async function isWanted(bookId: string, pieces?: StoredPiece[]): Promise<boolean> {
  const setting = await tdb.books.get(bookId)
  if (setting) return setting.wanted
  return (pieces ?? (await piecesOf(bookId))).length > 0
}

async function publish(bookId: string) {
  const pieces = await piecesOf(bookId)
  set(bookId, { loaded: true, cues: mergePieces(pieces), ranges: pieceRanges(pieces), wanted: await isWanted(bookId, pieces) })
}

/** The book's transcript, loaded on first use. */
export function useAbTranscript(bookId: string): AbTranscriptState {
  useEffect(() => {
    if (!abTranscriptState(bookId).loaded) void publish(bookId)
  }, [bookId])
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => abTranscriptState(bookId),
  )
}

export async function setTranscriptWanted(bookId: string, wanted: boolean) {
  await tdb.books.put({ bookId, wanted })
  await publish(bookId)
  if (wanted) kickAhead()
}

/** The transcript view for this book is open (null when closed): it keeps working while paused. */
let viewing: string | null = null
export function setTranscriptViewing(bookId: string | null) {
  viewing = bookId
  if (bookId) kickAhead()
}

// --- Sync --------------------------------------------------------------------------------------

export async function unsyncedPieces(): Promise<{ key: string; v: { e: number; c: TranscriptPiece['c'] } }[]> {
  const rows = await tdb.pieces.where('synced').equals(0).toArray()
  return rows.map((p) => ({ key: p.key, v: { e: p.e, c: p.c } }))
}

export async function markPiecesSent(keys: string[]) {
  if (keys.length) await tdb.pieces.bulkUpdate(keys.map((key) => ({ key, changes: { synced: 1 as const } })))
}

export async function markAllPiecesUnsent() {
  await tdb.pieces.toCollection().modify({ synced: 0 })
}

/** A piece another device made. Returns whether it was new here. */
export async function applyRemotePiece(key: string, v: unknown): Promise<boolean> {
  const cut = key.lastIndexOf(':')
  const bookId = key.slice(0, cut)
  const s = Number(key.slice(cut + 1))
  const val = v as { e?: unknown; c?: unknown } | null
  if (cut <= 0 || !Number.isFinite(s) || !val || typeof val.e !== 'number' || !Array.isArray(val.c)) return false
  if (await tdb.pieces.get(key)) return false
  const c = val.c.filter(
    (r): r is [number, number, string] => Array.isArray(r) && typeof r[0] === 'number' && typeof r[1] === 'number' && typeof r[2] === 'string',
  )
  await tdb.pieces.put({ key, bookId, s, e: val.e, c, synced: 1 })
  if (states.has(bookId)) await publish(bookId)
  return true
}

// --- Transcribing ahead ------------------------------------------------------------------------

let running = false
let serverPcm: boolean | null = null

/** Starts the background loop unless it runs. It stops by itself after a minute with nothing to do. */
export function kickAhead() {
  if (running) return
  running = true
  void runAhead().finally(() => (running = false))
}

audiobookPlayer.subscribe((s) => {
  if (s.status === 'playing') kickAhead()
})

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

interface Work {
  book: Audiobook
  target: PieceTarget
}

/** What to transcribe next: the book playing here (or open), else on the Mac a book played elsewhere. */
async function nextWork(): Promise<Work | null> {
  const s = audiobookPlayer.state
  const here = audiobookPlayer.book
  if (here && s.bookId === here.id && (s.status === 'playing' || viewing === here.id)) {
    const pieces = await piecesOf(here.id)
    if (await isWanted(here.id, pieces)) {
      const target = nextTarget(
        here.tracks.map((t) => (t.duration ?? 0) * 1000),
        s.chapters,
        pieces,
        s.bookMs,
      )
      if (target) return { book: here, target }
    }
    if (s.status === 'playing') return null
  }
  // Only where ffmpeg is (the Mac app), so an ordinary tab doesn't work the GPU on its own
  if (serverPcm !== true) return null
  const since = Date.now() - OTHER_DEVICE_RECENT_MS
  const recent = (await db.audioPos.toArray()).filter((p) => p.ts > since).sort((a, b) => b.ts - a.ts)
  for (const pos of recent) {
    const book = await db.audiobooks.get(pos.id)
    if (!book || book.tracks.some((t) => t.local)) continue
    const pieces = await piecesOf(book.id)
    if (!(await isWanted(book.id, pieces))) continue
    const i = pos.h ? Math.max(0, book.tracks.findIndex((t) => t.hash === pos.h)) : pos.i
    const target = nextTarget(
      book.tracks.map((t) => (t.duration ?? 0) * 1000),
      chaptersOf(book),
      pieces,
      trackStartMs(book, i) + pos.ms,
    )
    if (target) return { book, target }
  }
  return null
}

async function runAhead() {
  let idleSince = Date.now()
  for (;;) {
    let work: Work | null = null
    try {
      work = await nextWork()
    } catch (e) {
      console.warn('Audiobook transcript: picking work failed', e)
    }
    if (!work) {
      if (Date.now() - idleSince > 60_000) return
      await sleep(2000)
      continue
    }
    idleSince = Date.now()
    await transcribe(work.book, work.target)
  }
}

let lastDecoded: { hash: string; samples: Float32Array } | null = null

/** 16 kHz mono samples of file t from startMs to endMs: ffmpeg on the Mac, else decoded here. */
async function samplesFor(t: AudioTrack, startMs: number, endMs: number): Promise<Float32Array> {
  if (!t.local && serverPcm !== false) {
    const link = await temporaryLink(t.path)
    const q = new URLSearchParams({ url: link, start: String(Math.round(startMs)), dur: String(Math.round(endMs - startMs)) })
    try {
      const res = await fetch(`/api/ytdl/pcm?${q}`, { headers: apiHeaders() })
      if (res.ok) {
        serverPcm = true
        return new Float32Array(await res.arrayBuffer())
      }
      if (res.status !== 404 && res.status !== 503) throw new Error(`ffmpeg: HTTP ${res.status}`)
      serverPcm = false
    } catch (e) {
      if (serverPcm) throw e
      serverPcm = false
    }
  }
  if ((t.duration ?? Infinity) * 1000 > BROWSER_DECODE_MAX_MS) {
    throw new Error('long files need the Feeder Mac app, which decodes them with ffmpeg')
  }
  if (lastDecoded?.hash !== t.hash) {
    lastDecoded = null
    let bytes: ArrayBuffer
    if (t.local) {
      const file = await localTrackFile(t)
      if (!file) throw new Error(`${t.name} is not on this device`)
      bytes = await file.arrayBuffer()
    } else {
      const res = await fetch(await temporaryLink(t.path))
      if (!res.ok) throw new Error(`Dropbox: HTTP ${res.status}`)
      bytes = await res.arrayBuffer()
    }
    lastDecoded = { hash: t.hash, samples: await decode16k(bytes) }
  }
  const all = lastDecoded.samples
  return all.slice(Math.round((startMs / 1000) * RATE), Math.min(all.length, Math.round((endMs / 1000) * RATE)))
}

async function transcribe(book: Audiobook, target: PieceTarget) {
  const t = book.tracks[target.i]
  set(book.id, { workingAtMs: target.bookStartMs, error: null })
  try {
    const samples = await samplesFor(t, target.startMs, target.endMs)
    if (samples.length < RATE / 4) throw new Error(`no sound in ${t.name}`)
    // The file ended first (its length is rounded): the piece still reaches its end
    const want = Math.round(((target.endMs - target.startMs) / 1000) * RATE)
    const endMs = samples.length < want - RATE / 10 ? target.endMs : target.startMs + Math.round((samples.length / RATE) * 1000)
    setModelProgressListener((f) => set(book.id, { modelProgress: f < 1 ? f : null }))
    let cues: TranscriptCue[]
    try {
      cues = await transcribePiece(samples.slice(), target.startMs)
    } catch (e) {
      if ((e as { retry?: boolean }).retry) cues = await transcribePiece(samples, target.startMs)
      else throw e
    }
    const piece = toPiece(target, Math.max(endMs, target.startMs + 1), cues)
    const key = `${book.id}:${piece.s}`
    if (!(await tdb.pieces.get(key))) await tdb.pieces.put({ ...piece, key, bookId: book.id, synced: 0 })
    await publish(book.id)
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e)
    console.warn('Audiobook transcript failed', message)
    set(book.id, { error: message === 'stopped' ? null : message })
    await sleep(message === 'stopped' ? 1000 : 15_000)
  } finally {
    setModelProgressListener(null)
    set(book.id, { workingAtMs: null, modelProgress: null })
  }
}
