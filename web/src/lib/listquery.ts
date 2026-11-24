/**
 * Builds the article list: selection, Show tabs, the Today chip, grouping and sorting.
 * Mirrors the Android fork's archmodel/FeedListQuery.kt (grouping v2).
 */
import { feedDisplayTitle, type Feed, type Item } from './db'
import type { Grouping, ShowFilter, Sorting } from './settings'

// Reading time is words * 60 / 220 seconds: 3 minutes = 660 words, 10 minutes = 2200 words
export const SHORT_READ_MAX_WORDS = 660
export const MEDIUM_READ_MAX_WORDS = 2200

export type Selection = { kind: 'all' } | { kind: 'saved' } | { kind: 'tag'; tag: string } | { kind: 'feed'; url: string }

export interface Group {
  key: string
  label: string
  items: Item[]
  unread: number
}

export const readingMinutes = (words: number) => Math.max(1, Math.round((words * 60) / 220 / 60))

export function wordsOf(item: Item, feed: Feed | undefined): number {
  return feed?.fullTextByDefault && item.fullWordCount ? item.fullWordCount : item.wordCount
}

export function siteOf(item: Item): string {
  const raw = (item.link || item.feedUrl || '').toLowerCase()
  const afterScheme = raw.includes('://') ? raw.slice(raw.indexOf('://') + 3) : raw
  const host = afterScheme.split('/')[0]
  return host.startsWith('www.') ? host.slice(4) : host
}

function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** First day of the week for the browser's locale (0 = Sunday), Monday when unknown. */
function firstDayOfWeek(): number {
  try {
    const loc = new Intl.Locale(navigator.language) as Intl.Locale & {
      getWeekInfo?: () => { firstDay: number }
      weekInfo?: { firstDay: number }
    }
    const firstDay = (loc.getWeekInfo?.() ?? loc.weekInfo)?.firstDay
    // Intl uses 1 = Monday ... 7 = Sunday
    if (firstDay) return firstDay % 7
  } catch {
    // Older browsers: fall through
  }
  return 1
}

/**
 * Mail-style date group of a time: Today, Yesterday, This week, Last week, Earlier this month,
 * Last month, then each month (with the year for earlier years). The key is
 * "yyyy-MM-dd:kind" with the date where the group starts, so keys sort like the dates they
 * hold. Mirrors the Android fork's archmodel/DateBuckets.kt.
 */
export function dateBucketKey(ms: number, now: Date = new Date(), weekStartsOn: number = firstDayOfWeek()): string {
  const day = (y: number, m: number, d: number) => new Date(y, m, d)
  const today = day(now.getFullYear(), now.getMonth(), now.getDate())
  const date = new Date(ms)
  const d = day(date.getFullYear(), date.getMonth(), date.getDate())
  const shift = (base: Date, days: number) => day(base.getFullYear(), base.getMonth(), base.getDate() + days)
  const weekStart = shift(today, -((today.getDay() - weekStartsOn + 7) % 7))
  const fixed: [Date, string][] = [
    [today, 'today'],
    [shift(today, -1), 'yesterday'],
    [weekStart, 'week'],
    [shift(weekStart, -7), 'lastweek'],
    [day(today.getFullYear(), today.getMonth(), 1), 'month'],
    [day(today.getFullYear(), today.getMonth() - 1, 1), 'lastmonth'],
  ]
  // Anything dated in the future counts as today
  if (d >= today) return `${ymd(today)}:today`
  for (const [start, kind] of fixed) {
    if (d >= start) return `${ymd(start)}:${kind}`
  }
  const month = day(d.getFullYear(), d.getMonth(), 1)
  return `${ymd(month)}:${d.getFullYear() === today.getFullYear() ? 'm' : 'y'}`
}

const FIXED_DATE_LABELS: Record<string, string> = {
  week: 'This week',
  lastweek: 'Last week',
  month: 'Earlier this month',
  lastmonth: 'Last month',
}

export function dateLabel(key: string): string {
  const [date, kind] = key.split(':')
  const [y, m, d] = date.split('-').map(Number)
  const start = new Date(y, m - 1, d)
  switch (kind) {
    case 'today':
    case 'yesterday': {
      const day = start.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
      return `${kind === 'today' ? 'Today' : 'Yesterday'} · ${day}`
    }
    case 'm':
      return start.toLocaleDateString(undefined, { month: 'long' })
    case 'y':
      return start.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
    default:
      return FIXED_DATE_LABELS[kind] ?? key
  }
}

export function selectItems(items: Item[], feeds: Map<string, Feed>, selection: Selection): Item[] {
  switch (selection.kind) {
    case 'all':
      return items
    case 'saved':
      return items.filter((i) => i.saved)
    case 'tag':
      return items.filter((i) => (feeds.get(i.feedUrl)?.tag ?? '') === selection.tag)
    case 'feed':
      return items.filter((i) => i.feedUrl === selection.url)
  }
}

export interface ListFilters {
  show: ShowFilter
  today: boolean
}

/** Today chip: only items from today (local time). */
export function applyToday(items: Item[], today: boolean, keepId?: string): Item[] {
  if (!today) return items
  const startOfToday = new Date().setHours(0, 0, 0, 0)
  return items.filter((i) => i.id === keepId || i.sortTime >= startOfToday)
}

/** Show tabs: Unread, All or Saved (read or unread). */
export function applyShow(items: Item[], show: ShowFilter, keepId?: string): Item[] {
  switch (show) {
    case 'all':
      return items
    case 'unread':
      return items.filter((i) => i.id === keepId || !i.read)
    case 'saved':
      return items.filter((i) => i.id === keepId || i.saved)
    case 'downloaded':
      return items.filter((i) => i.id === keepId || isDownloaded(i))
  }
}

export function applyFilters(items: Item[], filters: ListFilters, keepId?: string): Item[] {
  return applyShow(applyToday(items, filters.today, keepId), filters.show, keepId)
}

/** Counts shown on the Unread and Saved tabs, for the items the other filters leave. */
export function showCounts(items: Item[]): { unread: number; saved: number; downloaded: number } {
  let unread = 0
  let saved = 0
  let downloaded = 0
  for (const i of items) {
    if (!i.read) unread++
    if (i.saved) saved++
    if (isDownloaded(i)) downloaded++
  }
  return { unread, saved, downloaded }
}

/** Saved for offline reading by Download for offline, or a video saved with yt-dlp. */
export const isDownloaded = (i: Pick<Item, 'offlineAt' | 'offlineVideo'>) => !!i.offlineAt || !!i.offlineVideo

function compareWithin(sorting: Sorting, feeds: Map<string, Feed>) {
  const newest = (a: Item, b: Item) => b.sortTime - a.sortTime || (a.id < b.id ? 1 : -1)
  switch (sorting) {
    case 'newest':
      return newest
    case 'oldest':
      return (a: Item, b: Item) => -newest(a, b)
    case 'shortest':
      return (a: Item, b: Item) => wordsOf(a, feeds.get(a.feedUrl)) - wordsOf(b, feeds.get(b.feedUrl)) || newest(a, b)
    case 'longest':
      return (a: Item, b: Item) => wordsOf(b, feeds.get(b.feedUrl)) - wordsOf(a, feeds.get(a.feedUrl)) || newest(a, b)
  }
}

function groupKey(grouping: Grouping, item: Item, feeds: Map<string, Feed>): string {
  const feed = feeds.get(item.feedUrl)
  switch (grouping) {
    case 'none':
      return ''
    case 'date':
      return dateBucketKey(item.sortTime)
    case 'feed':
      return item.feedUrl
    case 'tag':
      return feed?.tag ?? ''
    case 'length': {
      const w = wordsOf(item, feed)
      return w < SHORT_READ_MAX_WORDS ? '0' : w <= MEDIUM_READ_MAX_WORDS ? '1' : '2'
    }
    case 'site':
      return siteOf(item)
  }
}

function groupLabel(grouping: Grouping, key: string, feeds: Map<string, Feed>): string {
  switch (grouping) {
    case 'none':
      return ''
    case 'date':
      return dateLabel(key)
    case 'feed':
      return feedDisplayTitle(feeds.get(key)) || key
    case 'tag':
      return key || 'Untagged'
    case 'length':
      return ['Quick reads (under 3 min)', 'Medium (3 to 10 min)', 'Long reads (over 10 min)'][Number(key)]
    case 'site':
      return key || 'Unknown site'
  }
}

function compareGroups(grouping: Grouping, sorting: Sorting, feeds: Map<string, Feed>) {
  return (a: string, b: string): number => {
    switch (grouping) {
      case 'date':
        return sorting === 'oldest' ? a.localeCompare(b) : b.localeCompare(a)
      case 'feed': {
        const ta = feedDisplayTitle(feeds.get(a)).toLowerCase()
        const tb = feedDisplayTitle(feeds.get(b)).toLowerCase()
        return ta.localeCompare(tb) || a.localeCompare(b)
      }
      case 'tag':
      case 'site':
        // Untagged / unknown last
        if (!a !== !b) return a ? -1 : 1
        return a.toLowerCase().localeCompare(b.toLowerCase())
      default:
        return a.localeCompare(b)
    }
  }
}

export function buildGroups(items: Item[], feeds: Map<string, Feed>, grouping: Grouping, sorting: Sorting): Group[] {
  const sorted = [...items].sort(compareWithin(sorting, feeds))
  const byKey = new Map<string, Item[]>()
  for (const item of sorted) {
    const k = groupKey(grouping, item, feeds)
    const list = byKey.get(k)
    if (list) list.push(item)
    else byKey.set(k, [item])
  }
  return [...byKey.keys()].sort(compareGroups(grouping, sorting, feeds)).map((key) => {
    const list = byKey.get(key)!
    return { key, label: groupLabel(grouping, key, feeds), items: list, unread: list.filter((i) => !i.read).length }
  })
}
