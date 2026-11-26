/**
 * History: one log of everything consumed (articles, videos, episodes, audiobooks, ebooks, ZIM
 * pages) and of highlights, notes and feeds made or deleted, shown on the History page.
 * docs/central-history.md describes the fields; the Android app (model/history/CentralHistory.kt)
 * writes the same ones.
 *
 * Synced as kind "recent" (sync2.ts), one record per entry, the newest change wins. Removing an
 * entry sends { deleted: true }; Clear history sends key "*" with { before }, which drops every
 * entry up to then on every device. It is separate from the per-book timeline of history.ts
 * (kind "history"), whose opens and plays also land here.
 */
import { db, feedDisplayTitle, getKv, setKv, type Feed, type Highlight, type Item, type RecentEntry } from './db'
import { thisDevice } from './device'
import { FEED_KINDS_KEY, type FeedKind } from './home'
import { itemKind, loadPlaylists } from './playlists'
import { isWatched } from './videopos'
import { articleVideoId } from './youtube'
import { dateBucketKey, dateLabel } from './listquery'

export type RecentType = 'article' | 'youtube' | 'podcast' | 'audiobook' | 'ebook' | 'zim' | 'highlight' | 'note' | 'feed'
export type RecentAction = 'open' | 'create' | 'delete'

/** The synced value of an entry: everything but the local bookkeeping. */
export type RecentValue = Omit<RecentEntry, 'key' | 'ts' | 'synced' | 'del'>

export const TYPES: RecentType[] = ['article', 'youtube', 'podcast', 'audiobook', 'ebook', 'zim', 'highlight', 'note', 'feed']
const ACTIONS: RecentAction[] = ['open', 'create', 'delete']

/** Entries kept on a device, and for how long. */
export const MAX_ENTRIES = 2000
export const MAX_AGE_MS = 180 * 86_400_000
/** The key of the Clear history marker. */
export const CLEAR_KEY = '*'

export const recentKey = (t: string, i: string, a: string = 'open') => (a === 'open' ? `${t}:${i}` : `${t}:${i}:${a}`)

const httpOnly = (u: string | null | undefined) => (u && /^https?:\/\//i.test(u) ? u.slice(0, 2000) : undefined)
const clip = (s: string | null | undefined, n: number) => (s ? s.trim().slice(0, n) || undefined : undefined)

type Fields = Partial<RecentValue> & { t: RecentType; i: string; a?: RecentAction }

/** When the history was last cleared (0 = never). */
async function clearedBefore(): Promise<number> {
  const m = await db.recent.get(CLEAR_KEY)
  return m?.at ?? 0
}

/**
 * Notes an entry (or updates it). [touch] moves it to the top (a new open); otherwise only what
 * it says changes (progress). [create] = false only updates an entry that is already there.
 */
export async function noteRecent(f: Fields, opts: { touch?: boolean; create?: boolean; now?: number } = {}): Promise<RecentEntry | null> {
  if (!f.i) return null
  const a = f.a ?? 'open'
  const key = recentKey(f.t, f.i, a)
  const now = opts.now ?? Date.now()
  const touch = opts.touch ?? true
  let saved: RecentEntry | null = null
  await db.transaction('rw', db.recent, async () => {
    const old = await db.recent.get(key)
    const live = old && !old.del ? old : null
    if (!live && opts.create === false) return
    const base: RecentEntry = live ?? { key, t: f.t, a, i: f.i, n: '', at: now, ts: 0, synced: 0 }
    const next: RecentEntry = { ...base, del: undefined, synced: 0 }
    for (const [k, v] of Object.entries(f) as [keyof RecentValue, unknown][]) {
      if (v === undefined || v === null || v === '') continue
      // Played from a playlist once: it stays a playlist item
      if ((k === 'pl' || k === 'pn') && base.pl) continue
      ;(next as unknown as Record<string, unknown>)[k] = v
    }
    if (touch || !live) next.at = f.at ?? now
    // Backfilled entries keep the device that made them
    if (!f.dv) Object.assign(next, thisDevice())
    next.ts = Math.max(now, (old?.ts ?? 0) + 1)
    delete next.del
    if (live && !touch && sameValue(live, next)) return
    await db.recent.put(next)
    saved = next
  })
  if (saved) void trim(now).catch(() => undefined)
  return saved
}

function sameValue(a: RecentEntry, b: RecentEntry): boolean {
  return JSON.stringify(recentValue(a)) === JSON.stringify(recentValue(b))
}

/** Removes one entry from history here and, with the next sync, everywhere. Never the thing itself. */
export async function removeRecent(key: string, now = Date.now()): Promise<void> {
  await db.transaction('rw', db.recent, async () => {
    const old = await db.recent.get(key)
    if (!old || old.del) return
    await db.recent.put({ key, t: old.t, a: old.a, i: old.i, n: old.n, at: old.at, ts: Math.max(now, old.ts + 1), del: 1, synced: 0 })
  })
}

/** Clears all history, here and on every device. */
export async function clearRecent(now = Date.now()): Promise<void> {
  await db.transaction('rw', db.recent, async () => {
    const before = Math.max(now, await clearedBefore())
    await db.recent.clear()
    await db.recent.put({ key: CLEAR_KEY, t: 'article', a: 'open', i: CLEAR_KEY, n: '', at: before, ts: before, synced: 0 })
  })
}

let trimming = false
/** Keeps the newest MAX_ENTRIES, none older than MAX_AGE_MS (here only; nothing is sent). */
export async function trim(now = Date.now()): Promise<void> {
  if (trimming) return
  trimming = true
  try {
    const old = await db.recent.where('at').below(now - MAX_AGE_MS).filter((e) => e.key !== CLEAR_KEY).primaryKeys()
    if (old.length) await db.recent.bulkDelete(old)
    const n = (await db.recent.count()) - ((await db.recent.get(CLEAR_KEY)) ? 1 : 0)
    if (n > MAX_ENTRIES) {
      const extra = await db.recent.orderBy('at').filter((e) => e.key !== CLEAR_KEY).limit(n - MAX_ENTRIES).primaryKeys()
      await db.recent.bulkDelete(extra)
    }
  } finally {
    trimming = false
  }
}

/** Every entry to show, newest first. */
export async function recentEntries(): Promise<RecentEntry[]> {
  const all = await db.recent.orderBy('at').reverse().toArray()
  return all.filter((e) => !e.del && e.key !== CLEAR_KEY)
}

// ---------------------------------------------------------------------------------------------
// What gets noted

async function feedKinds(): Promise<Record<string, FeedKind>> {
  return getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {})
}

/** The History type of an article, video or episode. */
export function typeOfItem(item: Pick<Item, 'link' | 'contentHtml' | 'enclosureUrl'>, kind?: FeedKind): 'article' | 'youtube' | 'podcast' {
  return itemKind(item, kind)
}

/** An article, video or episode was opened (in the reader, the video page or the player). */
export async function noteItemOpened(itemId: string, from?: { playlistId?: string | null; playlistName?: string } | null): Promise<void> {
  const item = await db.items.get(itemId)
  if (!item) return
  const feed = await db.feeds.get(item.feedUrl)
  const t = typeOfItem(item, (await feedKinds())[item.feedUrl])
  const pos = t === 'article' ? undefined : await db.videoPos.get(itemId)
  let pn = from?.playlistName
  if (from?.playlistId && !pn) pn = (await loadPlaylists()).find((p) => p.id === from.playlistId)?.name
  await noteRecent({
    t,
    i: itemId,
    n: clip(item.title, 300) ?? '(untitled)',
    s: clip(item.author && t === 'youtube' ? feedDisplayTitle(feed) || item.author : feedDisplayTitle(feed), 200),
    img: httpOnly(item.imageUrl),
    u: httpOnly(item.link),
    ...(t === 'youtube' ? { x: articleVideoId(item) ?? undefined } : {}),
    ...(t === 'article' ? { d: 1 } : progressOf(pos?.t ?? 0, pos?.d || item.duration || 0)),
    ...(from?.playlistId ? { pl: from.playlistId, pn: clip(pn, 120) } : {}),
  })
}

function progressOf(t: number, d: number): Partial<RecentValue> {
  const out: Partial<RecentValue> = {}
  if (t > 0) out.pos = Math.round(t)
  if (d > 0) {
    out.dur = Math.round(d)
    out.f = Math.round(Math.min(1, t / d) * 10000) / 10000
    if (isWatched({ t, d })) out.d = 1
  }
  return out
}

/** A video's or episode's saved place changed: its entry follows (only when it has one). */
export async function noteItemProgress(itemId: string, t: number, d: number): Promise<void> {
  const key = (await db.recent.get(recentKey('youtube', itemId))) ? 'youtube' : (await db.recent.get(recentKey('podcast', itemId))) ? 'podcast' : null
  if (!key) return
  await noteRecent({ t: key, i: itemId, ...progressOf(t, d) }, { touch: false, create: false })
}

/** From history.ts: an audiobook played or paused, an ebook or ZIM site opened or closed. */
export async function noteFromEvent(c: 'audiobook' | 'ebook' | 'zim', i: string, a: string, w: { p?: string; s?: string; f?: number; x?: string }): Promise<void> {
  const starts = a === 'open' || a === 'play'
  if (c === 'zim') {
    if (!w.x && starts) return noteZimPage(i, '', w.p ?? '')
    if (w.x) return noteZimPage(i, w.x, w.p ?? '', starts)
    return
  }
  const f = typeof w.f === 'number' && Number.isFinite(w.f) ? Math.round(w.f * 10000) / 10000 : undefined
  const done = f !== undefined && f >= 0.99 ? 1 : undefined
  if (c === 'audiobook') {
    const b = await db.audiobooks.get(i)
    await noteRecent({ t: 'audiobook', i, n: clip(b?.title, 300) ?? 'Audiobook', s: clip(b?.author, 200), img: httpOnly(b?.cover), f, d: done, x: w.x, q: undefined }, { touch: starts, create: starts })
  } else {
    const b = await db.books.get(i)
    await noteRecent({ t: 'ebook', i, n: clip(b?.title, 300) ?? 'Ebook', s: clip(b?.author, 200), f, d: done, x: w.x }, { touch: starts, create: starts })
  }
}

/** A ZIM page was shown. Each page is its own entry, with the site as its source. */
export async function noteZimPage(bookId: string, path: string, title: string, touch = true): Promise<void> {
  let site = ''
  try {
    const { listBooks } = await import('./zim/library')
    site = (await listBooks()).books.find((b) => b.id === bookId)?.title ?? ''
  } catch {
    /* no OPFS (tests, old browsers) */
  }
  await noteRecent({ t: 'zim', i: `${bookId}\n${path}`, n: clip(title, 300) || site || bookId, s: clip(site, 200), x: path || undefined }, { touch, create: touch })
}

/** A highlight was made or deleted, or its note added or cleared. [before] is how it was. */
export async function noteHighlightChange(before: Highlight | null | undefined, after: Highlight | null | undefined): Promise<void> {
  const h = after ?? before
  if (!h) return
  const snap = (t: 'highlight' | 'note', a: RecentAction, q: string): Fields => ({
    t,
    a,
    i: h.id,
    n: clip(h.itemTitle, 300) || 'an article',
    s: clip(h.feedTitle, 200),
    q: clip(q, 280),
    c: h.color,
    x: h.itemId,
    u: httpOnly(h.itemLink),
    img: httpOnly(h.imageUrl),
  })
  if (!before && after) {
    await noteRecent(snap('highlight', 'create', after.text))
    if (after.note.trim()) await noteRecent(snap('note', 'create', after.note))
    return
  }
  if (before && !after) {
    await noteRecent(snap('highlight', 'delete', before.text))
    if (before.note.trim()) await noteRecent(snap('note', 'delete', before.note))
    return
  }
  if (before && after) {
    const had = before.note.trim()
    const has = after.note.trim()
    if (!had && has) await noteRecent(snap('note', 'create', has))
    else if (had && !has) await noteRecent(snap('note', 'delete', had))
    else if (has && had !== has) await noteRecent(snap('note', 'create', has), { touch: false, create: false })
  }
}

/** A feed was subscribed to or unsubscribed from (not when sync brings it). */
export async function noteFeedChange(a: 'create' | 'delete', feed: Pick<Feed, 'url' | 'title' | 'customTitle' | 'imageUrl'>): Promise<void> {
  await noteRecent({ t: 'feed', a, i: feed.url, n: clip(feedDisplayTitle(feed), 300) ?? feed.url, u: httpOnly(feed.url), img: httpOnly(feed.imageUrl) })
}

/** Before deleting feeds: notes each one as unsubscribed. */
export async function noteFeedsDeleted(urls: string[]): Promise<void> {
  for (const f of await db.feeds.bulkGet(urls)) if (f) await noteFeedChange('delete', f)
}

// ---------------------------------------------------------------------------------------------
// Backfill: once per browser, from what is already known

const BACKFILL_KEY = 'recentBackfilled'

/**
 * Fills History from video and episode positions, ebook and audiobook places and the per-book
 * timeline (history.ts). Read articles are left out: Mark all read would flood it.
 */
export async function backfillRecent(now = Date.now()): Promise<number> {
  if (await getKv<boolean>(BACKFILL_KEY, false)) return 0
  await setKv(BACKFILL_KEY, true)
  const since = now - MAX_AGE_MS
  const cleared = await clearedBefore()
  let n = 0
  const add = async (f: Fields) => {
    if (!f.at || f.at <= Math.max(since, cleared)) return
    if (await db.recent.get(recentKey(f.t, f.i, f.a))) return
    if (await noteRecent(f, { now })) n++
  }
  const kinds = await feedKinds()
  for (const p of await db.videoPos.toArray()) {
    const item = await db.items.get(p.id)
    if (!item) continue
    const feed = await db.feeds.get(item.feedUrl)
    const t = typeOfItem(item, kinds[item.feedUrl])
    if (t === 'article') continue
    await add({
      t,
      i: p.id,
      at: p.ts,
      n: clip(item.title, 300) ?? '(untitled)',
      s: clip(feedDisplayTitle(feed), 200),
      img: httpOnly(item.imageUrl),
      u: httpOnly(item.link),
      ...(t === 'youtube' ? { x: articleVideoId(item) ?? undefined } : {}),
      ...progressOf(p.t, p.d || item.duration || 0),
      ...(p.dv && p.dn ? { dv: p.dv, dn: p.dn } : {}),
    })
  }
  for (const b of await db.books.toArray()) {
    const pos = await db.bookPos.get(b.id)
    const at = pos?.ts ?? b.lastOpenedAt ?? 0
    if (!at) continue
    await add({ t: 'ebook', i: b.id, at, n: clip(b.title, 300) ?? 'Ebook', s: clip(b.author, 200), f: pos?.f, x: pos?.cfi })
  }
  for (const b of await db.audiobooks.toArray()) {
    const pos = await db.audioPos.get(b.id)
    const at = pos?.ts ?? b.lastOpenedAt ?? 0
    if (!at) continue
    await add({ t: 'audiobook', i: b.id, at, n: clip(b.title, 300) ?? 'Audiobook', s: clip(b.author, 200), img: httpOnly(b.cover), f: pos?.f })
  }
  // The newest open of each ZIM page in the per-book timeline
  const zimSeen = new Set<string>()
  for (const e of await db.history.orderBy('at').reverse().toArray()) {
    if (e.c !== 'zim' || !e.x || e.a !== 'open') continue
    const id = `${e.i}\n${e.x}`
    if (zimSeen.has(id)) continue
    zimSeen.add(id)
    await add({ t: 'zim', i: id, at: e.at, n: clip(e.p, 300) || e.i, x: e.x, ...(e.dv && e.dn ? { dv: e.dv, dn: e.dn } : {}) })
  }
  return n
}

// ---------------------------------------------------------------------------------------------
// Sync

/** The value sent for an entry (also the Clear history marker and removals). */
export function recentValue(e: RecentEntry): unknown {
  if (e.key === CLEAR_KEY) return { before: e.at }
  if (e.del) return { deleted: true }
  const v: Record<string, unknown> = {}
  for (const k of ['t', 'a', 'i', 'n', 's', 'img', 'at', 'f', 'pos', 'dur', 'd', 'pl', 'pn', 'x', 'q', 'c', 'u', 'dv', 'dn'] as const) {
    const x = e[k]
    if (x !== undefined && x !== null && x !== '') v[k] = x
  }
  return v
}

/** Reads an entry from another device, or null. */
export function entryFromValue(key: string, v: unknown, ts: number): RecentEntry | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  if (o.deleted === true) return { key, t: 'article', a: 'open', i: '', n: '', at: ts, ts, del: 1, synced: 1 }
  const t = o.t as RecentType
  const a = (o.a ?? 'open') as RecentAction
  if (!TYPES.includes(t) || !ACTIONS.includes(a) || typeof o.i !== 'string' || !o.i) return null
  if (recentKey(t, o.i, a) !== key) return null
  const str = (x: unknown, n: number) => (typeof x === 'string' && x ? x.slice(0, n) : undefined)
  const num = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : undefined)
  const e: RecentEntry = {
    key,
    t,
    a,
    i: o.i,
    n: str(o.n, 300) ?? '',
    s: str(o.s, 200),
    img: httpOnly(str(o.img, 2000)),
    at: num(o.at) ?? ts,
    f: num(o.f) === undefined ? undefined : Math.max(0, Math.min(1, num(o.f)!)),
    pos: num(o.pos),
    dur: num(o.dur),
    d: o.d === 1 || o.d === true ? 1 : undefined,
    pl: str(o.pl, 200),
    pn: str(o.pn, 120),
    x: str(o.x, 2000),
    q: str(o.q, 280),
    c: str(o.c, 12) as RecentEntry['c'],
    u: httpOnly(str(o.u, 2000)),
    dv: str(o.dv, 100),
    dn: str(o.dn, 100),
    ts,
    synced: 1,
  }
  for (const k of Object.keys(e) as (keyof RecentEntry)[]) if (e[k] === undefined) delete e[k]
  return e
}

/** Applies a record from another device: the newest change wins. Returns true when it changed something. */
export async function applyRecent(key: string, v: unknown, ts: number): Promise<boolean> {
  if (key === CLEAR_KEY) {
    const before = v && typeof v === 'object' ? Number((v as { before?: unknown }).before) : NaN
    if (!Number.isFinite(before)) return false
    let changed = false
    await db.transaction('rw', db.recent, async () => {
      const mine = await db.recent.get(CLEAR_KEY)
      if (mine && mine.at >= before) return
      const gone = await db.recent.where('at').belowOrEqual(before).primaryKeys()
      await db.recent.bulkDelete(gone)
      await db.recent.put({ key: CLEAR_KEY, t: 'article', a: 'open', i: CLEAR_KEY, n: '', at: before, ts: Math.max(ts, before), synced: 1 })
      changed = true
    })
    return changed
  }
  const e = entryFromValue(key, v, ts)
  if (!e) return false
  let changed = false
  await db.transaction('rw', db.recent, async () => {
    const mine = await db.recent.get(key)
    if (mine && mine.ts >= ts) return
    if (!e.del && e.at <= (await clearedBefore())) return
    await db.recent.put(e)
    changed = true
  })
  if (changed) void trim().catch(() => undefined)
  return changed
}

// ---------------------------------------------------------------------------------------------
// Showing it

export type RecentFilter = 'all' | 'article' | 'youtube' | 'podcast' | 'playlist' | 'books' | 'marks' | 'feed'

export const FILTERS: { id: RecentFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'article', label: 'Articles' },
  { id: 'youtube', label: 'YouTube' },
  { id: 'podcast', label: 'Podcasts' },
  { id: 'playlist', label: 'Playlists' },
  { id: 'books', label: 'Books' },
  { id: 'marks', label: 'Highlights & notes' },
  { id: 'feed', label: 'Feeds' },
]

export function matchesFilter(e: Pick<RecentEntry, 't' | 'pl'>, f: RecentFilter): boolean {
  if (f === 'all') return true
  if (f === 'playlist') return !!e.pl
  if (f === 'marks') return e.t === 'highlight' || e.t === 'note'
  if (f === 'books') return e.t === 'audiobook' || e.t === 'ebook' || e.t === 'zim'
  return e.t === f
}

export function matchesQuery(e: Pick<RecentEntry, 'n' | 's' | 'q' | 'pn'>, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return [e.n, e.s, e.q, e.pn].some((x) => x?.toLowerCase().includes(q))
}

/** Mail-style groups (Today, Yesterday, This week…), newest first. */
export function groupRecent<T extends Pick<RecentEntry, 'at'>>(entries: T[], now = new Date()): { key: string; label: string; entries: T[] }[] {
  const out: { key: string; label: string; entries: T[] }[] = []
  for (const e of [...entries].sort((a, b) => b.at - a.at)) {
    const key = dateBucketKey(e.at, now)
    const last = out[out.length - 1]
    if (last?.key === key) last.entries.push(e)
    else out.push({ key, label: dateLabel(key), entries: [e] })
  }
  return out
}

function clock(sec: number): string {
  const s = Math.max(0, Math.floor(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** "Read", "Watched", "12:03 / 40:00", "42%", or ''. */
export function progressText(e: Pick<RecentEntry, 't' | 'a' | 'f' | 'pos' | 'dur' | 'd'>): string {
  if (e.a !== 'open') return ''
  if (e.t === 'article') return e.d ? 'Read' : ''
  if (e.t === 'zim') return ''
  if (e.d) return e.t === 'youtube' ? 'Watched' : e.t === 'ebook' ? 'Finished' : e.t === 'podcast' ? 'Played' : 'Finished'
  if ((e.t === 'youtube' || e.t === 'podcast') && e.pos) return e.dur ? `${clock(e.pos)} / ${clock(e.dur)}` : clock(e.pos)
  if (typeof e.f === 'number' && e.f > 0) return `${Math.max(1, Math.round(e.f * 100))}%`
  return ''
}

/** The title line: the item's title, or "Highlighted in …" / "Subscribed to …" for changes. */
export function titleText(e: Pick<RecentEntry, 't' | 'a' | 'n'>): string {
  if (e.t === 'highlight') return e.a === 'delete' ? `Deleted highlight in ${e.n}` : `Highlighted in ${e.n}`
  if (e.t === 'note') return e.a === 'delete' ? `Deleted note on ${e.n}` : `Added note on ${e.n}`
  if (e.t === 'feed') return e.a === 'delete' ? `Deleted feed ${e.n}` : `Subscribed to ${e.n}`
  return e.n || '(untitled)'
}

export const TYPE_LABEL: Record<RecentType, string> = {
  article: 'Article',
  youtube: 'YouTube',
  podcast: 'Podcast',
  audiobook: 'Audiobook',
  ebook: 'Ebook',
  zim: 'ZIM page',
  highlight: 'Highlight',
  note: 'Note',
  feed: 'Feed',
}

/** "the article", "the video"…: what removing an entry does not delete. */
export function thingText(t: RecentType): string {
  return { article: 'article', youtube: 'video', podcast: 'episode', audiobook: 'audiobook', ebook: 'ebook', zim: 'page', highlight: 'highlight', note: 'note', feed: 'feed' }[t]
}
