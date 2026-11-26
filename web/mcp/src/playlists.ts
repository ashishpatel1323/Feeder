/** Playlists as synced, with smart rules evaluated the way web/src/lib/playlists.ts does. */
import type { Db } from './db.js'
import { itemKind, readAloudSeconds, articleVideoId, type ItemKind } from './kinds.js'

export type PlayStatus = 'unread' | 'inprogress' | 'read'

export interface SmartRules {
  folders: string[]
  feeds: string[]
  kinds: ItemKind[]
  statuses: PlayStatus[]
  savedOnly: boolean
  downloadedOnly: boolean
  withinDays: number | null
  minMinutes: number | null
  maxMinutes: number | null
}

export interface Playlist {
  id: string
  name: string
  icon: string
  smart: boolean
  rules: SmartRules
  itemIds: string[]
  sort: 'newest' | 'oldest' | 'shortest' | 'longest'
  createdAt: number
}

/** An item with the state the rules look at. */
export interface PItem {
  id: string
  feed_url: string
  title: string
  link: string | null
  content_html: string
  enclosure_url: string | null
  duration: number | null
  sort_time: number
  word_count: number
  full_word_count: number | null
  full_text_by_default: number
  folder: string
  feed_title: string
  read: number
  saved: number
  pos_t: number | null
  pos_d: number | null
  stub: number
}

const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
const numOrNull = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/** Starter playlists every device makes, with fixed ids, before any edit syncs them. */
const STARTERS: Record<string, Record<string, unknown>> = {
  'starter-morning-drive': { name: 'Morning drive', icon: '🚗', smart: true, rules: { kinds: ['podcast', 'article'], statuses: ['unread', 'inprogress'], withinDays: 2, maxMinutes: 40 } },
  'starter-new-podcast-episodes': { name: 'New podcast episodes', icon: '🎙', smart: true, rules: { kinds: ['podcast'], statuses: ['unread', 'inprogress'], withinDays: 7 } },
  'starter-short-reads': { name: 'Short reads', icon: '📰', smart: true, rules: { kinds: ['article'], statuses: ['unread'], maxMinutes: 8 } },
}

export function playlistFromValue(id: string, v: Record<string, unknown>): Playlist {
  const r = (v.rules ?? {}) as Record<string, unknown>
  const sorts = ['newest', 'oldest', 'shortest', 'longest']
  return {
    id,
    name: typeof v.name === 'string' ? v.name : 'Playlist',
    icon: typeof v.icon === 'string' ? v.icon : '★',
    smart: v.smart === true,
    rules: {
      folders: strings(r.folders),
      feeds: strings(r.feeds),
      kinds: strings(r.kinds).filter((k) => k === 'podcast' || k === 'youtube' || k === 'article') as ItemKind[],
      statuses: strings(r.statuses).filter((k) => k === 'unread' || k === 'inprogress' || k === 'read') as PlayStatus[],
      savedOnly: r.savedOnly === true,
      downloadedOnly: r.downloadedOnly === true,
      withinDays: numOrNull(r.withinDays),
      minMinutes: numOrNull(r.minMinutes),
      maxMinutes: numOrNull(r.maxMinutes),
    },
    itemIds: strings(v.items),
    sort: (sorts.includes(v.sort as string) ? v.sort : 'newest') as Playlist['sort'],
    createdAt: typeof v.createdAt === 'number' ? v.createdAt : 0,
  }
}

export function listPlaylists(db: Db): Playlist[] {
  const rows = db.prepare('SELECT id, value, deleted FROM playlists').all() as { id: string; value: string; deleted: number }[]
  const synced = new Set(rows.map((r) => r.id))
  // Starter playlists nobody has edited or deleted aren't in sync, but every device has them
  const starters = Object.entries(STARTERS)
    .filter(([id]) => !synced.has(id))
    .map(([id, v]) => playlistFromValue(id, v))
  return [...starters, ...rows.filter((r) => !r.deleted).map((r) => playlistFromValue(r.id, JSON.parse(r.value)))].sort((a, b) => a.createdAt - b.createdAt || a.name.localeCompare(b.name))
}

const ITEM_SQL = `
  SELECT i.id, i.feed_url, i.title, i.link, i.content_html, i.enclosure_url, i.duration, i.sort_time, i.word_count, i.full_word_count, i.stub,
         COALESCE(f.full_text_by_default, 0) AS full_text_by_default, COALESCE(f.folder, '') AS folder,
         COALESCE(NULLIF(f.custom_title, ''), f.title, '') AS feed_title,
         COALESCE(s.read, 0) AS read, COALESCE(s.saved, 0) AS saved, s.pos_t, s.pos_d
  FROM items i
  LEFT JOIN feeds f ON f.url = i.feed_url
  LEFT JOIN item_state s ON s.id = i.id
  WHERE COALESCE(s.hidden, 0) = 0`

export const kindOf = (i: PItem): ItemKind => itemKind(i)

export function itemSeconds(i: PItem): number | null {
  const kind = kindOf(i)
  if (kind === 'article') return readAloudSeconds(i.full_text_by_default && i.full_word_count ? i.full_word_count : i.word_count)
  return i.duration || i.pos_d || null
}

export function playStatus(i: PItem): PlayStatus {
  const watched = (i.pos_d ?? 0) > 0 && (i.pos_t ?? 0) >= (i.pos_d ?? 0) - 30
  if ((i.pos_t ?? 0) > 0 && !watched && !i.read) return 'inprogress'
  return i.read ? 'read' : 'unread'
}

export function matchesRules(i: PItem, r: SmartRules, now = Date.now()): boolean {
  if (r.folders.length || r.feeds.length) {
    if (!r.feeds.includes(i.feed_url) && !(i.folder && r.folders.includes(i.folder))) return false
  }
  if (r.kinds.length && !r.kinds.includes(kindOf(i))) return false
  if (r.statuses.length && !r.statuses.includes(playStatus(i))) return false
  if (r.savedOnly && !i.saved) return false
  // Downloads are per device; this Mac has none, so "downloaded only" can't be honoured: ignored
  if (r.withinDays != null && i.sort_time < now - r.withinDays * 86_400_000) return false
  if (r.minMinutes != null || r.maxMinutes != null) {
    const s = itemSeconds(i)
    if (s == null) return false
    if (r.minMinutes != null && s < r.minMinutes * 60) return false
    if (r.maxMinutes != null && s > r.maxMinutes * 60) return false
  }
  return true
}

function sortItems(items: PItem[], sort: Playlist['sort']): PItem[] {
  const out = [...items]
  const len = (i: PItem) => itemSeconds(i) ?? Number.MAX_SAFE_INTEGER
  switch (sort) {
    case 'newest':
      return out.sort((a, b) => b.sort_time - a.sort_time)
    case 'oldest':
      return out.sort((a, b) => a.sort_time - b.sort_time)
    case 'shortest':
      return out.sort((a, b) => len(a) - len(b) || b.sort_time - a.sort_time)
    case 'longest':
      return out.sort((a, b) => (itemSeconds(b) ?? -1) - (itemSeconds(a) ?? -1) || b.sort_time - a.sort_time)
  }
}

export const SMART_LIMIT = 200

export interface PlaylistItems {
  items: PItem[]
  /** Manual playlist entries this Mac has no copy of (older than the feed's window, never saved). */
  missing: string[]
}

export function playlistItems(db: Db, pl: Playlist): PlaylistItems {
  if (!pl.smart) {
    if (!pl.itemIds.length) return { items: [], missing: [] }
    const stmt = db.prepare(`${ITEM_SQL} AND i.id = ?`)
    const items: PItem[] = []
    const missing: string[] = []
    for (const id of pl.itemIds) {
      const row = stmt.get(id) as PItem | undefined
      if (row) items.push(row)
      else missing.push(id)
    }
    return { items, missing }
  }
  // Smart: pre-filter in SQL where it's cheap, then apply the exact rules
  const r = pl.rules
  let sql = ITEM_SQL
  const params: unknown[] = []
  if (r.withinDays != null) {
    sql += ' AND i.sort_time >= ?'
    params.push(Date.now() - r.withinDays * 86_400_000)
  }
  if (r.savedOnly) sql += ' AND s.saved = 1'
  const all = db.prepare(sql).all(...params) as PItem[]
  return { items: sortItems(all.filter((i) => matchesRules(i, r)), pl.sort).slice(0, SMART_LIMIT), missing: [] }
}

export { articleVideoId }
