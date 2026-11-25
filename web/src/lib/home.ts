import { feedDisplayTitle, sortFolder, sortFolders, type Feed, type Item } from './db'
import { isWatched } from './videopos'
import { youtubeVideoId } from './youtube'

/** What a feed carries, for the Home page's type filter. */
export type FeedKind = 'podcast' | 'youtube' | 'text'
export const FEED_KINDS: FeedKind[] = ['podcast', 'youtube', 'text']
export const KIND_LABELS: Record<FeedKind, string> = { podcast: 'Podcasts', youtube: 'YouTube', text: 'Text' }

/** Per-feed type picked by hand on the feed page, kept in this browser: feed URL → kind. */
export const FEED_KINDS_KEY = 'feedKinds'

export type HomeGroup = 'folders' | 'none' | 'type'
export type HomeSort = 'latest' | 'az' | 'unread' | 'order'
export type HomeSize = 's' | 'm' | 'l'
/** Feeds as artwork tiles (folders as sideways rows) or as a list of rows with their numbers. */
export type HomeLayout = 'tiles' | 'list'

/** Feeds a folder's row shows before its "All N" tile, and its list section before "Show all N". */
export const LANE_MAX = 8
export const LIST_FOLDER_MAX = 4
/** Positions this early do not count as started (same as Android). */
const MIN_PROGRESS = 5

const AUDIO = /\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac)(\?|#|$)/i
const NOT_AUDIO = /\.(jpe?g|png|gif|webp|avif|svg|mp4|m4v|webm|mov|pdf)(\?|#|$)/i

function isYouTubeFeedUrl(url: string): boolean {
  try {
    const host = new URL(url).hostname.replace(/^www\./, '')
    return host === 'youtube.com' || host.endsWith('.youtube.com')
  } catch {
    return false
  }
}

/**
 * Guesses a feed's kind from its address and articles: YouTube channel and playlist feeds,
 * podcasts (articles with audio attachments), and everything else as text.
 */
export function detectKind(feed: Pick<Feed, 'url'>, items: Pick<Item, 'link' | 'enclosureUrl'>[]): FeedKind {
  if (isYouTubeFeedUrl(feed.url)) return 'youtube'
  if (!items.length) return 'text'
  const audio = items.filter((i) => i.enclosureUrl && (AUDIO.test(i.enclosureUrl) || !NOT_AUDIO.test(i.enclosureUrl))).length
  if (audio && audio * 2 >= items.length) return 'podcast'
  const videos = items.filter((i) => youtubeVideoId(i.link)).length
  if (videos * 2 > items.length) return 'youtube'
  return 'text'
}

export interface HomeFeed {
  feed: Feed
  title: string
  kind: FeedKind
  unread: number
  /** Newest article time, 0 when the feed has none. */
  latest: number
  hasError: boolean
  total: number
  saved: number
  /** Episodes and videos started and not finished. */
  inProgress: number
}

export function buildHomeFeeds(
  feeds: Feed[],
  items: Item[],
  overrides: Record<string, FeedKind> = {},
  positions: Map<string, { t: number; d: number }> = new Map(),
): HomeFeed[] {
  const byFeed = new Map<string, Item[]>()
  for (const i of items) {
    const list = byFeed.get(i.feedUrl)
    if (list) list.push(i)
    else byFeed.set(i.feedUrl, [i])
  }
  return feeds.map((feed) => {
    const its = byFeed.get(feed.url) ?? []
    let unread = 0
    let latest = 0
    let saved = 0
    let inProgress = 0
    for (const i of its) {
      if (!i.read) unread++
      if (i.saved) saved++
      if (i.sortTime > latest) latest = i.sortTime
      const p = positions.get(i.id)
      if (p && p.t >= MIN_PROGRESS && !isWatched(p)) inProgress++
    }
    const o = overrides[feed.url]
    return {
      feed,
      title: feedDisplayTitle(feed),
      kind: o && FEED_KINDS.includes(o) ? o : detectKind(feed, its),
      unread,
      latest,
      hasError: !!feed.lastError,
      total: its.length,
      saved,
      inProgress,
    }
  })
}

export interface HomeFilter {
  kind: FeedKind | null
  /** null = any folder, '' = feeds in no folder. */
  folder: string | null
  unreadOnly: boolean
  errorsOnly: boolean
  query: string
}

export const NO_FILTER: HomeFilter = { kind: null, folder: null, unreadOnly: false, errorsOnly: false, query: '' }

/** Applies every filter except the ones named in `skip`, so a chip can count what it would show. */
export function filterHome(list: HomeFeed[], f: HomeFilter, skip: (keyof HomeFilter)[] = []): HomeFeed[] {
  const q = f.query.trim().toLowerCase()
  return list.filter(
    (h) =>
      (skip.includes('kind') || !f.kind || h.kind === f.kind) &&
      (skip.includes('folder') || f.folder === null || h.feed.tag === f.folder) &&
      (skip.includes('unreadOnly') || !f.unreadOnly || h.unread > 0) &&
      (skip.includes('errorsOnly') || !f.errorsOnly || h.hasError) &&
      (skip.includes('query') || !q || h.title.toLowerCase().includes(q) || h.feed.url.toLowerCase().includes(q)),
  )
}

const byTitle = (a: HomeFeed, b: HomeFeed) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' })

export function sortHome(list: HomeFeed[], sort: HomeSort, order: string[] = []): HomeFeed[] {
  switch (sort) {
    case 'az':
      return [...list].sort(byTitle)
    case 'unread':
      return [...list].sort((a, b) => b.unread - a.unread || byTitle(a, b))
    case 'order': {
      const pos = new Map(sortFolder(list.map((h) => h.feed), order).map((f, i) => [f.url, i]))
      return [...list].sort((a, b) => (pos.get(a.feed.url) ?? 0) - (pos.get(b.feed.url) ?? 0))
    }
    default:
      return [...list].sort((a, b) => b.latest - a.latest || byTitle(a, b))
  }
}

export interface HomeFolder {
  name: string
  feeds: HomeFeed[]
  unread: number
  latest: number
}

/** Folders first (each with its feeds sorted), then the feeds that are in no folder. */
export function groupByFolder(list: HomeFeed[], sort: HomeSort, feedOrder: string[] = [], folderOrder: string[] = []): { folders: HomeFolder[]; loose: HomeFeed[] } {
  const map = new Map<string, HomeFeed[]>()
  const loose: HomeFeed[] = []
  for (const h of list) {
    if (!h.feed.tag) loose.push(h)
    else map.set(h.feed.tag, [...(map.get(h.feed.tag) ?? []), h])
  }
  let folders: HomeFolder[] = [...map].map(([name, fs]) => {
    const feeds = sortHome(fs, sort, feedOrder)
    return { name, feeds, unread: fs.reduce((n, h) => n + h.unread, 0), latest: Math.max(0, ...fs.map((h) => h.latest)) }
  })
  const byName = (a: HomeFolder, b: HomeFolder) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  if (sort === 'az') folders.sort(byName)
  else if (sort === 'unread') folders.sort((a, b) => b.unread - a.unread || byName(a, b))
  else if (sort === 'order') {
    const names = sortFolders(folders.map((f) => f.name), folderOrder)
    folders = names.map((n) => folders.find((f) => f.name === n)!)
  } else folders.sort((a, b) => b.latest - a.latest || byName(a, b))
  return { folders, loose: sortHome(loose, sort, feedOrder) }
}

/** Two letters for a feed with no artwork: the first letters of its first two words ("Hacker News" → HN). */
export function initials(title: string): string {
  const words = title
    .replace(/https?:\/\//, '')
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w && !/^(the|a|an|of|on|and|www)$/i.test(w))
  if (!words.length) return (title.trim()[0] ?? '?').toUpperCase()
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

/** Background and text colors for a generated tile; the same title always gets the same pair. */
const TILE_COLORS: [string, string][] = [
  ['#2f6f5e', '#c9f0de'],
  ['#7a3e2b', '#ffd9c7'],
  ['#3b4a8c', '#d6defc'],
  ['#6b3f7a', '#f1d6fb'],
  ['#8a6d1f', '#fff0bf'],
  ['#22606e', '#c6eef6'],
  ['#7d2f4f', '#ffd3e3'],
  ['#455a2a', '#e1f3c4'],
]
export function tileColors(title: string): [string, string] {
  let h = 0
  for (const c of title) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return TILE_COLORS[h % TILE_COLORS.length]
}

/** How long ago, short: "now", "12m", "3h", "2d", "5w", "8mo", "2y". Empty when `ms` is 0. Same as Android. */
export function shortAge(ms: number, now = Date.now()): string {
  if (ms <= 0) return ''
  const min = Math.max(0, Math.floor((now - ms) / 60_000))
  if (min < 1) return 'now'
  if (min < 60) return `${min}m`
  if (min < 60 * 24) return `${Math.floor(min / 60)}h`
  if (min < 60 * 24 * 7) return `${Math.floor(min / (60 * 24))}d`
  if (min < 60 * 24 * 30) return `${Math.floor(min / (60 * 24 * 7))}w`
  if (min < 60 * 24 * 365) return `${Math.floor(min / (60 * 24 * 30))}mo`
  return `${Math.floor(min / (60 * 24 * 365))}y`
}
