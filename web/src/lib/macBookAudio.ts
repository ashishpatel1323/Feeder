/**
 * Mac app only: ebook chapters for the phone, spoken here with the natural voice (Kokoro, on the
 * graphics card) and put in Dropbox, so the phone downloads them on Wi-Fi instead of making them
 * with its own voice (Android model/ebooks/MacBookAudio.kt).
 *
 * While the app is open (and Settings › Prepared on this Mac is on), every minute it:
 *  - picks the Dropbox ebooks opened on any device in the last MAC_BOOK_DAYS days (synced positions),
 *  - keeps the chapter being read and the phone's Prepare ahead count after it ready in
 *    /Ebooks/.feeder-audio/<book>/s<N>.m4a + s<N>.json (the phone's own chapter meta, with
 *    "source": "mac"), one chapter at a time,
 *  - deletes chapters read past (on any device) and books finished or not opened for
 *    MAC_STALE_DAYS days,
 *  - writes mac.json (when it was last seen, what it's doing, each book's chapters), which the
 *    phone shows in its Offline audio sheet.
 *
 * Chapter text is made exactly as the phone's reader makes it (ebook-android/textindex.ts), so the
 * phone can check the audio is for its text (Java String.hashCode, textLength).
 */
import { useEffect, useState } from 'react'
import { indexText } from '../ebook-android/textindex'
import { db } from './db'
import { getDropbox, listFolder, listSubfolders, putFile, readTextFile, deleteFile, EBOOKS_FOLDER } from './dropbox'
import { bookFile } from './ebooks/library'
import { kokoro, KOKORO_PREFIX } from './kokoro'
import { chunkForKokoro, splitSentences } from './readaloud'
import { getSettings } from './settings'

export const MAC_AUDIO_FOLDER = `${EBOOKS_FOLDER}/.feeder-audio`
/** Prepare ahead: every chapter to the end of the book */
export const WHOLE_BOOK = 1000
/** Books opened on any device this recently are kept ready */
export const MAC_BOOK_DAYS = 7
/** A book's audio goes when it hasn't been opened for this long */
export const MAC_STALE_DAYS = 14
const DAY = 86400_000
const TICK_MS = 60_000
const HEARTBEAT_MS = 2 * 60_000
const DEFAULT_VOICE = 'af_heart'
/** Silence between sentences, as read aloud pauses */
const GAP_SECONDS = 0.08

export interface Span {
  s: number
  e: number
  a: number
  b: number
}

/** What goes next to a chapter's audio: the phone's own AudioMeta (BookAudio.kt) plus where it came from. */
export interface ChapterMeta {
  textHash: number
  textLength: number
  lang: string
  durationMillis: number
  segments: Span[]
  words: Span[]
  from: number
  source: 'mac'
  voice: string
}

export interface MacBookStatus {
  id: string
  title: string
  /** Chapter (section index) being read */
  current: number
  /** Chapters kept ready in Dropbox */
  ready: number[]
  /** Chapters that should be ready (the window) */
  planned: number[]
  bytes: number
  /** When it was last opened on any device */
  usedAt: number
}

/** mac.json: read by the phone for its Offline audio sheet. */
export interface MacStatus {
  seen: number
  voice: string
  working: { book: string; title: string; section: number; label: string; progress: number } | null
  books: MacBookStatus[]
  /** Audiobook transcripts made here (audiobooks/transcripts.ts) */
  transcripts?: import('./audiobooks/transcripts').MacTranscriptStatus
}

/** phone.json: the phone's Prepare ahead setting, so the Mac keeps the same window. */
export interface PhonePrefs {
  ahead: number
  /** Audiobook transcripts: chapters to transcribe ahead (WHOLE_BOOK: all) */
  transcribeAhead?: number
  at: number
}

export interface MacRunState {
  available: boolean
  enabled: boolean
  status: MacStatus | null
  error: string
}

/** Java's String.hashCode, which the phone compares chapter text with. */
export function javaHash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h
}

/** Section index of an EPUB CFI ("epubcfi(/6/14!/4/2:0)" → 6), or -1. */
export function sectionOfCfi(cfi: string): number {
  const m = /^epubcfi\(\/6\/(\d+)/.exec(cfi)
  return m ? Number(m[1]) / 2 - 1 : -1
}

/** The ebooks to keep ready: in Dropbox, opened in the last MAC_BOOK_DAYS days, newest first. */
export function booksToPrepare(positions: { id: string; ts: number; f: number }[], inDropbox: Set<string>, now = Date.now()): string[] {
  return positions
    .filter((p) => inDropbox.has(p.id) && now - p.ts <= MAC_BOOK_DAYS * DAY && p.f < 0.999)
    .sort((a, b) => b.ts - a.ts)
    .map((p) => p.id)
}

/** Book folders in Dropbox to delete: finished, not opened for MAC_STALE_DAYS days, or gone. */
export function staleBookFolders(folders: string[], positions: Map<string, { ts: number; f: number }>, now = Date.now()): string[] {
  return folders.filter((id) => {
    const p = positions.get(id)
    return !p || p.f >= 0.999 || now - p.ts > MAC_STALE_DAYS * DAY
  })
}

/**
 * The chapters to keep ready: [current] (when it has text) and [ahead] chapters with text after it
 * (all of them for WHOLE_BOOK). [readable] lists the sections with text, in order.
 */
export function macWindow(readable: number[], current: number, ahead: number): number[] {
  const after = readable.filter((i) => i > current)
  return [...(readable.includes(current) ? [current] : []), ...(ahead >= WHOLE_BOOK ? after : after.slice(0, ahead))]
}

/** Chapter files in a book's folder that are out of the window (read past, or beyond it): to delete. */
export function chapterFilesToDelete(names: string[], keep: Set<number>): string[] {
  return names.filter((n) => {
    const m = /^s(\d+)\.(m4a|json)$/.exec(n)
    return !!m && !keep.has(Number(m[1]))
  })
}

/** Chapters with both their audio and meta in a book's folder. */
export function readyChapters(names: string[]): number[] {
  const set = new Set(names)
  return [...set]
    .map((n) => /^s(\d+)\.json$/.exec(n))
    .filter((m): m is RegExpExecArray => !!m && set.has(`s${m[1]}.m4a`))
    .map((m) => Number(m[1]))
    .sort((a, b) => a - b)
}

/** The pieces the voice is asked for, with where they are in [text]: sentences, long ones split. */
export function pieceOffsets(text: string, lang: string): { start: number; end: number }[] {
  const out: { start: number; end: number }[] = []
  for (const s of splitSentences(text, lang)) {
    for (const c of chunkForKokoro(text.slice(s.start, s.end))) out.push({ start: s.start + c.start, end: s.start + c.end })
  }
  return out
}

/** Word timings in one sentence, spread by word length (as the phone does for voices that report none). */
export function estimateWords(text: string, seg: Span): Span[] {
  const duration = seg.b - seg.a
  if (duration <= 0) return []
  const words = [...text.slice(seg.s, seg.e).matchAll(/\S+/g)]
  if (!words.length) return []
  const weights = words.map((w) => w[0].length + 1)
  const total = weights.reduce((x, y) => x + y, 0)
  let elapsed = 0
  return words.map((w, i) => {
    const a = seg.a + Math.floor((duration * elapsed) / total)
    elapsed += weights[i]
    const b = seg.a + Math.floor((duration * elapsed) / total)
    return { s: seg.s + w.index!, e: seg.s + w.index! + w[0].length, a, b }
  })
}

/** 16-bit mono WAV of [samples]. */
export function wavBlob(samples: Float32Array, sampleRate: number): Blob {
  const buf = new ArrayBuffer(44 + samples.length * 2)
  const v = new DataView(buf)
  const str = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)))
  str(0, 'RIFF')
  v.setUint32(4, 36 + samples.length * 2, true)
  str(8, 'WAVEfmt ')
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true)
  v.setUint16(22, 1, true)
  v.setUint32(24, sampleRate, true)
  v.setUint32(28, sampleRate * 2, true)
  v.setUint16(32, 2, true)
  v.setUint16(34, 16, true)
  str(36, 'data')
  v.setUint32(40, samples.length * 2, true)
  for (let i = 0; i < samples.length; i++) {
    const x = Math.max(-1, Math.min(1, samples[i]))
    v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true)
  }
  return new Blob([buf], { type: 'audio/wav' })
}

const appHeaders = { 'x-feeder-app': '1' }

interface OpenBook {
  title: string
  lang: string
  sections: { linear?: string; createDocument?: () => Promise<Document> }[]
  resolveCFI?: (cfi: string) => { index: number } | undefined
  texts: Map<number, string | null>
}

type Listener = (s: MacRunState) => void

class MacBookAudio {
  state: MacRunState = { available: false, enabled: false, status: null, error: '' }
  /** The phone's settings (phone.json), read every round */
  phone: PhonePrefs | null = null
  private listeners = new Set<Listener>()
  private running = false
  private started = false
  private books = new Map<string, OpenBook>()
  private lastBeat = 0
  private lastCleanup = 0
  private working: MacStatus['working'] = null

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    l(this.state)
    return () => this.listeners.delete(l)
  }

  private emit(patch: Partial<MacRunState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l(this.state))
  }

  /** Starts the background work when this is the Mac app (once per page). */
  async start() {
    if (this.started) return
    this.started = true
    const available = await fetch('/api/bookaudio/status', { headers: appHeaders })
      .then((r) => (r.ok ? (r.json() as Promise<{ available?: boolean }>) : null))
      .then((j) => !!j?.available)
      .catch(() => false)
    this.emit({ available, enabled: available && (await getSettings()).macBookAudio })
    if (!available) return
    const loop = async () => {
      await this.tick()
      window.setTimeout(loop, TICK_MS)
    }
    void loop()
  }

  /** Settings › Prepared on this Mac: the switch. */
  setEnabled(on: boolean) {
    this.emit({ enabled: this.state.available && on })
    if (on) void this.tick()
  }

  private async tick() {
    if (this.running || !this.state.enabled) return
    this.running = true
    try {
      if (!(await getDropbox())) {
        this.emit({ error: 'Sign in to Dropbox (Ebooks) to make audio for your phone' })
        return
      }
      // One chapter per round: go again straight away while there's more to make
      while (this.state.enabled && (await this.round())) {
        /* next chapter */
      }
      this.emit({ error: '' })
    } catch (e) {
      console.warn('Audio for the phone stopped', e)
      this.emit({ error: e instanceof Error ? e.message : String(e) })
    } finally {
      this.running = false
    }
  }

  /** Looks at every book, tidies Dropbox and makes one missing chapter. True when it made one. */
  private async round(): Promise<boolean> {
    const settings = (await getSettings())
    const voice = settings.readAloudVoice.startsWith(KOKORO_PREFIX) ? settings.readAloudVoice.slice(KOKORO_PREFIX.length) : DEFAULT_VOICE
    const phone = await readTextFile(`${MAC_AUDIO_FOLDER}/phone.json`)
      .then((t) => (t ? (JSON.parse(t) as PhonePrefs) : null))
      .catch(() => null)
    this.phone = phone
    const ahead = phone?.ahead && phone.ahead > 0 ? phone.ahead : settings.bookAudioAhead
    const positions = await db.bookPos.toArray()
    const library = await db.books.toArray()
    const inDropbox = new Set(library.filter((b) => b.dropboxPath).map((b) => b.id))
    const now = Date.now()

    if (now - this.lastCleanup > 60 * 60_000) {
      this.lastCleanup = now
      const byId = new Map(positions.map((p) => [p.id, p]))
      for (const id of staleBookFolders(await listSubfolders(MAC_AUDIO_FOLDER), byId, now)) await deleteFile(`${MAC_AUDIO_FOLDER}/${id}`)
    }

    const books: MacBookStatus[] = []
    let todo: { id: string; section: number; book: OpenBook } | null = null
    for (const id of booksToPrepare(positions, inDropbox, now)) {
      const pos = positions.find((p) => p.id === id)!
      const book = await this.open(id).catch((e) => {
        console.warn('Could not open book for audio', id, e)
        return null
      })
      if (!book) continue
      const current = book.resolveCFI?.(pos.cfi)?.index ?? sectionOfCfi(pos.cfi)
      if (current < 0) continue
      const planned = macWindow(await this.readable(book, current, ahead), current, ahead)
      const folder = `${MAC_AUDIO_FOLDER}/${id}`
      const files = await listFolder(folder)
      const keep = new Set(planned)
      for (const name of chapterFilesToDelete(files.map((f) => f.name), keep)) await deleteFile(`${folder}/${name}`)
      const kept = files.filter((f) => /^s(\d+)\./.test(f.name) && keep.has(Number(/^s(\d+)/.exec(f.name)![1])))
      const ready = readyChapters(kept.map((f) => f.name))
      books.push({ id, title: book.title, current, ready, planned, bytes: kept.reduce((n, f) => n + f.size, 0), usedAt: pos.ts })
      const missing = planned.find((i) => !ready.includes(i))
      if (!todo && missing !== undefined) todo = { id, section: missing, book }
    }

    this.working = null
    await this.beat(voice, books, !todo)
    if (!todo) return false
    await this.make(todo.id, todo.book, todo.section, voice, books, settings.readAloudKokoroUseGpu)
    return true
  }

  private async beat(voice: string, books: MacBookStatus[], force: boolean) {
    const transcripts = await import('./audiobooks/transcripts').then((m) => m.macTranscriptStatus()).catch(() => undefined)
    const status: MacStatus = { seen: Date.now(), voice, working: this.working, books, ...(transcripts ? { transcripts } : {}) }
    this.emit({ status })
    if (!force && status.seen - this.lastBeat < HEARTBEAT_MS) return
    this.lastBeat = status.seen
    await putFile(`${MAC_AUDIO_FOLDER}/mac.json`, new Blob([JSON.stringify(status)], { type: 'application/json' }))
  }

  private async open(id: string): Promise<OpenBook> {
    const cached = this.books.get(id)
    if (cached) return cached
    const { makeBook } = await import('../vendor/foliate-js/view.js')
    const file = await bookFile(id, undefined, undefined, { touch: false })
    const b = (await makeBook(file)) as unknown as {
      metadata?: { title?: unknown; language?: unknown }
      sections?: OpenBook['sections']
      resolveCFI?: OpenBook['resolveCFI']
    }
    const title = typeof b.metadata?.title === 'string' ? b.metadata.title : ((await db.books.get(id))?.title ?? '')
    const lang = (Array.isArray(b.metadata?.language) ? b.metadata?.language[0] : b.metadata?.language) as string | undefined
    const book: OpenBook = { title, lang: lang || 'en', sections: b.sections ?? [], resolveCFI: b.resolveCFI?.bind(b), texts: new Map() }
    // A few books at a time
    if (this.books.size >= 4) this.books.delete(this.books.keys().next().value!)
    this.books.set(id, book)
    return book
  }

  /** The chapter's text as the phone reads it, or null when it has nothing to read. */
  private async text(book: OpenBook, i: number): Promise<string | null> {
    if (book.texts.has(i)) return book.texts.get(i)!
    let text: string | null = null
    const s = book.sections[i]
    try {
      if (s && s.linear !== 'no' && s.createDocument) {
        const doc = await s.createDocument()
        if (doc?.body) {
          const t = indexText(doc.body).text
          // Same rule as the phone's reader (sectionTexts)
          if (/[\p{L}\p{N}]/u.test(t) && t.trim().length >= 40) text = t
        }
      }
    } catch (e) {
      console.warn('Chapter text failed', e)
    }
    book.texts.set(i, text)
    return text
  }

  /** Sections with text from [current], as far as the window needs. */
  private async readable(book: OpenBook, current: number, ahead: number): Promise<number[]> {
    const out: number[] = []
    for (let i = Math.max(0, current); i < book.sections.length; i++) {
      if (await this.text(book, i)) out.push(i)
      if (ahead < WHOLE_BOOK && out.filter((x) => x > current).length >= ahead) break
    }
    return out
  }

  private async make(id: string, book: OpenBook, section: number, voice: string, books: MacBookStatus[], gpu: boolean) {
    const text = (await this.text(book, section))!
    const lang = book.lang
    const pieces = pieceOffsets(text, lang)
    if (!pieces.length) return
    await kokoro.load(gpu)
    const parts: Float32Array[] = []
    const segments: Span[] = []
    let rate = 24000
    let samples = 0
    for (let n = 0; n < pieces.length; n++) {
      if (!this.state.enabled) throw new Error('Stopped')
      this.working = { book: id, title: book.title, section, label: '', progress: n / pieces.length }
      await this.beat(voice, books, false)
      const p = pieces[n]
      const audio = await kokoro.generate(text.slice(p.start, p.end), voice, false, true).audio
      rate = audio.sampleRate
      const a = Math.round((samples / rate) * 1000)
      parts.push(audio.samples)
      samples += audio.samples.length
      const gap = new Float32Array(Math.round(rate * GAP_SECONDS))
      parts.push(gap)
      samples += gap.length
      segments.push({ s: p.start, e: p.end, a, b: Math.round((samples / rate) * 1000) })
    }
    const all = new Float32Array(samples)
    let at = 0
    for (const p of parts) {
      all.set(p, at)
      at += p.length
    }
    const res = await fetch('/api/bookaudio/aac', { method: 'POST', headers: { ...appHeaders, 'content-type': 'audio/wav' }, body: wavBlob(all, rate) })
    if (!res.ok) throw new Error(`Could not compress chapter audio (${res.status})`)
    const m4a = await res.blob()
    const meta: ChapterMeta = {
      textHash: javaHash(text),
      textLength: text.length,
      lang: 'mac',
      durationMillis: segments[segments.length - 1].b,
      segments,
      words: segments.flatMap((s) => estimateWords(text, s)),
      from: 0,
      source: 'mac',
      voice,
    }
    const folder = `${MAC_AUDIO_FOLDER}/${id}`
    await putFile(`${folder}/s${section}.m4a`, m4a)
    // Written last: a chapter counts once its meta is there
    await putFile(`${folder}/s${section}.json`, new Blob([JSON.stringify(meta)], { type: 'application/json' }))
  }
}

export const macBookAudio = new MacBookAudio()

/** The Mac counts as on when it was seen this recently */
export const MAC_ACTIVE_MS = 15 * 60_000

/**
 * What the Mac is doing (its mac.json in Dropbox), for other browsers' summary lines; null in the
 * Mac app itself, without Dropbox, or before the Mac ever wrote one. Checked every two minutes.
 */
export function useMacStatus(): MacStatus | null {
  const [status, setStatus] = useState<MacStatus | null>(null)
  useEffect(() => {
    let stop = false
    const load = async () => {
      if (macBookAudio.state.available || !(await getDropbox())) return
      const text = await readTextFile(`${MAC_AUDIO_FOLDER}/mac.json`).catch(() => null)
      if (!stop && text) setStatus(JSON.parse(text) as MacStatus)
    }
    void load()
    const t = window.setInterval(() => void load(), 2 * 60_000)
    return () => {
      stop = true
      window.clearInterval(t)
    }
  }, [])
  return status
}

/** "2 min ago" */
export function seenAgo(ts: number, now = Date.now()): string {
  const min = Math.max(0, Math.round((now - ts) / 60_000))
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const h = Math.round(min / 60)
  return h < 24 ? `${h} h ago` : `${Math.round(h / 24)} days ago`
}
