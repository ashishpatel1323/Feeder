import 'fake-indexeddb/auto'
import { describe, expect, it, vi } from 'vitest'

const fetched: string[] = []
const items = Array.from({ length: 300 }, (_, i) => {
  const date = new Date(Date.UTC(2026, 0, 1) - i * 86_400_000).toUTCString()
  return `<item><guid>ep-${i}</guid><title>Episode ${i}</title><pubDate>${date}</pubDate><description>Notes ${i}</description></item>`
}).join('')
const rss = `<?xml version="1.0"?><rss version="2.0"><channel><title>Big podcast</title><link>https://example.com</link>${items}</channel></rss>`

vi.mock('../api', () => ({
  proxyFetch: async (url: string) => {
    fetched.push(url)
    return { text: rss, finalUrl: url, contentType: 'application/rss+xml' }
  },
}))

describe('adding a big feed', () => {
  it('downloads it once and stores only the newest articles', async () => {
    const { addFeed } = await import('../refresh')
    const { db } = await import('../db')
    const url = await addFeed('https://example.com/podcast.rss')
    expect(fetched).toEqual(['https://example.com/podcast.rss'])
    expect((await db.feeds.get(url))?.title).toBe('Big podcast')
    const stored = await db.items.where('feedUrl').equals(url).toArray()
    expect(stored).toHaveLength(200)
    const titles = new Set(stored.map((i) => i.title))
    expect(titles.has('Episode 0')).toBe(true)
    expect(titles.has('Episode 199')).toBe(true)
    expect(titles.has('Episode 200')).toBe(false)
  })
})

describe('newestArticles', () => {
  const article = (guid: string, published: number | null) => ({
    guid, title: guid, link: null, contentHtml: '', snippet: '', imageUrl: null, author: null, published, tags: [], enclosureUrl: null,
  })

  it('keeps the latest by date for an oldest-first feed, in feed order', async () => {
    const { newestArticles } = await import('../refresh')
    const list = [article('a', 1), article('b', 2), article('c', 3)]
    expect(newestArticles(list, 2).map((a) => a.guid)).toEqual(['b', 'c'])
  })

  it('falls back to feed order when an article has no date', async () => {
    const { newestArticles } = await import('../refresh')
    const list = [article('a', null), article('b', 2), article('c', 3)]
    expect(newestArticles(list, 2).map((a) => a.guid)).toEqual(['a', 'b'])
  })
})
