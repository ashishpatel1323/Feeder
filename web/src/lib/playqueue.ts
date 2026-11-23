/**
 * The play queue: plays a playlist from start to end, mixing engines. Podcast episodes and saved
 * YouTube videos play as audio here; articles are read aloud by the read-aloud engine (the
 * Now playing bar holds the article text it reads from, see components/QueuePlayer.tsx).
 * When an item ends it is marked read, it leaves your manual playlists, and the next one starts.
 */
import { useEffect, useState } from 'react'
import { db, feedDisplayTitle, getKv, type Item } from './db'
import { FEED_KINDS_KEY, type FeedKind } from './home'
import { addToPlaylist, itemKind, removeFromAllManual, type ItemKind } from './playlists'
import { readAloud } from './readaloud'
import { savedVideoUrl } from './offline'
import { articleVideoId } from './youtube'
import { resumeAt, saveVideoPos } from './videopos'
import { rememberDuration } from './downloads'

export type QueueStatus = 'idle' | 'loading' | 'playing' | 'paused'

export interface QueueState {
  playlistId: string | null
  name: string
  ids: string[]
  index: number
  status: QueueStatus
  /** What the current item is, null when nothing is queued. */
  kind: ItemKind | null
  /** Audio items: seconds played and length (0 when not known yet). */
  position: number
  duration: number
  /** Audio speed (articles use the read-aloud speed). */
  audioRate: number
}

const RATE_KEY = 'feeder.queueAudioRate'
const readRate = () => {
  try {
    const n = Number(localStorage.getItem(RATE_KEY))
    return n >= 0.5 && n <= 4 ? n : 1
  } catch {
    return 1
  }
}

const initial: QueueState = { playlistId: null, name: '', ids: [], index: 0, status: 'idle', kind: null, position: 0, duration: 0, audioRate: 1 }

type Listener = (s: QueueState) => void

class PlayQueue {
  state: QueueState = { ...initial, audioRate: readRate() }
  private listeners = new Set<Listener>()
  private audio: HTMLAudioElement | null = null
  private generation = 0
  private lastSaved = 0
  /** The item now playing, for the bar and the media session. */
  current: Item | null = null
  feedTitle = ''
  /** Shown when something goes wrong or an item is skipped. */
  onMessage: ((msg: string, action?: { label: string; run: () => void }) => void) | null = null

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    return () => void this.listeners.delete(l)
  }

  private set(patch: Partial<QueueState>) {
    this.state = { ...this.state, ...patch }
    this.listeners.forEach((l) => l(this.state))
  }

  get active(): boolean {
    return this.state.ids.length > 0 && this.state.kind !== null
  }

  get currentId(): string | null {
    return this.state.ids[this.state.index] ?? null
  }

  /** Plays [ids] in order from [start]. */
  start(ids: string[], opts: { name: string; playlistId?: string | null; start?: number } = { name: '' }) {
    if (!ids.length) return
    this.set({ ids, index: Math.max(0, Math.min(opts.start ?? 0, ids.length - 1)), name: opts.name, playlistId: opts.playlistId ?? null })
    void this.playCurrent()
  }

  /** Puts [id] right after the item playing now (or starts the queue with it). */
  playNext(id: string) {
    if (!this.active) return this.start([id], { name: 'Up next' })
    const ids = this.state.ids.filter((x, i) => x !== id || i === this.state.index)
    if (ids[this.state.index] === id) return
    ids.splice(this.state.index + 1, 0, id)
    this.set({ ids })
  }

  /** Puts [id] at the end of the queue (or starts the queue with it). */
  playLast(id: string) {
    if (!this.active) return this.start([id], { name: 'Up next' })
    if (this.state.ids.includes(id)) return
    this.set({ ids: [...this.state.ids, id] })
  }

  private ensureAudio(): HTMLAudioElement {
    if (this.audio) return this.audio
    const a = new Audio()
    a.preload = 'auto'
    a.addEventListener('timeupdate', () => {
      this.set({ position: a.currentTime, duration: Number.isFinite(a.duration) ? a.duration : 0 })
      const id = this.currentId
      if (id && Date.now() - this.lastSaved > 5000) {
        this.lastSaved = Date.now()
        void saveVideoPos(id, a.currentTime, a.duration)
      }
    })
    a.addEventListener('loadedmetadata', () => {
      const vid = this.current && articleVideoId(this.current)
      if (vid && Number.isFinite(a.duration)) rememberDuration(vid, a.duration)
    })
    a.addEventListener('playing', () => this.set({ status: 'playing' }))
    a.addEventListener('pause', () => {
      if (this.state.kind !== 'article' && this.state.status === 'playing') this.set({ status: 'paused' })
    })
    a.addEventListener('ended', () => void this.finishCurrent())
    a.addEventListener('error', () => {
      if (!a.src || this.state.kind === 'article') return
      this.onMessage?.(`Could not play “${this.current?.title ?? 'this item'}”, skipped`)
      void this.next(false)
    })
    this.audio = a
    return a
  }

  private stopAudio() {
    if (!this.audio) return
    const id = this.currentId
    if (id && this.audio.currentTime > 0 && this.state.kind !== 'article') void saveVideoPos(id, this.audio.currentTime, this.audio.duration)
    this.audio.pause()
    this.audio.removeAttribute('src')
    this.audio.load()
  }

  private async playCurrent(): Promise<void> {
    const gen = ++this.generation
    this.stopAudio()
    const id = this.currentId
    if (!id) return this.stop()
    const item = await db.items.get(id)
    if (gen !== this.generation) return
    if (!item) {
      // Deleted meanwhile: skip it
      this.set({ ids: this.state.ids.filter((x) => x !== id) })
      if (this.state.index >= this.state.ids.length) return this.stop()
      return this.playCurrent()
    }
    const feed = await db.feeds.get(item.feedUrl)
    const kinds = await getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {})
    if (gen !== this.generation) return
    const kind = itemKind(item, kinds[item.feedUrl])
    this.current = item
    this.feedTitle = feedDisplayTitle(feed) || ''
    this.set({ kind, status: 'loading', position: 0, duration: item.duration ?? 0 })
    if (kind === 'article') {
      // QueuePlayer sees the new article, attaches its text and starts reading it aloud
      if (readAloud.state.status !== 'idle') readAloud.stop()
      return
    }
    if (readAloud.state.status !== 'idle') readAloud.stop()
    let src: string | null = null
    if (kind === 'youtube') {
      const vid = articleVideoId(item)
      src = vid ? await savedVideoUrl(vid) : null
      if (gen !== this.generation) return
      if (!src) {
        this.onMessage?.(`“${item.title}” is a YouTube video that isn't downloaded yet, skipped. Download it to hear it here.`)
        return this.next(false)
      }
    } else {
      src = item.enclosureUrl ?? null
    }
    if (!src) return this.next(false)
    const a = this.ensureAudio()
    a.src = src
    a.playbackRate = this.state.audioRate
    const pos = await db.videoPos.get(item.id)
    if (gen !== this.generation) return
    const at = resumeAt(pos)
    if (at) a.currentTime = at
    this.setMedia(item)
    try {
      await a.play()
    } catch (e) {
      if (gen !== this.generation) return
      // Autoplay blocked: wait for a tap on Play
      this.set({ status: 'paused' })
      if (e instanceof DOMException && e.name !== 'NotAllowedError') this.onMessage?.(`Could not play: ${e.message}`)
    }
  }

  private setMedia(item: Item) {
    if (!('mediaSession' in navigator)) return
    const ms = navigator.mediaSession
    ms.metadata = new MediaMetadata({
      title: item.title,
      artist: this.feedTitle || 'Feeder',
      album: this.state.name || 'Feeder playlist',
      artwork: item.imageUrl ? [{ src: item.imageUrl }] : [],
    })
    const handlers: [MediaSessionAction, (() => void) | null][] = [
      ['play', () => this.toggle(true)],
      ['pause', () => this.toggle(false)],
      ['stop', () => this.stop()],
      ['seekbackward', () => this.seekBy(-15)],
      ['seekforward', () => this.seekBy(30)],
      ['previoustrack', () => this.prev()],
      ['nexttrack', () => void this.next(false)],
    ]
    for (const [action, handler] of handlers) {
      try {
        ms.setActionHandler(action, handler)
      } catch {
        // not supported here
      }
    }
  }

  /** The read-aloud engine finished an article. True when it was ours. */
  articleFinished(itemId: string): boolean {
    if (!this.active || this.state.kind !== 'article' || this.currentId !== itemId) return false
    void this.finishCurrent()
    return true
  }

  /** The read-aloud engine started or paused (for the article now in the queue). */
  articleStatus(status: 'idle' | 'playing' | 'paused', itemId: string | null) {
    if (!this.active || this.state.kind !== 'article' || itemId !== this.currentId) return
    if (status === 'playing' && this.state.status !== 'playing') this.set({ status: 'playing' })
    if (status === 'paused' && this.state.status === 'playing') this.set({ status: 'paused' })
  }

  /** Played to the end: mark it read, take it out of manual playlists, play the next one. */
  private async finishCurrent() {
    const item = this.current
    if (item) {
      await db.items.update(item.id, { read: 1, readTime: Date.now(), readSynced: 0 })
      if (this.state.kind !== 'article' && this.audio && Number.isFinite(this.audio.duration)) await saveVideoPos(item.id, this.audio.duration, this.audio.duration)
      const from = await removeFromAllManual(item.id)
      if (from.length) {
        this.onMessage?.(`Finished “${item.title}”, removed from your playlist`, {
          label: 'Undo',
          run: () => {
            void db.items.update(item.id, { read: 0, readTime: null, readSynced: 0 })
            for (const pl of from) void addToPlaylist(pl, [item.id])
          },
        })
      }
    }
    await this.next(false)
  }

  async next(userAsked = true): Promise<void> {
    if (this.state.index < this.state.ids.length - 1) {
      this.set({ index: this.state.index + 1 })
      await this.playCurrent()
    } else {
      if (userAsked) return
      this.onMessage?.(this.state.name ? `End of ${this.state.name}` : 'End of the queue')
      this.stop()
    }
  }

  prev() {
    // Past the first few seconds, Previous starts this one over
    if (this.state.kind !== 'article' && this.audio && this.audio.currentTime > 5) {
      this.audio.currentTime = 0
      return
    }
    if (this.state.index > 0) {
      this.set({ index: this.state.index - 1 })
      void this.playCurrent()
    } else if (this.state.kind === 'article') readAloud.play(0)
  }

  /** Jumps to an item in the queue. */
  jump(index: number) {
    if (index < 0 || index >= this.state.ids.length) return
    this.set({ index })
    void this.playCurrent()
  }

  /** Play/pause; pass [play] to force one. */
  toggle(play?: boolean) {
    if (!this.active) return
    if (this.state.kind === 'article') {
      const playing = readAloud.state.status === 'playing'
      if (play === false || (play === undefined && playing)) readAloud.pause()
      else if (readAloud.state.itemId === this.currentId) readAloud.play()
      else this.set({ status: 'loading' }) // QueuePlayer re-attaches and starts it
      return
    }
    const a = this.ensureAudio()
    if (play === false || (play === undefined && !a.paused)) {
      a.pause()
      this.set({ status: 'paused' })
    } else void a.play().catch(() => undefined)
  }

  seekBy(seconds: number) {
    if (this.state.kind === 'article') return readAloud.seekBy(seconds)
    if (!this.audio) return
    this.audio.currentTime = Math.max(0, Math.min((this.audio.duration || Infinity) - 1, this.audio.currentTime + seconds))
  }

  seekToFraction(f: number) {
    if (this.state.kind === 'article') return readAloud.seekToFraction(f)
    if (this.audio && Number.isFinite(this.audio.duration)) this.audio.currentTime = f * this.audio.duration
  }

  setAudioRate(rate: number) {
    try {
      localStorage.setItem(RATE_KEY, String(rate))
    } catch {
      /* private mode */
    }
    if (this.audio) this.audio.playbackRate = rate
    this.set({ audioRate: rate })
  }

  /** Takes an item out of the queue (not out of any playlist). */
  remove(index: number) {
    if (index === this.state.index) {
      const ids = this.state.ids.filter((_, i) => i !== index)
      if (!ids.length) return this.stop()
      this.set({ ids, index: Math.min(index, ids.length - 1) })
      void this.playCurrent()
      return
    }
    this.set({ ids: this.state.ids.filter((_, i) => i !== index), index: index < this.state.index ? this.state.index - 1 : this.state.index })
  }

  stop() {
    this.generation++
    this.stopAudio()
    if (this.state.kind === 'article' && readAloud.state.itemId === this.currentId) readAloud.stop()
    this.current = null
    this.set({ ...initial, audioRate: this.state.audioRate })
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'none'
  }
}

export const playQueue = new PlayQueue()

export function useQueue(): QueueState {
  const [s, setS] = useState(playQueue.state)
  useEffect(() => playQueue.subscribe(setS), [])
  return s
}
