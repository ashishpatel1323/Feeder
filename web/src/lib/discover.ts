/**
 * Discover: finding new feeds to follow. Every source is free and needs no key:
 * - Text feeds: a bundled list by topic (discover-feeds.json), Feedly's public feed search,
 *   a website's own feed (the Add feed finder) and, last, a Google News search feed.
 * - Podcasts: Apple's iTunes Search API and its Top charts by category.
 * - YouTube: channel search on youtube.com; each channel has an RSS feed.
 * Everything goes through the app's own fetch proxy (server/handler.ts).
 */
import { proxyFetch } from './api'
import curated from './discover-feeds.json'

export type DiscoverKind = 'text' | 'podcast' | 'youtube'

export interface DiscoverResult {
  kind: DiscoverKind
  title: string
  feedUrl: string
  /** Website, Apple Podcasts page or YouTube channel page. */
  site?: string
  image?: string
  description?: string
  /** A short fact line: "9.4K followers", "Lex Fridman", "21.5M subscribers"… */
  meta?: string
}

export interface CuratedTopic {
  name: string
  group: string
  feeds: { t: string; u: string; d?: string }[]
}

export const CURATED_TOPICS: CuratedTopic[] = (curated as { topics: CuratedTopic[] }).topics

export function curatedResults(topic: CuratedTopic): DiscoverResult[] {
  return topic.feeds.map((f) => ({ kind: kindOfUrl(f.u), title: f.t, feedUrl: f.u, description: f.d, meta: siteOf(f.u) }))
}

/** Every bundled feed whose name or description has all the words. */
export function searchCurated(query: string): DiscoverResult[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return []
  const seen = new Set<string>()
  const out: DiscoverResult[] = []
  for (const t of CURATED_TOPICS) {
    for (const f of t.feeds) {
      const hay = `${f.t} ${f.d ?? ''} ${f.u} ${t.name}`.toLowerCase()
      if (seen.has(f.u) || !words.every((w) => hay.includes(w))) continue
      seen.add(f.u)
      out.push({ kind: kindOfUrl(f.u), title: f.t, feedUrl: f.u, description: f.d, meta: `${siteOf(f.u) ?? ''} · ${t.name}` })
    }
  }
  return out
}

export function kindOfUrl(url: string): DiscoverKind {
  return /youtube\.com\/feeds\/videos\.xml/i.test(url) ? 'youtube' : 'text'
}

function siteOf(url: string): string | undefined {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return undefined
  }
}

/** 9483 → "9.5K", 21500000 → "21.5M". */
export function compactNumber(n: number): string {
  if (n >= 1e6) return `${(n / 1e6).toFixed(n >= 1e7 ? 0 : 1).replace(/\.0$/, '')}M`
  if (n >= 1e3) return `${(n / 1e3).toFixed(n >= 1e4 ? 0 : 1).replace(/\.0$/, '')}K`
  return String(n)
}

/** Looks like an address someone pasted (a site, a feed, a channel link) rather than words. */
export function looksLikeUrl(q: string): boolean {
  const s = q.trim()
  return /^https?:\/\//i.test(s) || (/^[\w-]+(\.[\w-]+)+(\/\S*)?$/.test(s) && !/\s/.test(s))
}

// ---------------------------------------------------------------------------------------------
// Small cache: searches are repeated a lot while browsing, and Feedly's endpoint is unofficial

const CACHE_PREFIX = 'feeder.discover.'
const CACHE_MS = 24 * 60 * 60 * 1000
const memo = new Map<string, { at: number; v: unknown }>()

async function cached<T>(key: string, load: () => Promise<T>): Promise<T> {
  const hit = memo.get(key)
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.v as T
  try {
    const raw = localStorage.getItem(CACHE_PREFIX + key)
    if (raw) {
      const s = JSON.parse(raw) as { at: number; v: T }
      if (Date.now() - s.at < CACHE_MS) {
        memo.set(key, s)
        return s.v
      }
    }
  } catch {
    // no storage
  }
  const v = await load()
  // An empty answer may be a hiccup (or an unofficial endpoint changing): ask again next time
  if (Array.isArray(v) && !v.length) return v
  const entry = { at: Date.now(), v }
  memo.set(key, entry)
  try {
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(entry))
  } catch {
    // full or private mode
  }
  return v
}

async function getJson(url: string): Promise<unknown> {
  return JSON.parse((await proxyFetch(url)).text)
}

// ---------------------------------------------------------------------------------------------
// Text feeds: Feedly search

interface FeedlyFeed {
  id?: string
  feedId?: string
  title?: string
  website?: string
  description?: string
  subscribers?: number
  iconUrl?: string
  visualUrl?: string
}

export function parseFeedly(data: unknown): DiscoverResult[] {
  const results = (data as { results?: FeedlyFeed[] })?.results ?? []
  const out: DiscoverResult[] = []
  for (const r of results) {
    const id = r.feedId ?? r.id ?? ''
    if (!id.startsWith('feed/')) continue
    const feedUrl = id.slice(5)
    if (!/^https?:\/\//i.test(feedUrl)) continue
    out.push({
      kind: kindOfUrl(feedUrl),
      title: (r.title ?? '').trim() || siteOf(feedUrl) || feedUrl,
      feedUrl,
      site: r.website,
      image: r.iconUrl || r.visualUrl,
      description: r.description?.trim() || undefined,
      meta: r.subscribers ? `${compactNumber(r.subscribers)} Feedly followers` : undefined,
    })
  }
  return out
}

/**
 * Feedly matches words strictly ("mint india" finds nothing, "livemint" does), so when a search
 * comes back empty it is tried again with the words joined, then with each longer word.
 */
export function feedlyQueries(q: string): string[] {
  const s = q.trim().toLowerCase()
  const words = s.split(/\s+/).filter(Boolean)
  const out = [s]
  if (words.length > 1) {
    out.push(words.join(''))
    for (const w of [...words].sort((a, b) => b.length - a.length)) if (w.length >= 4) out.push(w)
  }
  return [...new Set(out)]
}

export async function searchFeedly(q: string): Promise<DiscoverResult[]> {
  for (const query of feedlyQueries(q)) {
    const found = await cached(`feedly:${query}`, async () => parseFeedly(await getJson(`https://cloud.feedly.com/v3/search/feeds?query=${encodeURIComponent(query)}&count=30&locale=en`)))
    if (found.length) return found
  }
  return []
}

/** A Google News search as a feed, for any topic: the fallback when nothing else fits. */
export function googleNewsResult(q: string, locale = typeof navigator !== 'undefined' ? navigator.language : 'en-US'): DiscoverResult {
  const region = (locale.split('-')[1] ?? 'US').toUpperCase()
  const lang = locale.split('-')[0] || 'en'
  const feedUrl = `https://news.google.com/rss/search?q=${encodeURIComponent(q.trim())}&hl=${lang}-${region}&gl=${region}&ceid=${region}:${lang}`
  return { kind: 'text', title: `Google News: ${q.trim()}`, feedUrl, site: 'news.google.com', description: `The latest news articles about “${q.trim()}”, updated by Google News.`, meta: 'Search feed' }
}

// ---------------------------------------------------------------------------------------------
// Podcasts: Apple

export const PODCAST_GENRES: [number, string][] = [
  [0, 'All'],
  [1489, 'News'],
  [1321, 'Business'],
  [1318, 'Technology'],
  [1533, 'Science'],
  [1324, 'Society & Culture'],
  [1304, 'Education'],
  [1487, 'History'],
  [1303, 'Comedy'],
  [1512, 'Health & Fitness'],
  [1545, 'Sports'],
  [1301, 'Arts'],
  [1488, 'True Crime'],
  [1309, 'TV & Film'],
  [1310, 'Music'],
  [1305, 'Kids & Family'],
  [1314, 'Religion'],
  [1502, 'Leisure'],
  [1483, 'Fiction'],
]

export const PODCAST_COUNTRIES: [string, string][] = [
  ['in', 'India'],
  ['us', 'US'],
  ['gb', 'UK'],
  ['au', 'Australia'],
  ['ca', 'Canada'],
]

const TIME_ZONE_COUNTRY: Record<string, string> = { 'Asia/Kolkata': 'in', 'Asia/Calcutta': 'in', 'Europe/London': 'gb' }

/** The chart country to start with: from the time zone (Macs often say en-US in India), else the browser's region, else the US. */
export function defaultCountry(locale = typeof navigator !== 'undefined' ? navigator.language : 'en-US', timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone): string {
  const byZone = TIME_ZONE_COUNTRY[timeZone ?? '']
  if (byZone) return byZone
  const r = (locale.split('-')[1] ?? '').toLowerCase()
  return PODCAST_COUNTRIES.some(([c]) => c === r) ? r : 'us'
}

interface ItunesPodcast {
  collectionId?: number
  collectionName?: string
  artistName?: string
  feedUrl?: string
  collectionViewUrl?: string
  artworkUrl600?: string
  artworkUrl100?: string
  primaryGenreName?: string
  trackCount?: number
}

export function parseItunes(data: unknown): DiscoverResult[] {
  const results = (data as { results?: ItunesPodcast[] })?.results ?? []
  return results
    .filter((r) => r.feedUrl)
    .map((r) => ({
      kind: 'podcast' as const,
      title: r.collectionName ?? r.feedUrl!,
      feedUrl: r.feedUrl!,
      site: r.collectionViewUrl,
      image: r.artworkUrl600 || r.artworkUrl100,
      meta: [r.artistName, r.primaryGenreName, r.trackCount ? `${r.trackCount} episodes` : ''].filter(Boolean).join(' · '),
    }))
}

export async function searchPodcasts(q: string, country = defaultCountry()): Promise<DiscoverResult[]> {
  const term = q.trim()
  return cached(`itunes:${country}:${term.toLowerCase()}`, async () => parseItunes(await getJson(`https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=${country}&term=${encodeURIComponent(term)}`)))
}

/** The podcast ids of a Top chart (Apple's chart RSS), best first. */
export function parseChartIds(data: unknown): string[] {
  const entries = (data as { feed?: { entry?: unknown } })?.feed?.entry
  const list = Array.isArray(entries) ? entries : entries ? [entries] : []
  return list.map((e) => (e as { id?: { attributes?: Record<string, string> } }).id?.attributes?.['im:id'] ?? '').filter(Boolean)
}

export async function topPodcasts(genre: number, country = defaultCountry()): Promise<DiscoverResult[]> {
  return cached(`chart:${country}:${genre}`, async () => {
    const chart = `https://itunes.apple.com/${country}/rss/toppodcasts/limit=50/${genre ? `genre=${genre}/` : ''}json`
    const ids = parseChartIds(await getJson(chart))
    if (!ids.length) return []
    // The chart has no feed addresses: look them all up at once, then keep the chart's order
    const found = parseItunesWithIds(await getJson(`https://itunes.apple.com/lookup?entity=podcast&country=${country}&id=${ids.join(',')}`))
    return ids.map((id) => found.get(id)).filter((r): r is DiscoverResult => !!r)
  })
}

function parseItunesWithIds(data: unknown): Map<string, DiscoverResult> {
  const results = (data as { results?: ItunesPodcast[] })?.results ?? []
  const m = new Map<string, DiscoverResult>()
  for (const r of results) {
    const [p] = parseItunes({ results: [r] })
    if (p && r.collectionId) m.set(String(r.collectionId), p)
  }
  return m
}

// ---------------------------------------------------------------------------------------------
// YouTube

export const youtubeFeedUrl = (channelId: string) => `https://www.youtube.com/feeds/videos.xml?channel_id=${channelId}`

/** The channel id in a YouTube channel feed address, or null. */
export function youtubeChannelId(feedUrl: string): string | null {
  return feedUrl.match(/[?&]channel_id=(UC[\w-]{22})/)?.[1] ?? null
}

const text = (v: unknown): string => {
  const o = v as { simpleText?: string; runs?: { text?: string }[] } | undefined
  return o?.simpleText ?? o?.runs?.map((r) => r.text ?? '').join('') ?? ''
}

/** Reads the channels out of a youtube.com search page (its ytInitialData). */
export function parseYouTubeChannels(html: string): DiscoverResult[] {
  const start = html.indexOf('ytInitialData')
  if (start < 0) return []
  const open = html.indexOf('{', start)
  const end = html.indexOf(';</script>', open)
  if (open < 0 || end < 0) return []
  let data: unknown
  try {
    data = JSON.parse(html.slice(open, end))
  } catch {
    return []
  }
  const out: DiscoverResult[] = []
  const seen = new Set<string>()
  const walk = (o: unknown, depth: number) => {
    if (!o || typeof o !== 'object' || depth > 40) return
    if (Array.isArray(o)) return o.forEach((x) => walk(x, depth + 1))
    const rec = o as Record<string, unknown>
    const ch = rec.channelRenderer as Record<string, unknown> | undefined
    if (ch && typeof ch.channelId === 'string' && !seen.has(ch.channelId)) {
      seen.add(ch.channelId)
      const thumbs = ((ch.thumbnail as { thumbnails?: { url: string }[] })?.thumbnails ?? []).map((t) => t.url)
      const thumb = thumbs[thumbs.length - 1]
      // YouTube puts the subscriber count in videoCountText and the @handle in subscriberCountText
      const counts = [text(ch.videoCountText), text(ch.subscriberCountText)].filter(Boolean)
      const handle = counts.find((c) => c.startsWith('@'))
      const subs = counts.find((c) => /subscriber/i.test(c))
      out.push({
        kind: 'youtube',
        title: text(ch.title) || ch.channelId,
        feedUrl: youtubeFeedUrl(ch.channelId),
        site: handle ? `https://www.youtube.com/${handle}` : `https://www.youtube.com/channel/${ch.channelId}`,
        image: thumb ? (thumb.startsWith('//') ? `https:${thumb}` : thumb) : undefined,
        description: text(ch.descriptionSnippet) || undefined,
        meta: [handle, subs].filter(Boolean).join(' · ') || undefined,
      })
      return
    }
    for (const v of Object.values(rec)) walk(v, depth + 1)
  }
  walk(data, 0)
  return out
}

export async function searchYouTube(q: string): Promise<DiscoverResult[]> {
  const term = q.trim()
  // sp=EgIQAg%3D%3D is YouTube's own "Type: Channel" search filter
  const page = (extra = '') => proxyFetch(`https://www.youtube.com/results?search_query=${encodeURIComponent(term)}&sp=EgIQAg%253D%253D${extra}`).then((r) => parseYouTubeChannels(r.text))
  return cached(`yt:${term.toLowerCase()}`, async () => {
    const first = await page()
    // YouTube now and then answers in a layout without channel entries: one more try
    return first.length ? first : page('&hl=en')
  })
}

/** Whether a result is a feed that is already followed (YouTube by channel, others by address). */
export function isFollowed(r: DiscoverResult, followed: Set<string>): boolean {
  if (followed.has(r.feedUrl)) return true
  const norm = (u: string) => u.replace(/^https?:\/\/(www\.)?/i, '').replace(/\/$/, '').toLowerCase()
  const want = norm(r.feedUrl)
  const ch = youtubeChannelId(r.feedUrl)
  for (const f of followed) {
    if (norm(f) === want) return true
    if (ch && youtubeChannelId(f) === ch) return true
  }
  return false
}

// --- Display helpers (same as the Android app's DiscoverSources.kt) ---------------------------

/** "India: Business" → "India business", so quick-pick chips read like the other topics. */
export function topicLabel(name: string): string {
  if (!name.startsWith('India: ')) return name
  const rest = name.slice('India: '.length)
  return `India ${rest.charAt(0).toLowerCase()}${rest.slice(1)}`
}

/** Topics offered as one-tap chips next to the topic picker (when the bundled list has them). */
export const QUICK_TOPICS = ['India: Business', 'India: News', 'Tech', 'Programming', 'Science', 'Personal finance', 'Startups']

/**
 * The small square icon for a result row: its own image (podcast art, channel avatar, Feedly icon),
 * else the site's favicon from DuckDuckGo's icon service (no cookies, no tracking).
 */
export function iconUrl(r: DiscoverResult): string | undefined {
  if (r.image) return r.image
  const host = siteOf(r.site && /^https?:/.test(r.site) ? r.site : r.feedUrl)
  if (!host) return undefined
  return `https://icons.duckduckgo.com/ip3/${host.replace(/^(feeds2?|rss)\./, '')}.ico`
}

/** One supporting line for a row: meta and description, either may be missing. */
export function supportingLine(r: DiscoverResult): string {
  return [r.meta, r.description].filter((s) => s && s.trim()).join(' · ')
}
