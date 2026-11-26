/**
 * The YouTube tab (components/YouTubeTab.tsx, YouTubeWatch.tsx): Feeder's own YouTube feeds shown
 * like Flow's Subscriptions, as on Android (ui/compose/youtube). There is no separate subscription
 * list: subscribing adds a feed, unsubscribing deletes it, and groups are the folders those feeds
 * are in. The music flag is the synced Feed.youtubeMusic.
 */
import { apiHeaders } from './api'
import { db, feedDisplayTitle, type Feed, type Item, type VideoPos, type YouTubeMeta } from './db'
import { compactNumber } from './discover'
import { isWatched } from './videopos'
import { youtubeVideoId } from './youtube'
import type { CommentPage, VideoMeta, WatchNext } from '../../server/youtube'

export type { Comment, CommentPage, RelatedVideo, WatchNext } from '../../server/youtube'

/** A YouTube channel or playlist feed (youtube.com/feeds/videos.xml?…). */
export function isYouTubeFeed(url: string): boolean {
  try {
    const u = new URL(url)
    return /(^|\.)youtube\.com$/i.test(u.hostname) && u.pathname.startsWith('/feeds/videos.xml')
  } catch {
    return false
  }
}

export type ChannelSort = 'name' | 'recent' | 'active'

export interface Channel {
  feed: Feed
  url: string
  title: string
  tag: string
  avatar: string | null
  music: boolean
  /** When its first article arrived here, for "Recently added". */
  firstSeen: number
  /** Videos in the last 30 days, for "Most active". */
  recentCount: number
}

export interface VideoRow {
  item: Item
  videoId: string
  channel: string
  channelUrl: string
  avatar: string | null
  /** Seconds, 0 = not known. */
  duration: number
  views: number | null
  isShort: boolean
  isLive: boolean
  /** 0..1 watched, 0 when not started. */
  progress: number
}

export interface TabState {
  channels: Channel[]
  /** Folders that hold YouTube feeds, A to Z. */
  groups: string[]
  /** null = All. */
  group: string | null
  videos: VideoRow[]
  shorts: VideoRow[]
  lastRefreshed: number | null
}

const MAX_SHORTS = 30
const DAY = 86_400_000

const byName = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: 'base' })

export function channelsOf(feeds: Feed[], items: Item[], now = Date.now()): Channel[] {
  const yt = feeds.filter((f) => isYouTubeFeed(f.url))
  const first = new Map<string, number>()
  const recent = new Map<string, number>()
  const since = now - 30 * DAY
  const urls = new Set(yt.map((f) => f.url))
  for (const it of items) {
    if (!urls.has(it.feedUrl)) continue
    const f = first.get(it.feedUrl)
    if (f === undefined || it.firstSeen < f) first.set(it.feedUrl, it.firstSeen)
    if (it.sortTime >= since) recent.set(it.feedUrl, (recent.get(it.feedUrl) ?? 0) + 1)
  }
  return yt.map((f) => ({
    feed: f,
    url: f.url,
    title: feedDisplayTitle(f) || 'Channel',
    tag: f.tag,
    avatar: f.imageUrl,
    music: !!f.youtubeMusic,
    firstSeen: first.get(f.url) ?? f.lastFetched ?? 0,
    recentCount: recent.get(f.url) ?? 0,
  }))
}

export function sortChannels(list: Channel[], sort: ChannelSort): Channel[] {
  const out = [...list]
  if (sort === 'name') out.sort((a, b) => byName(a.title, b.title))
  else if (sort === 'recent') out.sort((a, b) => b.firstSeen - a.firstSeen)
  else out.sort((a, b) => b.recentCount - a.recentCount || byName(a.title, b.title))
  return out
}

/** The manager's list: video or music channels, matching the search, in the chosen order. */
export function channelsShown(channels: Channel[], query: string, music: boolean, sort: ChannelSort): Channel[] {
  const q = query.trim().toLowerCase()
  return sortChannels(
    channels.filter((c) => c.music === music && (!q || c.title.toLowerCase().includes(q))),
    sort,
  )
}

export function buildTab(feeds: Feed[], items: Item[], meta: Map<string, YouTubeMeta>, positions: Map<string, VideoPos>, wantGroup: string | null): TabState {
  const channels = channelsOf(feeds, items)
  const groups = [...new Set(channels.map((c) => c.tag).filter(Boolean))].sort(byName)
  const group = wantGroup && groups.includes(wantGroup) ? wantGroup : null
  const byUrl = new Map(channels.map((c) => [c.url, c]))
  const rows: VideoRow[] = []
  for (const item of items) {
    const ch = byUrl.get(item.feedUrl)
    if (!ch || (group && ch.tag !== group)) continue
    const videoId = youtubeVideoId(item.link)
    if (!videoId) continue
    const m = meta.get(videoId)
    const pos = positions.get(item.id)
    const duration = (m?.duration || 0) || pos?.d || item.duration || 0
    rows.push({
      item,
      videoId,
      channel: ch.title,
      channelUrl: ch.url,
      avatar: ch.avatar,
      duration,
      views: m?.views ?? null,
      isShort: !!m?.isShort || !!item.link?.includes('/shorts/'),
      isLive: !!m?.isLive,
      progress: pos && duration > 0 ? (isWatched(pos) ? 1 : Math.min(1, Math.max(0, pos.t / duration))) : 0,
    })
  }
  rows.sort((a, b) => b.item.sortTime - a.item.sortTime)
  const last = Math.max(0, ...channels.map((c) => c.feed.lastFetched ?? 0))
  return {
    channels,
    groups,
    group,
    videos: rows.filter((r) => !r.isShort),
    shorts: rows.filter((r) => r.isShort).slice(0, MAX_SHORTS),
    lastRefreshed: last || null,
  }
}

/** Search over the subscriptions list: title or channel. */
export function filterRows(rows: VideoRow[], query: string): VideoRow[] {
  const q = query.trim().toLowerCase()
  if (!q) return rows
  return rows.filter((r) => r.item.title.toLowerCase().includes(q) || r.channel.toLowerCase().includes(q))
}

// ---------------------------------------------------------------- words and numbers

/** 213 → "3:33", 6383 → "1:46:23". */
export function durationText(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

export const viewsText = (n: number | null | undefined) => (n == null ? '' : `${compactNumber(n)} view${n === 1 ? '' : 's'}`)

/** "5 minutes ago", "3 days ago", "2 months ago", as YouTube words it. */
export function ageText(t: number, now = Date.now()): string {
  const s = Math.max(0, (now - t) / 1000)
  const unit = (n: number, w: string) => `${n} ${w}${n === 1 ? '' : 's'} ago`
  if (s < 60) return 'just now'
  if (s < 3600) return unit(Math.floor(s / 60), 'minute')
  if (s < 86400) return unit(Math.floor(s / 3600), 'hour')
  if (s < 7 * 86400) return unit(Math.floor(s / 86400), 'day')
  if (s < 30 * 86400) return unit(Math.floor(s / (7 * 86400)), 'week')
  if (s < 365 * 86400) return unit(Math.floor(s / (30 * 86400)), 'month')
  return unit(Math.floor(s / (365 * 86400)), 'year')
}

export const thumbUrl = (id: string, big = false) => `https://i.ytimg.com/vi/${id}/${big ? 'hqdefault' : 'mqdefault'}.jpg`

// ---------------------------------------------------------------- per-browser choices

const PREF_KEY = 'feeder.youtubeTab'

export interface TabPrefs {
  group: string | null
  grid: boolean
  sort: ChannelSort
}

export function loadPrefs(storage: Pick<Storage, 'getItem'> | undefined = globalThis.localStorage): TabPrefs {
  try {
    const v = JSON.parse(storage?.getItem(PREF_KEY) ?? '{}') as Partial<TabPrefs>
    return {
      group: typeof v.group === 'string' ? v.group : null,
      grid: v.grid === true,
      sort: v.sort === 'recent' || v.sort === 'active' ? v.sort : 'name',
    }
  } catch {
    return { group: null, grid: false, sort: 'name' }
  }
}

export function savePrefs(p: TabPrefs, storage: Pick<Storage, 'setItem'> | undefined = globalThis.localStorage) {
  try {
    storage?.setItem(PREF_KEY, JSON.stringify(p))
  } catch {
    /* private mode */
  }
}

// ---------------------------------------------------------------- adding channels

const CHANNEL_ID = /UC[-_a-zA-Z0-9]{22}/
const PLAYLIST_ID = /(?:^|[?&])list=([-_a-zA-Z0-9]+)/

export const channelFeedUrl = (id: string) => `https://www.youtube.com/feeds/videos.xml?channel_id=${id}`

/**
 * What to subscribe to for a channel link, @handle, channel id or playlist link: a feed URL when it
 * can be told from the text alone, else the channel page (addFeed finds the feed it advertises).
 */
export function channelInputToUrl(input: string): string | null {
  const s = input.trim()
  if (!s) return null
  const id = s.match(CHANNEL_ID)
  if (id && !/\/feeds\//.test(s)) return channelFeedUrl(id[0])
  let page: string
  if (s.startsWith('@')) page = `https://www.youtube.com/${s}`
  else if (/^https?:\/\//i.test(s)) page = s
  else if (/youtube\.com|youtu\.be/i.test(s)) page = `https://${s}`
  else if (/^[\w.-]+$/.test(s)) page = `https://www.youtube.com/@${s}`
  else return null
  try {
    const u = new URL(page)
    if (u.pathname.startsWith('/feeds/videos.xml')) return u.href
    const list = u.search.match(PLAYLIST_ID)
    if (list) return `https://www.youtube.com/feeds/videos.xml?playlist_id=${list[1]}`
    return u.href
  } catch {
    return null
  }
}

/**
 * YouTube Takeout's subscriptions.csv: "Channel Id,Channel Url,Channel Title" with a header row.
 * Returns channel id and title.
 */
export function parseTakeoutCsv(text: string): { id: string; title: string | null }[] {
  const seen = new Set<string>()
  const out: { id: string; title: string | null }[] = []
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    const cells = splitCsv(line)
    const id = cells.map((c) => c.trim()).find((c) => /^UC[-_a-zA-Z0-9]{22}$/.test(c))
    if (!id || seen.has(id)) continue
    seen.add(id)
    out.push({ id, title: cells[2]?.trim() || null })
  }
  return out
}

function splitCsv(line: string): string[] {
  const out: string[] = []
  let cur = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '"' && quoted && line[i + 1] === '"') {
      cur += '"'
      i++
    } else if (c === '"') quoted = !quoted
    else if (c === ',' && !quoted) {
      out.push(cur)
      cur = ''
    } else cur += c
  }
  out.push(cur)
  return out
}

// ---------------------------------------------------------------- the backend (server/youtube.ts)

const nextCache = new Map<string, Promise<WatchNext | null>>()

async function getJson<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(path, { headers: apiHeaders() })
    if (!res.ok) return null
    return (await res.json()) as T
  } catch {
    return null
  }
}

/** The watch page's extras (channel, likes, description, comments token, related), or null. */
export function fetchNext(videoId: string): Promise<WatchNext | null> {
  let p = nextCache.get(videoId)
  if (!p) {
    p = getJson<WatchNext>(`/api/youtube/next?v=${encodeURIComponent(videoId)}`)
    // Failures are tried again next time
    void p.then((v) => v || nextCache.delete(videoId))
    nextCache.set(videoId, p)
  }
  return p
}

export const fetchComments = (token: string) => getJson<CommentPage>(`/api/youtube/comments?token=${encodeURIComponent(token)}`)

/** Looked up again after this long when YouTube gave no length (new uploads, premieres). */
const RETRY_EMPTY = DAY
const BATCH = 20
const pending = new Set<string>()
let running = false

/** Rows on screen: looks up their lengths, views and Shorts flags if not known yet. */
export async function wantMeta(ids: string[], known: Map<string, YouTubeMeta>, now = Date.now()) {
  for (const id of ids) {
    const m = known.get(id)
    if (!m || (!m.duration && !m.isLive && now - m.at > RETRY_EMPTY)) pending.add(id)
  }
  if (running) return
  running = true
  try {
    while (pending.size) {
      const batch = [...pending].slice(0, BATCH)
      batch.forEach((id) => pending.delete(id))
      const res = await getJson<{ items: VideoMeta[] }>(`/api/youtube/meta?ids=${batch.join(',')}`)
      // The backend is missing (static hosting) or YouTube refused: stop for now
      if (!res) {
        pending.clear()
        break
      }
      const at = Date.now()
      await db.ytMeta.bulkPut(res.items.map((m) => ({ id: m.videoId, duration: m.duration, views: m.views, isLive: m.isLive, isShort: m.isShort, at })))
    }
  } finally {
    running = false
  }
}
