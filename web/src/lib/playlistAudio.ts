/**
 * "Get next 20 articles ready offline" for playlists: each article's text (full text where the
 * feed uses it) is fetched and spoken ahead with the natural voice, so it plays at once and with
 * no connection. Audio goes into the same store as ebook chapters (bookAudio.ts, owner
 * "item:<id>"), so the read-aloud engine finds it by itself. An article's audio is deleted once it
 * has played, and anything not played within 7 days. Browsers only do this while Feeder is open.
 */
import Dexie, { type Table } from 'dexie'
import { bookAudio } from './bookAudio'
import { db, type Feed, type Item } from './db'
import { loadFullText } from './extras'
import { paragraphsOf } from './flip'
import { kokoro, KOKORO_PREFIX } from './kokoro'
import { isOnline } from './offline'
import { indexSpeechText, kokoroPieces, readAloud } from './readaloud'

// Read aloud looks in the store before asking the voice, for playlists as for ebooks
readAloud.storedAudio = bookAudio

export const BATCH_SIZE = 20
const STALE_DAYS = 7
const BATCH_KEY = 'feeder.playlistAudio'

interface ReadyArticle {
  id: string
  /** The paragraphs that were spoken: the article plays from these, so the audio matches. */
  paras: string[]
  playlistId: string
  voice: string
  at: number
}

class ArticlesDb extends Dexie {
  articles!: Table<ReadyArticle, string>
  constructor() {
    super('feeder-playlist-audio')
    this.version(1).stores({ articles: '&id, at' })
  }
}

export interface PlaylistAudioState {
  playlistId: string | null
  ids: string[]
  ready: Set<string>
  failed: Set<string>
  preparingId: string | null
  progress: number
  bytes: number
  running: boolean
}

type Listener = (s: PlaylistAudioState) => void

/** The text read aloud for an article: its full text first for feeds set to it. */
export async function articleText(item: Item, feed: Feed | undefined, timeoutMs = 10_000): Promise<string> {
  if (item.fullHtml || !feed?.fullTextByDefault || !item.link || !isOnline()) return item.fullHtml || item.contentHtml || item.snippet
  try {
    return await Promise.race([loadFullText(item), new Promise<string>((_, no) => setTimeout(() => no(new Error('slow')), timeoutMs))])
  } catch {
    return item.contentHtml || item.snippet
  }
}

/** What the Now playing panel reads: one <p> per paragraph. */
function speechTextOf(paras: string[]): string {
  const div = document.createElement('div')
  for (const t of paras) {
    const p = document.createElement('p')
    p.textContent = t
    div.append(p)
  }
  return indexSpeechText(div).text
}

class PlaylistAudioManager {
  private adb: ArticlesDb | null = null
  private loaded: Promise<void> | null = null
  private listeners = new Set<Listener>()
  private ready = new Set<string>()
  private failed = new Set<string>()
  private batch: { playlistId: string; ids: string[]; voice: string; gpu: boolean } | null = null
  private preparing: { id: string; progress: number } | null = null
  private running = false
  private generation = 0
  state: PlaylistAudioState = { playlistId: null, ids: [], ready: new Set(), failed: new Set(), preparingId: null, progress: 0, bytes: 0, running: false }

  subscribe(l: Listener): () => void {
    this.listeners.add(l)
    l(this.state)
    void this.open()
    return () => this.listeners.delete(l)
  }

  private open(): Promise<void> {
    if (this.loaded) return this.loaded
    this.loaded = (async () => {
      if (typeof indexedDB === 'undefined') return
      this.adb = new ArticlesDb()
      const cutoff = Date.now() - STALE_DAYS * 86400_000
      for (const old of await this.adb.articles.where('at').below(cutoff).toArray()) await this.forget(old.id)
      for (const a of await this.adb.articles.toArray()) this.ready.add(a.id)
      try {
        this.batch = JSON.parse(localStorage.getItem(BATCH_KEY) ?? 'null')
      } catch {
        this.batch = null
      }
      this.emit()
      // A request left unfinished when Feeder was closed carries on
      if (this.batch?.ids.some((id) => !this.ready.has(id))) void this.run()
    })().catch((e) => console.warn('Playlist audio storage unavailable', e))
    return this.loaded
  }

  /** Only the natural voice can be prepared ahead (browser voices can't be recorded). */
  supported(voiceURI: string): boolean {
    return voiceURI.startsWith(KOKORO_PREFIX)
  }

  /** Prepares [ids] (articles of [playlistId], in play order). Replaces an earlier request. */
  async start(playlistId: string, ids: string[], voiceURI: string, gpu: boolean) {
    await this.open()
    this.generation++
    this.failed.clear()
    this.batch = { playlistId, ids, voice: voiceURI.slice(KOKORO_PREFIX.length), gpu }
    localStorage.setItem(BATCH_KEY, JSON.stringify(this.batch))
    this.emit()
    void this.run()
  }

  /** Stops preparing; what is ready stays. */
  cancel() {
    if (!this.batch) return
    this.generation++
    this.batch = { ...this.batch, ids: this.batch.ids.filter((id) => this.ready.has(id)) }
    localStorage.setItem(BATCH_KEY, JSON.stringify(this.batch))
    this.preparing = null
    this.emit()
  }

  /** Deletes the audio of the last request. */
  async removeAll() {
    this.generation++
    const ids = this.batch?.ids ?? []
    this.batch = null
    localStorage.removeItem(BATCH_KEY)
    for (const id of ids) await this.forget(id)
    this.emit()
  }

  /** The paragraphs an article was prepared from, so playing it reads the same text. */
  async parasFor(id: string, voiceURI: string): Promise<string[] | null> {
    await this.open()
    if (!this.ready.has(id) || !this.adb) return null
    const a = await this.adb.articles.get(id)
    return a && a.voice === voiceURI.slice(KOKORO_PREFIX.length) ? a.paras : null
  }

  /** The article played to the end: its audio is no longer needed. */
  async played(id: string) {
    await this.open()
    if (!this.ready.has(id)) return
    await this.forget(id)
    this.emit()
  }

  private async forget(id: string) {
    this.ready.delete(id)
    await this.adb?.articles.delete(id)
    await bookAudio.removeOwner(`item:${id}`)
  }

  private async run() {
    if (this.running) return
    this.running = true
    try {
      while (this.batch) {
        const gen = this.generation
        const b = this.batch
        const id = b.ids.find((x) => !this.ready.has(x) && !this.failed.has(x))
        if (!id) break
        try {
          if (!(await this.make(id, b.playlistId, b.voice, b.gpu, gen))) this.failed.add(id)
        } catch (e) {
          if (gen !== this.generation) continue
          console.warn('Preparing article audio failed', e)
          this.failed.add(id)
        }
      }
    } finally {
      this.preparing = null
      this.running = false
      this.emit()
    }
  }

  private async make(id: string, playlistId: string, voice: string, gpu: boolean, gen: number): Promise<boolean> {
    this.preparing = { id, progress: 0 }
    this.emit()
    const item = await db.items.get(id)
    if (!item) return false
    const feed = await db.feeds.get(item.feedUrl)
    const paras = paragraphsOf(await articleText(item, feed, 30_000))
    if (!paras.length) return false
    const lang = document.documentElement.lang || navigator.language || 'en'
    const pieces = kokoroPieces(speechTextOf(paras), lang)
    if (!pieces.length) return false
    await kokoro.load(gpu)
    for (let i = 0; i < pieces.length; i++) {
      if (gen !== this.generation) throw new Error('Cancelled')
      if (bookAudio.has(voice, pieces[i])) continue
      const audio = await kokoro.generate(pieces[i], voice, false, true).audio
      if (gen !== this.generation) throw new Error('Cancelled')
      await bookAudio.keep(`item:${id}`, 0, voice, pieces[i], audio)
      this.preparing = { id, progress: (i + 1) / pieces.length }
      this.emit()
    }
    await this.adb?.articles.put({ id, paras, playlistId, voice, at: Date.now() })
    this.ready.add(id)
    return true
  }

  private emit() {
    let bytes = 0
    for (const id of this.ready) bytes += bookAudio.ownerStats(`item:${id}`).bytes
    if (this.preparing) bytes += bookAudio.ownerStats(`item:${this.preparing.id}`).bytes
    const b = this.batch
    this.state = {
      playlistId: b?.playlistId ?? null,
      ids: b?.ids ?? [],
      ready: new Set(this.ready),
      failed: new Set(this.failed),
      preparingId: this.preparing?.id ?? null,
      progress: this.preparing?.progress ?? 0,
      bytes,
      running: this.running && !!b?.ids.some((x) => !this.ready.has(x) && !this.failed.has(x)),
    }
    this.listeners.forEach((l) => l(this.state))
  }
}

export const playlistAudio = new PlaylistAudioManager()
