import { describe, expect, it } from 'vitest'
import { computeFeedStats, formatRate, timeAgo } from '../feedstats'
import type { Item } from '../db'

const now = new Date(2026, 10, 19, 12).getTime()
const DAY = 86_400_000
const it_ = (feedUrl: string, ago: number, extra: Partial<Item> = {}): Item => ({
    id: `${feedUrl}\n${ago}`,
    feedUrl,
    guid: String(ago),
    title: '',
    link: null,
    contentHtml: '',
    snippet: '',
    imageUrl: null,
    author: null,
    sortTime: now - ago,
    firstSeen: now - ago,
    wordCount: 100,
    read: 0,
    readTime: null,
    readSynced: 0,
    saved: 0,
    ...extra,
})

describe('feed stats', () => {
    it('counts recent articles per day, reads and lengths', () => {
        const s = computeFeedStats(
            [it_('a', 1000), it_('a', DAY, { read: 1 }), it_('a', 10 * DAY, { saved: 1, fullWordCount: 500 }), it_('a', 40 * DAY), it_('b', 2 * DAY)],
            now,
        ).get('a')!
        expect(s.stored).toBe(4)
        expect(s.recent).toBe(3)
        expect(s.lastWeek).toBe(2)
        expect(s.recentRead).toBe(1)
        expect(s.unread).toBe(3)
        expect(s.saved).toBe(1)
        expect(s.avgWords).toBe(200)
        expect(s.perDay.reduce((a, b) => a + b, 0)).toBe(3)
        expect(s.perDay[29]).toBe(1)
        // 3 articles over the 10 days they span
    expect(s.rate).toBeCloseTo(0.3)
        expect(s.lastArticle).toBe(now - 1000)
    })

    it('formats rates and times', () => {
        expect(formatRate(31.4)).toBe('31/day')
        expect(formatRate(3.14)).toBe('3.1/day')
        expect(formatRate(0.3)).toBe('2.1/week')
        expect(formatRate(0)).toBe('None in 30 days')
        expect(timeAgo(now - 4 * 60_000, now)).toBe('4 min ago')
        expect(timeAgo(now - 3 * 3_600_000, now)).toBe('3 h ago')
    })
})
