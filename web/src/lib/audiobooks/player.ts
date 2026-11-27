/**
 * Plays one audiobook at a time, its files one after another (this device's copies, else streamed
 * from Dropbox). Keeps the
 * position (saved every 15 s, on pause and on every jump, and synced as "audiopos"), a speed per
 * book, a sleep timer (minutes, or to the end of the chapter, fading out at the end) and the
 * media session (lock screen, headphone buttons: back 10 s, ahead 30 s, chapter back/next).
 *
 * It also plays Library > Player's files (media/library.ts), audio or video, as one-file books:
 * the same mini player, speed, sleep timer and synced place. Its element is a <video>, kept out of
 * the page until the Player's watch page shows it ([attachVideo]); moving it doesn't stop it, so a
 * video carries on as sound when its page is left. Files in a folder play one after another.
 */
import { useEffect, useState } from 'react'
import { db, getKv, setKv, type Audiobook } from '../db'
import { audioFocus, playQueue } from '../playqueue'
import { readAloud } from '../readaloud'
import { clockText, recordHistory } from '../history'
import { saveAudioPos, scanBook, trackUrl } from './library'
import { asBook, mediaUrl } from '../media/library'
import { noteMedia } from '../recent'
import type { MediaFile } from '../db'
import { bookLengthMs, chapterAt, chaptersOf, fractionAt, resumePoint, trackStartMs, type Chapter } from './timeline'

export type BookStatus = 'idle' | 'loading' | 'playing' | 'paused'

export interface Sleep {
  /** When it pauses (ms since epoch), or null when it waits for the chapter to end */
  endsAt: number | null
  endOfChapter: boolean
}

export interface BookPlayerState {
  bookId: string | null
  /** What is playing: an audiobook, or a file from Library > Player */
  source: 'audiobook' | 'media'
  /** A Player file with pictures: its watch page shows the video */
  video: boolean
  /** Player files that come next (their ids), in order */
  queue: string[]
  status: BookStatus
  /** File playing, and ms into it */
  i: number
  ms: number
  /** Ms into the whole book, and its length (0 while unknown) */
  bookMs: number
  bookLength: number
  chapter: number
  chapters: Chapter[]
  rate: number
  sleep: Sleep | null
  /** Reading the files' chapters and lengths: files done, files in all */
  scan: { done: number; total: number } | null
  error: string | null
}

const RATES_KEY = 'audiobookRates'
const LAST_RATE = 'feeder.audiobookRate'
const FADE_MS = 10_000

const initial: BookPlayerState = { bookId: null, source: 'audiobook', video: false, queue: [], status: 'idle', i: 0, ms: 0, bookMs: 0, bookLength: 0, chapter: 0, chapters: [], rate: 1, sleep: null, scan: null, error: null }

type Listener = (s: BookPlayerState) => void

class AudiobookPlayer {
  state: BookPlayerState = initial
  book: Audiobook | null = null
  private listeners = new Set<Listener>()
  private audio: HTMLVideoElement | null = null
  /** The Player file playing, when it is one */
  media: MediaFile | null = null
  /** Where the element waits while no page shows the video */
  private holder: HTMLElement | null = null
  /** Opens a Player file by id (set by media/queue.ts), for the queue */
  openMediaById: ((id: string, queue: string[]) => Promise<void>) | null = null
  private generation = 0
  private lastSaved = 0
  private retried = false
  private sleepTimer: ReturnType<typeof setInterval> | null = null
  /** History: whether a "play" was noted and no "pause" since, and why the next play or pause happens */
  private noted = false
  private playReason = 'app'
  private pauseReason: string | null = null
  private switching = false
  onMessage: ((msg: string) => void) | null = null

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    return () => void this.listeners.delete(l)
  }

  private set(patch: Partial<BookPlayerState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l(this.state))
  }

  get active() {
    return !!this.state.bookId
  }

  private ensureAudio(): HTMLVideoElement {
    if (this.audio) return this.audio
    const a = document.createElement('video')
    a.preload = 'auto'
    a.playsInline = true
    a.className = 'media-video'
    this.holder = document.createElement('div')
    this.holder.className = 'media-holder'
    this.holder.hidden = true
    document.body.appendChild(this.holder)
    this.holder.appendChild(a)
    a.addEventListener('loadedmetadata', () => this.onMetadata())
    a.addEventListener('timeupdate', () => this.onTime())
    a.addEventListener('playing', () => {
      this.retried = false
      this.switching = false
      this.set({ status: 'playing', error: null })
      if (!this.noted) {
        this.noted = true
        this.note('play', this.playReason)
      }
      this.playReason = 'app'
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing'
    })
    a.addEventListener('pause', () => {
      // Paused by the browser or the system (not by a file change): headphones out, another tab
      if (this.noted && !this.switching && !a.ended) this.notePause(this.pauseReason ?? 'system')
      if (this.state.status === 'playing') this.set({ status: 'paused' })
      if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused'
      this.save()
    })
    a.addEventListener('ended', () => void this.trackEnded())
    a.addEventListener('error', () => void this.onError())
    this.audio = a
    return a
  }

  private onTime() {
    const a = this.audio
    const b = this.book
    if (!a || !b) return
    const ms = a.currentTime * 1000
    const chapter = chapterAt(this.state.chapters, this.state.i, ms)
    const chapterChanged = chapter !== this.state.chapter
    this.set({ ms, bookMs: trackStartMs(b, this.state.i) + ms, chapter })
    if (chapterChanged) {
      this.setMedia()
      if (this.state.sleep?.endOfChapter) {
        // Reached the next chapter while waiting for the end of the last one
        this.cancelSleep()
        this.pause('sleep')
        this.onMessage?.('Sleep timer: paused at the end of the chapter')
        return
      }
    }
    if (Date.now() - this.lastSaved > 15_000) this.save()
    this.updatePositionState()
  }

  /** Shows the video inside [box] (the Player's watch page), or puts it away again with null. */
  attachVideo(box: HTMLElement | null) {
    const a = this.ensureAudio()
    const to = box ?? this.holder
    if (!to || a.parentElement === to) return
    to.appendChild(a)
    a.controls = !!box
  }

  /** Subtitles for the video (a WebVTT link), shown by default; null takes them away. */
  setSubtitles(url: string | null, label = 'Subtitles') {
    const a = this.ensureAudio()
    a.querySelectorAll('track').forEach((t) => t.remove())
    if (!url) return
    const t = document.createElement('track')
    t.kind = 'subtitles'
    t.label = label
    t.src = url
    t.default = true
    a.appendChild(t)
    // Chrome leaves a track added after loading hidden: show it
    t.addEventListener('load', () => {
      if (a.textTracks[0]) a.textTracks[0].mode = 'showing'
    })
  }

  /** A file's real length, when it wasn't known: kept for the book and, for a Player file, saved. */
  private onMetadata() {
    const a = this.audio
    const b = this.book
    if (!a || !b || !Number.isFinite(a.duration) || a.duration <= 0) return
    const t = b.tracks[this.state.i]
    if (!t || t.duration) return
    const tracks = b.tracks.map((x, k) => (k === this.state.i ? { ...x, duration: a.duration } : x))
    this.refreshBook({ ...b, tracks })
    if (this.media && this.media.id === b.id) {
      this.media = { ...this.media, duration: a.duration }
      void db.media.update(b.id, { duration: a.duration })
    }
    this.set({ video: this.state.source === 'media' && a.videoWidth > 0 })
  }

  /** Notes a play or pause in the book's history, with the chapter and time into it. */
  private note(a: 'play' | 'pause', reason: string) {
    const b = this.book
    if (!b) return
    const s = this.state
    if (s.source === 'media' && this.media) {
      void noteMedia({ id: b.id, title: b.title, folder: this.media.folder }, a === 'play', fractionAt(b, s.i, s.ms), `0:${Math.round(s.ms)}`)
      return
    }
    const ch = s.chapters[s.chapter]
    const inChapter = ch && ch.i === s.i ? s.ms - ch.startMs : s.ms
    void recordHistory(
      'audiobook',
      b.id,
      a,
      {
        p: ch ? `Ch. ${s.chapter + 1} · ${clockText(inChapter)}` : clockText(s.bookMs),
        s: ch?.title,
        f: fractionAt(b, s.i, s.ms),
        x: `${s.i}:${Math.round(s.ms)}`,
      },
      reason,
    )
  }

  private notePause(reason: string) {
    if (!this.noted) return
    this.noted = false
    this.pauseReason = null
    this.note('pause', reason)
  }

  /** Saves the position now (also sent to other devices with the next sync). */
  save() {
    const b = this.book
    if (!b || !this.audio) return
    const t = b.tracks[this.state.i]
    if (!t) return
    this.lastSaved = Date.now()
    const ms = this.audio.currentTime * 1000
    void saveAudioPos(b.id, this.state.i, t.hash, ms, fractionAt(b, this.state.i, ms))
  }

  private refreshBook(b: Audiobook) {
    this.book = b
    const chapters = chaptersOf(b)
    this.set({ chapters, bookLength: bookLengthMs(b), chapter: chapterAt(chapters, this.state.i, this.state.ms), bookMs: trackStartMs(b, this.state.i) + this.state.ms })
  }

  /** Opens a book where it was left (or [at]), and plays it unless [play] is false. */
  async open(id: string, opts: { play?: boolean; at?: { i: number; ms: number }; reason?: string; media?: MediaFile; queue?: string[] } = {}) {
    if (this.state.bookId === id && !opts.at) {
      if (opts.queue) this.set({ queue: opts.queue })
      if (opts.play !== false) this.play(opts.reason)
      return
    }
    this.notePause('app')
    this.stopAudio()
    this.playReason = opts.reason ?? 'app'
    const gen = ++this.generation
    const b = opts.media ? asBook(opts.media) : await db.audiobooks.get(id)
    if (!b || gen !== this.generation) return
    const rate = await this.rateFor(id)
    this.book = b
    this.media = opts.media ?? null
    this.set({ ...initial, bookId: id, source: opts.media ? 'media' : 'audiobook', video: opts.media?.kind === 'video', queue: opts.queue ?? [], status: 'loading', rate, sleep: this.state.sleep })
    this.refreshBook(b)
    if (opts.media) void db.media.update(id, { lastOpenedAt: Date.now() })
    else void db.audiobooks.update(id, { lastOpenedAt: Date.now() })
    // Chapters and lengths: read once, in the background; playing doesn't wait for them
    if (!opts.media && b.tracks.some((t) => !t.scanned)) {
      this.set({ scan: { done: b.tracks.filter((t) => t.scanned).length, total: b.tracks.length } })
      void scanBook(id, (done, total) => gen === this.generation && this.set({ scan: { done, total } }))
        .then((fresh) => {
          if (gen !== this.generation) return
          this.set({ scan: null })
          if (fresh) {
            this.refreshBook(fresh)
            this.setMedia()
          }
        })
        .catch(() => gen === this.generation && this.set({ scan: null }))
    }
    const pos = await db.audioPos.get(id)
    if (gen !== this.generation) return
    const start = opts.at ?? resumePoint(b, pos) ?? { i: 0, ms: 0 }
    await this.load(start.i, start.ms, opts.play !== false, gen)
  }

  private async load(i: number, ms: number, play: boolean, gen = this.generation, freshLink = false) {
    const b = this.book
    if (!b) return
    const t = b.tracks[i]
    if (!t) return
    this.set({ i, ms, bookMs: trackStartMs(b, i) + ms, chapter: chapterAt(this.state.chapters, i, ms), status: play ? 'loading' : 'paused' })
    let url: string
    try {
      url = this.media ? await mediaUrl(this.media, freshLink) : await trackUrl(t, freshLink)
    } catch (e) {
      if (gen !== this.generation) return
      this.set({ status: 'paused', error: e instanceof Error ? e.message : String(e) })
      this.onMessage?.(t.local ? `Could not open ${t.name}` : `Could not reach Dropbox: ${e instanceof Error ? e.message : e}`)
      return
    }
    if (gen !== this.generation) return
    const a = this.ensureAudio()
    this.switching = this.noted
    a.src = url
    a.playbackRate = this.state.rate
    a.volume = 1
    const seek = () => {
      if (ms > 0) a.currentTime = ms / 1000
    }
    if (a.readyState >= 1) seek()
    else a.addEventListener('loadedmetadata', seek, { once: true })
    this.setMedia()
    if (play) await this.startAudio(gen)
  }

  private async startAudio(gen = this.generation) {
    const a = this.audio
    if (!a) return
    // One player at a time
    if (playQueue.active) playQueue.stop()
    if (readAloud.state.status === 'playing') readAloud.pause()
    try {
      await a.play()
    } catch (e) {
      if (gen !== this.generation) return
      this.set({ status: 'paused' })
      if (e instanceof DOMException && e.name !== 'NotAllowedError' && e.name !== 'AbortError') this.onMessage?.(`Could not play: ${e.message}`)
    }
  }

  private async onError() {
    const a = this.audio
    if (!a?.src || !this.book) return
    // Most often the 4-hour Dropbox link ran out: get a new one and carry on from here
    if (!this.retried) {
      this.retried = true
      await this.load(this.state.i, this.state.ms, this.state.status !== 'paused', this.generation, true)
      return
    }
    const name = this.book.tracks[this.state.i]?.name ?? 'this file'
    this.notePause('system')
    this.set({ status: 'paused', error: `Could not play ${name}` })
    this.onMessage?.(`Could not play ${name}. This browser may not play its format.`)
  }

  private async trackEnded() {
    const b = this.book
    if (!b) return
    if (this.state.i < b.tracks.length - 1) {
      await this.load(this.state.i + 1, 0, true)
      return
    }
    // The end of the book
    const last = b.tracks[b.tracks.length - 1]
    void saveAudioPos(b.id, b.tracks.length - 1, last.hash, (last.duration ?? this.audio?.duration ?? 0) * 1000, 1)
    this.notePause('end')
    // A Player file: on to the next one in its folder
    const [next, ...rest] = this.state.queue
    if (this.state.source === 'media' && next && this.openMediaById) {
      await this.openMediaById(next, rest)
      return
    }
    this.cancelSleep()
    this.set({ status: 'paused' })
    this.onMessage?.(`Finished ${b.title}`)
  }

  play(reason = 'app') {
    if (!this.book) return
    this.playReason = reason
    if (!this.audio?.src) {
      void this.load(this.state.i, this.state.ms, true)
      return
    }
    void this.startAudio()
  }

  pause(reason = 'app') {
    this.pauseReason = reason
    this.notePause(reason)
    this.audio?.pause()
    this.set({ status: 'paused' })
  }

  toggle(reason = 'app') {
    if (this.state.status === 'playing' || this.state.status === 'loading') this.pause(reason)
    else this.play(reason)
  }

  /** Jumps [seconds] back or ahead, across files when needed. */
  seekBy(seconds: number) {
    this.seekToBookMs(this.state.bookMs + seconds * 1000)
  }

  /** Jumps to a place in the whole book. */
  seekToBookMs(bookMs: number) {
    const b = this.book
    if (!b) return
    const len = this.state.bookLength
    const target = Math.max(0, len ? Math.min(len - 1000, bookMs) : bookMs)
    let base = 0
    let i = 0
    for (; i < b.tracks.length - 1; i++) {
      const tl = (b.tracks[i].duration ?? 0) * 1000
      if (!tl || target < base + tl) break
      base += tl
    }
    // Within the file now playing (or lengths unknown): seek inside it
    if (i === this.state.i || !b.tracks[i].duration) {
      const ms = i === this.state.i ? target - base : Math.max(0, this.state.ms + (target - this.state.bookMs))
      if (this.audio) this.audio.currentTime = ms / 1000
      this.set({ ms, bookMs: trackStartMs(b, this.state.i) + ms })
      this.save()
      return
    }
    void this.load(i, target - base, this.state.status === 'playing' || this.state.status === 'loading').then(() => this.save())
  }

  seekToFraction(f: number) {
    if (this.state.bookLength) this.seekToBookMs(f * this.state.bookLength)
    else if (this.audio && Number.isFinite(this.audio.duration)) this.audio.currentTime = f * this.audio.duration
  }

  /** Plays chapter [k] from its start. */
  jumpToChapter(k: number) {
    const c = this.state.chapters[k]
    if (!c) return
    const playing = this.state.status !== 'paused'
    if (c.i === this.state.i && this.audio?.src) {
      this.audio.currentTime = c.startMs / 1000
      this.set({ ms: c.startMs, chapter: k })
      this.save()
      if (playing) this.play()
      return
    }
    void this.load(c.i, c.startMs, true).then(() => this.save())
  }

  /** Previous: the start of this chapter, or the one before when already near its start. */
  prevChapter() {
    const k = this.state.chapter
    const c = this.state.chapters[k]
    if (c && c.i === this.state.i && this.state.ms - c.startMs > 3000) return this.jumpToChapter(k)
    this.jumpToChapter(Math.max(0, k - 1))
  }

  nextChapter() {
    if (this.state.chapter < this.state.chapters.length - 1) this.jumpToChapter(this.state.chapter + 1)
  }

  private async rateFor(id: string): Promise<number> {
    const rates = await getKv<Record<string, number>>(RATES_KEY, {})
    const r = rates[id]
    if (r) return r
    try {
      const last = Number(localStorage.getItem(LAST_RATE))
      return last >= 0.5 && last <= 4 ? last : 1
    } catch {
      return 1
    }
  }

  /** Speed for this book (remembered per book, and as the start speed for new books). */
  async setRate(rate: number) {
    if (this.audio) this.audio.playbackRate = rate
    this.set({ rate })
    const id = this.state.bookId
    if (!id) return
    const rates = await getKv<Record<string, number>>(RATES_KEY, {})
    await setKv(RATES_KEY, { ...rates, [id]: rate })
    try {
      localStorage.setItem(LAST_RATE, String(rate))
    } catch {
      /* private mode */
    }
  }

  /** Pauses after [minutes], or at the end of the chapter with minutes = 'chapter'. */
  setSleep(minutes: number | 'chapter') {
    this.cancelSleep()
    if (minutes === 'chapter') {
      this.set({ sleep: { endsAt: null, endOfChapter: true } })
      return
    }
    this.set({ sleep: { endsAt: Date.now() + minutes * 60_000, endOfChapter: false } })
    this.sleepTimer = setInterval(() => this.sleepTick(), 500)
  }

  /** Adds 5 minutes, or starts a 5-minute timer. */
  extendSleep() {
    const s = this.state.sleep
    if (!s?.endsAt) return this.setSleep(5)
    if (this.audio) this.audio.volume = 1
    this.set({ sleep: { ...s, endsAt: Math.max(s.endsAt, Date.now()) + 5 * 60_000 } })
  }

  cancelSleep() {
    if (this.sleepTimer) clearInterval(this.sleepTimer)
    this.sleepTimer = null
    if (this.audio) this.audio.volume = 1
    if (this.state.sleep) this.set({ sleep: null })
  }

  private sleepTick() {
    const s = this.state.sleep
    if (!s?.endsAt) return
    const left = s.endsAt - Date.now()
    if (this.state.status !== 'playing') return
    if (left <= 0) {
      this.cancelSleep()
      this.pause('sleep')
      // Back a little, so the last words before falling asleep are heard again
      this.seekBy(-10)
      this.onMessage?.('Sleep timer: paused')
      return
    }
    if (this.audio) this.audio.volume = left < FADE_MS ? Math.max(0.05, left / FADE_MS) : 1
  }

  private setMedia() {
    const b = this.book
    if (!b || !('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    const chapter = this.state.chapters[this.state.chapter]
    ms.metadata = new MediaMetadata({
      title: chapter?.title || b.title,
      artist: b.author || (this.state.source === 'media' ? 'Feeder' : 'Audiobook'),
      album: b.title,
      artwork: b.cover ? [{ src: b.cover }] : [],
    })
    const handlers: [MediaSessionAction, MediaSessionActionHandler | null][] = [
      ['play', () => this.play('media')],
      ['pause', () => this.pause('media')],
      ['stop', () => this.pause('media')],
      ['seekbackward', () => this.seekBy(-10)],
      ['seekforward', () => this.seekBy(30)],
      ['previoustrack', () => this.prevChapter()],
      ['nexttrack', () => (this.state.source === 'media' ? this.skipNext() : this.nextChapter())],
      [
        'seekto',
        (d) => {
          if (d.seekTime !== undefined && this.audio) this.audio.currentTime = d.seekTime
        },
      ],
    ]
    for (const [action, handler] of handlers) {
      try {
        ms.setActionHandler(action, handler)
      } catch {
        // not supported here
      }
    }
  }

  private updatePositionState() {
    const a = this.audio
    if (!a || !('mediaSession' in navigator) || !Number.isFinite(a.duration)) return
    try {
      navigator.mediaSession.setPositionState({ duration: a.duration, position: Math.min(a.currentTime, a.duration), playbackRate: a.playbackRate })
    } catch {
      // not supported
    }
  }

  private stopAudio() {
    if (!this.audio) return
    if (this.audio.src && this.book) this.save()
    this.audio.pause()
    this.audio.removeAttribute('src')
    this.audio.querySelectorAll('track').forEach((t) => t.remove())
    this.audio.load()
  }

  /** A Player file: the next one in its folder now. */
  skipNext() {
    const [next, ...rest] = this.state.queue
    if (next && this.openMediaById) void this.openMediaById(next, rest)
  }

  /** Stops and closes the player (the position is kept). */
  close() {
    this.notePause('closed')
    this.generation++
    this.stopAudio()
    this.cancelSleep()
    this.book = null
    this.media = null
    this.set(initial)
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none'
  }
}

export const audiobookPlayer = new AudiobookPlayer()
audioFocus.pauseAudiobook = () => {
  if (audiobookPlayer.state.status === 'playing' || audiobookPlayer.state.status === 'loading') audiobookPlayer.pause('focus')
}

export function useAudiobookPlayer(): BookPlayerState {
  const [s, setS] = useState(audiobookPlayer.state)
  useEffect(() => audiobookPlayer.subscribe(setS), [])
  return s
}
