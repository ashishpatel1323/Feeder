/**
 * Ebook chapters spoken ahead of time with the natural voice (Kokoro), so read aloud goes on from
 * one chapter to the next without waiting for speech to be made. Audio is kept in its own
 * IndexedDB database, one record per piece the read-aloud engine asks the voice for (keyed by
 * voice + text, so playback finds it without knowing about books). Played chapters are deleted as
 * reading moves on; a book's audio goes when it is finished or not opened for STALE_DAYS days.
 *
 * Browsers only run this while Feeder is open. The Android app does the same in a background job
 * (model/ebooks/BookAudio.kt).
 */
import Dexie, { type Table } from 'dexie'
import type { FoliateView } from '../vendor/foliate-js/view.js'
import { kokoro, KOKORO_PREFIX, type KokoroAudio } from './kokoro'
import { indexSpeechText, kokoroPieces } from './readaloud'
import { decodeSpeech, encodeSpeech, type StoredSpeech } from './speechCodec'

const STALE_DAYS = 7
export const AHEAD_CHOICES = [1, 2, 3, 5]

interface Piece {
  key: string
  /** The book, or "item:<id>" for a playlist article (playlistAudio.ts) */
  bookId: string
  section: number
  speech: StoredSpeech
  at: number
}

class AudioDb extends Dexie {
  pieces!: Table<Piece, string>
  constructor() {
    super('feeder-book-audio')
    this.version(1).stores({ pieces: '&key, bookId, [bookId+section], at' })
    // Pieces are kept compressed now (speechCodec.ts); the old uncompressed ones are dropped
    this.version(2)
      .stores({ pieces: '&key, bookId, [bookId+section], at' })
      .upgrade((tx) => tx.table('pieces').clear())
  }
}

export type SectionAudioState = 'played' | 'playing' | 'ready' | 'preparing' | 'waiting'

export interface SectionAudio {
  index: number
  label: string
  state: SectionAudioState
  progress: number
  seconds: number
}

export interface BookAudioState {
  bookId: string | null
  /** False with a browser voice: only the natural voice can be prepared ahead. */
  supported: boolean
  current: number
  sections: SectionAudio[]
  bytes: number
  readyAhead: number
  preparing: boolean
}

interface Plan {
  index: number
  label: string
  keys: string[]
  texts: string[]
}

type Listener = (s: BookAudioState) => void

const keyOf = (voice: string, text: string) => `${voice}\n${text}`

class BookAudioManager {
  private db: AudioDb | null = null
  /** What is stored, without the audio: key → book, chapter, size, length. */
  private index = new Map<string, { bookId: string; section: number; bytes: number; seconds: number }>()
  private loaded: Promise<void> | null = null
  private listeners = new Set<Listener>()
  private view: FoliateView | null = null
  private bookId: string | null = null
  private voice = ''
  private gpu = true
  private current = -1
  private ahead = 3
  private deletePlayed = true
  private plans = new Map<number, Plan>()
  private lastDeleted: number | null = null
  private making: { section: number; done: number; total: number } | null = null
  private running = false
  private generation = 0
  state: BookAudioState = { bookId: null, supported: false, current: -1, sections: [], bytes: 0, readyAhead: 0, preparing: false }

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    l(this.state)
    return () => this.listeners.delete(l)
  }

  private open(): Promise<void> {
    if (this.loaded) return this.loaded
    this.loaded = (async () => {
      if (typeof indexedDB === 'undefined') return
      this.db = new AudioDb()
      const cutoff = Date.now() - STALE_DAYS * 86400_000
      await this.db.pieces.where('at').below(cutoff).delete()
      await this.db.pieces.each((p) => {
        this.index.set(p.key, { bookId: p.bookId, section: p.section, bytes: p.speech.bytes, seconds: p.speech.samples / p.speech.sampleRate })
      })
    })().catch((e) => console.warn('Book audio storage unavailable', e))
    return this.loaded
  }

  /** Stored audio for a piece of speech, for the read-aloud engine (no lookups when nothing is stored). */
  has(voice: string, text: string): boolean {
    return this.index.has(keyOf(voice, text))
  }

  async load(voice: string, text: string): Promise<KokoroAudio> {
    await this.open()
    const p = await this.db?.pieces.get(keyOf(voice, text))
    if (!p) throw new Error('Prepared audio is gone')
    return decodeSpeech(p.speech)
  }

  /** Keeps audio for [text] (made with [voice]) for [owner]: a book's chapter, or "item:<id>". */
  async keep(owner: string, section: number, voice: string, text: string, audio: KokoroAudio) {
    await this.open()
    if (!this.db) return
    const speech = await encodeSpeech(audio.samples, audio.sampleRate)
    const piece: Piece = { key: keyOf(voice, text), bookId: owner, section, speech, at: Date.now() }
    await this.db.pieces.put(piece)
    this.index.set(piece.key, { bookId: owner, section, bytes: speech.bytes, seconds: speech.samples / speech.sampleRate })
  }

  /** Deletes all audio kept for [owner]. */
  async removeOwner(owner: string) {
    await this.open()
    await this.remove([...this.index].filter(([, v]) => v.bookId === owner).map(([k]) => k))
  }

  /** How much is kept for [owner], and how long it plays. */
  ownerStats(owner: string): { bytes: number; seconds: number; pieces: number } {
    let bytes = 0
    let seconds = 0
    let pieces = 0
    for (const v of this.index.values()) {
      if (v.bookId !== owner) continue
      bytes += v.bytes
      seconds += v.seconds
      pieces++
    }
    return { bytes, seconds, pieces }
  }

  /** The book being read aloud (null when reading stopped or with a browser voice). */
  setBook(bookId: string | null, view: FoliateView | null, voiceURI: string, gpu: boolean, ahead: number, deletePlayed: boolean) {
    const supported = voiceURI.startsWith(KOKORO_PREFIX)
    const voice = supported ? voiceURI.slice(KOKORO_PREFIX.length) : ''
    const changed = bookId !== this.bookId || voice !== this.voice
    this.ahead = ahead
    this.deletePlayed = deletePlayed
    this.gpu = gpu
    if (changed) {
      this.generation++
      this.bookId = bookId
      this.view = view
      this.voice = voice
      this.plans.clear()
      this.current = -1
      this.lastDeleted = null
      this.making = null
    } else this.view = view
    this.state = { ...this.state, bookId, supported }
    if (!bookId || !supported) {
      this.emit()
      return
    }
    void this.open().then(async () => {
      // Audio in another voice is no use any more
      const stale = [...this.index].filter(([k, v]) => v.bookId === bookId && !k.startsWith(`${voice}\n`)).map(([k]) => k)
      if (stale.length) await this.remove(stale)
      this.emit()
      void this.run()
    })
  }

  setAhead(n: number) {
    this.ahead = n
    void this.trim().then(() => this.run())
  }

  setDeletePlayed(on: boolean) {
    this.deletePlayed = on
    void this.trim()
  }

  /** Reading aloud is now at book section [section]. */
  setPosition(section: number) {
    if (section < 0 || section === this.current) return
    this.current = section
    void this.open().then(async () => {
      await this.trim()
      if (this.db && this.bookId) await this.db.pieces.where('bookId').equals(this.bookId).modify({ at: Date.now() })
      this.emit()
      void this.run()
    })
  }

  /** The book was read to the end: its audio goes. */
  async finished(bookId: string) {
    await this.open()
    await this.remove([...this.index].filter(([, v]) => v.bookId === bookId).map(([k]) => k))
    this.emit()
  }

  private async remove(keys: string[]) {
    if (!keys.length) return
    for (const k of keys) this.index.delete(k)
    await this.db?.pieces.bulkDelete(keys)
  }

  /** Deletes played chapters (when set) and chapters out of reach. */
  private async trim() {
    const bookId = this.bookId
    if (!bookId || this.current < 0) return
    const keep = new Set([this.current, ...(await this.window()).map((p) => p.index)])
    const drop: string[] = []
    for (const [k, v] of this.index) {
      if (v.bookId !== bookId || keep.has(v.section)) continue
      if (v.section < this.current) {
        if (!this.deletePlayed) continue
        this.lastDeleted = Math.max(this.lastDeleted ?? -1, v.section)
      }
      drop.push(k)
    }
    await this.remove(drop)
    this.emit()
  }

  /** The chapters after the current one to prepare: their text pieces. */
  private async window(): Promise<Plan[]> {
    const view = this.view
    const sections = view?.book?.sections
    if (!view || view.isFixedLayout || !sections || this.current < 0) return []
    const out: Plan[] = []
    for (let i = this.current + 1; i < sections.length && out.length < this.ahead; i++) {
      const plan = await this.plan(i)
      if (plan) out.push(plan)
    }
    return out
  }

  private async plan(index: number): Promise<Plan | null> {
    const cached = this.plans.get(index)
    if (cached) return cached.keys.length ? cached : null
    const s = this.view?.book?.sections?.[index]
    let plan: Plan = { index, label: '', keys: [], texts: [] }
    try {
      if (s && s.linear !== 'no' && s.createDocument) {
        const doc = await s.createDocument()
        if (doc?.body) {
          const lang = doc.documentElement.lang || document.documentElement.lang || navigator.language || 'en'
          const { text } = indexSpeechText(doc.body)
          if (text.trim().length >= 40) {
            const texts = kokoroPieces(text, lang)
            plan = { index, label: (this.view?.getProgressOf(index)?.tocItem?.label ?? '').trim(), keys: texts.map((t) => keyOf(this.voice, t)), texts }
          }
        }
      }
    } catch (e) {
      console.warn('Chapter text for read aloud failed', e)
    }
    this.plans.set(index, plan)
    return plan.keys.length ? plan : null
  }

  /** Makes the missing audio ahead, one piece at a time, behind what is being read now. */
  private async run() {
    if (this.running) return
    this.running = true
    const gen = this.generation
    try {
      while (gen === this.generation && this.bookId && this.voice) {
        const plans = await this.window()
        const next = plans.find((p) => p.keys.some((k) => !this.index.has(k)))
        if (!next || gen !== this.generation) break
        await kokoro.load(this.gpu)
        const i = next.keys.findIndex((k) => !this.index.has(k))
        this.making = { section: next.index, done: next.keys.filter((k) => this.index.has(k)).length, total: next.keys.length }
        this.emit()
        const audio = await kokoro.generate(next.texts[i], this.voice, false, true).audio
        if (gen !== this.generation || !this.db || !this.bookId) break
        // Reading may have moved past this chapter meanwhile
        if (next.index <= this.current) continue
        await this.keep(this.bookId, next.index, this.voice, next.texts[i], audio)
      }
    } catch (e) {
      console.warn('Preparing chapter audio stopped', e)
    } finally {
      this.making = null
      this.running = false
      this.emit()
    }
  }

  private emit() {
    const bookId = this.bookId
    const supported = this.state.supported
    if (!bookId || !supported) {
      this.state = { bookId, supported, current: -1, sections: [], bytes: 0, readyAhead: 0, preparing: false }
      this.listeners.forEach((l) => l(this.state))
      return
    }
    let bytes = 0
    const secondsBy = new Map<number, number>()
    for (const v of this.index.values()) {
      if (v.bookId !== bookId) continue
      bytes += v.bytes
      secondsBy.set(v.section, (secondsBy.get(v.section) ?? 0) + v.seconds)
    }
    const rows: SectionAudio[] = []
    const label = (i: number) => this.plans.get(i)?.label || (i === this.current ? (this.view?.lastLocation?.tocItem?.label ?? '').trim() : '')
    if (this.lastDeleted !== null && this.lastDeleted < this.current) rows.push({ index: this.lastDeleted, label: label(this.lastDeleted), state: 'played', progress: 0, seconds: 0 })
    if (this.current >= 0) rows.push({ index: this.current, label: label(this.current), state: 'playing', progress: 0, seconds: 0 })
    let ready = 0
    const ahead = [...this.plans.values()].filter((p) => p.index > this.current && p.keys.length).sort((a, b) => a.index - b.index).slice(0, this.ahead)
    for (const p of ahead) {
      const done = p.keys.filter((k) => this.index.has(k)).length
      if (done === p.keys.length) {
        ready++
        rows.push({ index: p.index, label: p.label, state: 'ready', progress: 1, seconds: secondsBy.get(p.index) ?? 0 })
      } else if (this.making?.section === p.index) {
        rows.push({ index: p.index, label: p.label, state: 'preparing', progress: done / p.keys.length, seconds: 0 })
      } else rows.push({ index: p.index, label: p.label, state: 'waiting', progress: done / p.keys.length, seconds: 0 })
    }
    this.state = { bookId, supported, current: this.current, sections: rows, bytes, readyAhead: ready, preparing: !!this.making }
    this.listeners.forEach((l) => l(this.state))
  }
}

export const bookAudio = new BookAudioManager()
