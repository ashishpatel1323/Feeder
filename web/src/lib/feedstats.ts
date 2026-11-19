import type { Item } from './db'

const DAY = 86_400_000
export const STATS_DAYS = 30

/** Per-feed numbers for the manage page, from the articles stored in this browser. */
export interface FeedStats {
    /** New articles per day for the last STATS_DAYS days, oldest first (by publication time). */
    perDay: number[]
    /** Articles published in the window. */
    recent: number
    /**
   * Articles per day: recent articles divided by the days they span (at most the window), so a
   * feed added today isn't shown as slower than it is.
   */
    rate: number
    /** Articles published in the last 7 days. */
    lastWeek: number
    /** Of the articles in the window, how many you read. */
    recentRead: number
    /** Newest publication time, or null without articles. */
    lastArticle: number | null
    unread: number
    saved: number
    /** Average words per article (full article when loaded). */
    avgWords: number
    stored: number
    /** First time this browser saw an article of this feed. */
    firstSeen: number | null
    /** Articles by weekday, Monday first, over everything stored. */
    byWeekday: number[]
}

const empty = (): FeedStats => ({
    perDay: new Array(STATS_DAYS).fill(0),
    recent: 0,
    rate: 0,
    lastWeek: 0,
    recentRead: 0,
    lastArticle: null,
    unread: 0,
    saved: 0,
    avgWords: 0,
    stored: 0,
    firstSeen: null,
    byWeekday: new Array(7).fill(0),
})

export function computeFeedStats(items: Item[], now = Date.now()): Map<string, FeedStats> {
    const out = new Map<string, FeedStats>()
    const words = new Map<string, number>()
  const oldest = new Map<string, number>()
    // Day buckets end at the end of today (local time)
    const end = new Date(now)
    end.setHours(24, 0, 0, 0)
    const start = end.getTime() - STATS_DAYS * DAY
    for (const i of items) {
        let s = out.get(i.feedUrl)
        if (!s) out.set(i.feedUrl, (s = empty()))
        s.stored++
        if (!i.read) s.unread++
        if (i.saved) s.saved++
        words.set(i.feedUrl, (words.get(i.feedUrl) ?? 0) + (i.fullWordCount || i.wordCount || 0))
        if (s.lastArticle === null || i.sortTime > s.lastArticle) s.lastArticle = i.sortTime
        if (s.firstSeen === null || i.firstSeen < s.firstSeen) s.firstSeen = i.firstSeen
        s.byWeekday[(new Date(i.sortTime).getDay() + 6) % 7]++
        if (i.sortTime >= start && i.sortTime < end.getTime()) {
            s.perDay[Math.floor((i.sortTime - start) / DAY)]++
            s.recent++
      if (i.sortTime < (oldest.get(i.feedUrl) ?? Infinity)) oldest.set(i.feedUrl, i.sortTime)
            if (i.read) s.recentRead++
            if (i.sortTime >= now - 7 * DAY) s.lastWeek++
        }
    }
    for (const [url, s] of out) {
        const span = Math.min(STATS_DAYS, Math.max(1, (now - (oldest.get(url) ?? now)) / DAY))
    s.rate = s.recent / span
        s.avgWords = s.stored ? Math.round((words.get(url) ?? 0) / s.stored) : 0
    }
    return out
}

export const noStats = empty

/** "31/day", "1.2/day" or "2.1/week" for slow feeds. */
export function formatRate(perDay: number): string {
    if (perDay >= 10) return `${Math.round(perDay)}/day`
    if (perDay >= 1) return `${perDay.toFixed(1)}/day`
    const week = perDay * 7
    if (week === 0) return 'None in 30 days'
    return `${week.toFixed(1)}/week`
}

/** "just now", "4 min ago", "3 h ago", "2 d ago", or a date. */
export function timeAgo(t: number | null | undefined, now = Date.now()): string {
    if (!t) return 'never'
    const m = Math.round((now - t) / 60_000)
    if (m < 1) return 'just now'
    if (m < 60) return `${m} min ago`
    const h = Math.round(m / 60)
    if (h < 24) return `${h} h ago`
    const d = Math.round(h / 24)
    if (d < 60) return `${d} d ago`
    return new Date(t).toLocaleDateString([], {
        month: 'short',
        year: 'numeric',
    })
}
