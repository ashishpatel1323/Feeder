/**
 * Playlists to listen to (for example while driving), like a podcast app's episode filters.
 *
 * - Smart playlists keep a set of rules (folders and feeds, type, status, published within,
 *   length) and show whatever matches right now, so played items drop off by themselves.
 * - My playlists are lists you fill by hand ("+ Playlist" on any article, episode or video)
 *   and put in order.
 *
 * Articles are read aloud; podcast episodes and saved YouTube videos play as audio.
 * Playlists are kept in this browser only (like the Downloads order).
 */
import { db, getKv, setKv, type Feed, type Item, type VideoPos } from './db'
import { FEED_KINDS_KEY, type FeedKind } from './home'
import { wordsOf } from './listquery'
import { CHARS_PER_SECOND } from './readaloud'
import { articleVideoId } from './youtube'
import { knownDurations } from './downloads'
import { isWatched } from './videopos'

export type ItemKind = 'podcast' | 'youtube' | 'article'
export const ITEM_KINDS: ItemKind[] = ['podcast', 'youtube', 'article']
export const ITEM_KIND_LABELS: Record<ItemKind, string> = { podcast: 'Podcast', youtube: 'YouTube', article: 'Article' }

export type PlayStatus = 'unread' | 'inprogress' | 'read'
export const PLAY_STATUSES: PlayStatus[] = ['unread', 'inprogress', 'read']
export const PLAY_STATUS_LABELS: Record<PlayStatus, string> = { unread: 'Unread', inprogress: 'In progress', read: 'Read' }

export type PlaylistSort = 'newest' | 'oldest' | 'shortest' | 'longest'
export const PLAYLIST_SORTS: PlaylistSort[] = ['newest', 'oldest', 'shortest', 'longest']
export const SORT_LABELS: Record<PlaylistSort, string> = { newest: 'Newest', oldest: 'Oldest', shortest: 'Shortest', longest: 'Longest' }

export interface SmartRules {
  /** Folder names; with [feeds], an item matches when it is in either. Both empty = every feed. */
  folders: string[]
  feeds: string[]
  /** Empty = every type. */
  kinds: ItemKind[]
  /** Empty = any status. */
  statuses: PlayStatus[]
  savedOnly: boolean
  downloadedOnly: boolean
  /** Published in the last this many days; null = any time. */
  withinDays: number | null
  /** Length bounds in minutes; null = no bound. Articles count as their read-aloud time. */
  minMinutes: number | null
  maxMinutes: number | null
}

export interface Playlist {
  id: string
  name: string
  /** A short emoji or symbol shown on the colored tile. */
  icon: string
  color: string
  smart: boolean
  rules: SmartRules
  /** My playlists: the items in order. Unused for smart playlists. */
  itemIds: string[]
  sort: PlaylistSort
  createdAt: number
}

export const PLAYLISTS_KEY = 'playlists'
const SEEDED_KEY = 'playlistsSeeded'
/** At most this many items in a smart playlist, so a broad rule still plays sensibly. */
export const SMART_LIMIT = 200

export const PLAYLIST_COLORS = ['#1d4ed8', '#7c3aed', '#0e7490', '#be123c', '#a16207', '#15803d', '#c2410c', '#9d174d']
export const PLAYLIST_ICONS = ['🚗', '🎙', '📰', '▶', '★', '☕', '🏃', '🌙', '🎧', '📚', '🧠', '✈']

export const EMPTY_RULES: SmartRules = {
  folders: [],
  feeds: [],
  kinds: [],
  statuses: [],
  savedOnly: false,
  downloadedOnly: false,
  withinDays: null,
  minMinutes: null,
  maxMinutes: null,
}

const AUDIO = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac)(\?|#|$)/i
const NOT_AUDIO = /\.(jpe?g|png|gif|webp|avif|svg|pdf)(\?|#|$)/i

/** True when the attachment looks like playable audio (or a podcast's video file). */
export function isPlayableEnclosure(url: string | null | undefined): boolean {
  return !!url && (AUDIO.test(url) || !NOT_AUDIO.test(url))
}

/**
 * What one item is: a YouTube video (its link or content points at one), a podcast episode
 * (an audio attachment), or an article to read aloud. A feed set to Text on its page always
 * gives articles.
 */
export function itemKind(item: Pick<Item, 'link' | 'contentHtml' | 'enclosureUrl'>, feedKind?: FeedKind): ItemKind {
  if (feedKind === 'text') return 'article'
  if (articleVideoId(item)) return 'youtube'
  if (isPlayableEnclosure(item.enclosureUrl)) return 'podcast'
  return 'article'
}

/** What the rules and the list need to know about the world besides the items themselves. */
export interface PlaylistContext {
  feeds: Map<string, Feed>
  feedKinds: Record<string, FeedKind>
  positions: Map<string, Pick<VideoPos, 't' | 'd'>>
  /** YouTube video id → seconds, learned when a video was saved or played. */
  videoDurations: Record<string, number>
  /** Read-aloud speed, for an article's listening time. */
  rate: number
  now: number
}

export function makeContext(p: Partial<PlaylistContext> & Pick<PlaylistContext, 'feeds'>): PlaylistContext {
  return { feedKinds: {}, positions: new Map(), videoDurations: {}, rate: 1, now: Date.now(), ...p }
}

export const kindOf = (item: Item, ctx: PlaylistContext) => itemKind(item, ctx.feedKinds[item.feedUrl])

/** About how long reading the article aloud takes, in seconds. */
export function readAloudSeconds(words: number, rate = 1): number {
  // An average English word plus its space is about six characters
  return Math.max(30, Math.round((words * 6) / CHARS_PER_SECOND / Math.max(0.25, rate)))
}

/** Seconds to listen to the item (articles at the read-aloud speed), null when not known yet. */
export function itemSeconds(item: Item, ctx: PlaylistContext): number | null {
  const kind = kindOf(item, ctx)
  if (kind === 'article') return readAloudSeconds(wordsOf(item, ctx.feeds.get(item.feedUrl)), ctx.rate)
  const pos = ctx.positions.get(item.id)
  if (kind === 'youtube') {
    const vid = articleVideoId(item)
    const d = (vid && ctx.videoDurations[vid]) || pos?.d || item.duration
    return d ? d : null
  }
  return item.duration || pos?.d || null
}

export function playStatus(item: Item, ctx: PlaylistContext): PlayStatus {
  const pos = ctx.positions.get(item.id)
  if (pos && pos.t > 0 && !isWatched(pos) && !item.read) return 'inprogress'
  return item.read ? 'read' : 'unread'
}

export const isDownloadedItem = (item: Item) => !!item.offlineAt || !!item.offlineVideo

export function matchesRules(item: Item, rules: SmartRules, ctx: PlaylistContext): boolean {
  if (rules.folders.length || rules.feeds.length) {
    const tag = ctx.feeds.get(item.feedUrl)?.tag ?? ''
    if (!rules.feeds.includes(item.feedUrl) && !(tag && rules.folders.includes(tag))) return false
  }
  if (rules.kinds.length && !rules.kinds.includes(kindOf(item, ctx))) return false
  if (rules.statuses.length && !rules.statuses.includes(playStatus(item, ctx))) return false
  if (rules.savedOnly && !item.saved) return false
  if (rules.downloadedOnly && !isDownloadedItem(item)) return false
  if (rules.withinDays != null && item.sortTime < ctx.now - rules.withinDays * 86_400_000) return false
  if (rules.minMinutes != null || rules.maxMinutes != null) {
    const s = itemSeconds(item, ctx)
    // An episode of unknown length can't be said to fit
    if (s == null) return false
    if (rules.minMinutes != null && s < rules.minMinutes * 60) return false
    if (rules.maxMinutes != null && s > rules.maxMinutes * 60) return false
  }
  return true
}

export function sortItems(items: Item[], sort: PlaylistSort, ctx: PlaylistContext): Item[] {
  const len = (i: Item) => itemSeconds(i, ctx) ?? Number.MAX_SAFE_INTEGER
  const out = [...items]
  switch (sort) {
    case 'newest':
      return out.sort((a, b) => b.sortTime - a.sortTime)
    case 'oldest':
      return out.sort((a, b) => a.sortTime - b.sortTime)
    case 'shortest':
      return out.sort((a, b) => len(a) - len(b) || b.sortTime - a.sortTime)
    case 'longest':
      return out.sort((a, b) => (itemSeconds(b, ctx) ?? -1) - (itemSeconds(a, ctx) ?? -1) || b.sortTime - a.sortTime)
  }
}

/** The items of a playlist right now, in play order. */
export function playlistItems(pl: Playlist, items: Item[], ctx: PlaylistContext, byId?: Map<string, Item>): Item[] {
  if (!pl.smart) {
    const map = byId ?? new Map(items.map((i) => [i.id, i]))
    return pl.itemIds.map((id) => map.get(id)).filter((i): i is Item => !!i)
  }
  return sortItems(
    items.filter((i) => matchesRules(i, pl.rules, ctx)),
    pl.sort,
    ctx,
  ).slice(0, SMART_LIMIT)
}

/** Total listening time in seconds, and how many items have no known length. */
export function totalSeconds(items: Item[], ctx: PlaylistContext): { seconds: number; unknown: number } {
  let seconds = 0
  let unknown = 0
  for (const i of items) {
    const s = itemSeconds(i, ctx)
    if (s == null) unknown++
    else seconds += s
  }
  return { seconds, unknown }
}

/** "2 h 05 m", "48 min", "under a minute". */
export function formatTotal(seconds: number): string {
  const m = Math.round(seconds / 60)
  if (m < 1) return 'under a minute'
  if (m < 60) return `${m} min`
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} m`
}

/** Short text for each rule that is set, for the chips at the top of a smart playlist. */
export function describeRules(r: SmartRules, feeds: Map<string, Feed>): { label: string; value: string }[] {
  const out: { label: string; value: string }[] = []
  const names = [...r.folders, ...r.feeds.map((u) => feeds.get(u)?.customTitle || feeds.get(u)?.title || u)]
  if (names.length) out.push({ label: r.feeds.length && !r.folders.length ? 'Feeds' : 'From', value: names.join(', ') })
  if (r.kinds.length) out.push({ label: 'Type', value: r.kinds.map((k) => ITEM_KIND_LABELS[k]).join(' · ') })
  if (r.statuses.length) out.push({ label: '', value: r.statuses.map((s) => PLAY_STATUS_LABELS[s]).join(' or ') })
  if (r.savedOnly) out.push({ label: '', value: 'Saved only' })
  if (r.downloadedOnly) out.push({ label: '', value: 'Downloaded only' })
  if (r.withinDays != null) out.push({ label: 'Published', value: withinLabel(r.withinDays) })
  if (r.minMinutes != null && r.maxMinutes != null) out.push({ label: 'Length', value: `${r.minMinutes}–${r.maxMinutes} min` })
  else if (r.maxMinutes != null) out.push({ label: 'Length', value: `under ${r.maxMinutes} min` })
  else if (r.minMinutes != null) out.push({ label: 'Length', value: `over ${r.minMinutes} min` })
  return out
}

export const WITHIN_CHOICES: (number | null)[] = [1, 2, 7, 14, 30, null]
export function withinLabel(days: number | null): string {
  if (days == null) return 'Any time'
  if (days === 1) return 'last 24 hours'
  if (days === 7) return 'last week'
  if (days === 14) return 'last 2 weeks'
  if (days === 30) return 'last month'
  return `last ${days} days`
}

let counter = 0
export function newPlaylistId(now = Date.now()): string {
  counter++
  return `pl-${now.toString(36)}-${counter.toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

export function newPlaylist(p: Partial<Playlist> & Pick<Playlist, 'name' | 'smart'>, existing: Playlist[] = []): Playlist {
  const used = new Set(existing.map((x) => x.color))
  return {
    id: newPlaylistId(),
    icon: p.smart ? '⚡' : '★',
    color: PLAYLIST_COLORS.find((c) => !used.has(c)) ?? PLAYLIST_COLORS[existing.length % PLAYLIST_COLORS.length],
    rules: { ...EMPTY_RULES },
    itemIds: [],
    sort: 'newest',
    createdAt: Date.now(),
    ...p,
  }
}

/** The three playlists a new user starts with, so the page isn't empty. */
export function starterPlaylists(): Playlist[] {
  const list: Playlist[] = []
  list.push(
    newPlaylist(
      {
        name: 'Morning drive',
        smart: true,
        icon: '🚗',
        rules: { ...EMPTY_RULES, kinds: ['podcast', 'article'], statuses: ['unread', 'inprogress'], withinDays: 2, maxMinutes: 40 },
      },
      list,
    ),
  )
  list.push(newPlaylist({ name: 'New podcast episodes', smart: true, icon: '🎙', rules: { ...EMPTY_RULES, kinds: ['podcast'], statuses: ['unread', 'inprogress'], withinDays: 7 } }, list))
  list.push(newPlaylist({ name: 'Short reads', smart: true, icon: '📰', rules: { ...EMPTY_RULES, kinds: ['article'], statuses: ['unread'], maxMinutes: 8 } }, list))
  return list
}

// ---- Storage (IndexedDB key-value row, this browser only) ----

export async function loadPlaylists(): Promise<Playlist[]> {
  const list = await getKv<Playlist[]>(PLAYLISTS_KEY, [])
  return list.map((p) => ({ ...p, rules: { ...EMPTY_RULES, ...p.rules }, itemIds: p.itemIds ?? [] }))
}

/** Creates the starter playlists the first time Playlists are used in this browser. */
export async function seedPlaylists(): Promise<void> {
  await db.transaction('rw', db.kv, async () => {
    if (await getKv<boolean>(SEEDED_KEY, false)) return
    if (!(await getKv<Playlist[]>(PLAYLISTS_KEY, [])).length) await setKv(PLAYLISTS_KEY, starterPlaylists())
    await setKv(SEEDED_KEY, true)
  })
}

async function change(fn: (list: Playlist[]) => Playlist[]): Promise<Playlist[]> {
  return db.transaction('rw', db.kv, async () => {
    const next = fn(await loadPlaylists())
    await setKv(PLAYLISTS_KEY, next)
    await setKv(SEEDED_KEY, true)
    return next
  })
}

export const savePlaylist = (pl: Playlist) => change((list) => (list.some((p) => p.id === pl.id) ? list.map((p) => (p.id === pl.id ? pl : p)) : [...list, pl]))
export const deletePlaylist = (id: string) => change((list) => list.filter((p) => p.id !== id))
export const restorePlaylists = (list: Playlist[]) => change(() => list)

/** Adds items to the end of a manual playlist, skipping ones already in it. */
export function withItems(pl: Playlist, ids: string[]): Playlist {
  const out = [...pl.itemIds]
  const have = new Set(out)
  for (const id of ids) {
    if (have.has(id)) continue
    have.add(id)
    out.push(id)
  }
  return { ...pl, itemIds: out }
}

export const withoutItems = (pl: Playlist, ids: string[]): Playlist => ({ ...pl, itemIds: pl.itemIds.filter((id) => !ids.includes(id)) })

export function moveItem(pl: Playlist, id: string, to: number): Playlist {
  const ids = pl.itemIds.filter((x) => x !== id)
  ids.splice(Math.max(0, Math.min(to, ids.length)), 0, id)
  return { ...pl, itemIds: ids }
}

export async function addToPlaylist(playlistId: string, ids: string[]) {
  return change((list) => list.map((p) => (p.id === playlistId && !p.smart ? withItems(p, ids) : p)))
}

export async function removeFromPlaylist(playlistId: string, ids: string[]) {
  return change((list) => list.map((p) => (p.id === playlistId ? withoutItems(p, ids) : p)))
}

/** Played to the end: drop it from every manual playlist (smart ones drop it by their rules). */
export async function removeFromAllManual(id: string): Promise<string[]> {
  const from: string[] = []
  await change((list) =>
    list.map((p) => {
      if (p.smart || !p.itemIds.includes(id)) return p
      from.push(p.id)
      return withoutItems(p, [id])
    }),
  )
  return from
}

/** Reads what the rules need from the database: per-feed type overrides, positions, durations. */
export async function loadContext(feeds: Map<string, Feed>, rate: number): Promise<PlaylistContext> {
  const [feedKinds, positions] = await Promise.all([getKv<Record<string, FeedKind>>(FEED_KINDS_KEY, {}), db.videoPos.toArray()])
  return makeContext({ feeds, feedKinds, positions: new Map(positions.map((p) => [p.id, p])), videoDurations: knownDurations(), rate })
}
