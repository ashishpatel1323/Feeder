import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { db, type Item } from '../db'
import { articleImageUrls, cleanupOffline, formatBytes, preferSavedImages, unreadForOffline } from '../offline'

const item = (id: string, over: Partial<Item> = {}): Item => ({
  id,
  feedUrl: 'f',
  guid: id,
  title: id,
  link: `https://example.com/${id}`,
  contentHtml: '',
  snippet: '',
  imageUrl: null,
  author: null,
  sortTime: 0,
  firstSeen: 0,
  wordCount: 0,
  read: 0,
  readTime: null,
  readSynced: 1,
  saved: 0,
  ...over,
})

beforeEach(async () => {
  await db.delete()
  await db.open()
})

describe('offline reading', () => {
  it('collects the thumbnail and inline images, resolved and de-duplicated', () => {
    const urls = articleImageUrls({
      imageUrl: 'https://cdn.example.com/lead.jpg',
      link: 'https://example.com/post/1',
      contentHtml: '<p><img src="/a.png"><img src="https://cdn.example.com/lead.jpg"><img src="data:image/png;base64,xx"></p>',
      fullHtml: '<img src="https://img.example.org/b.webp">',
    })
    expect(urls).toEqual(['https://cdn.example.com/lead.jpg', 'https://img.example.org/b.webp', 'https://example.com/a.png'])
  })

  it('drops srcset and <source> so the saved src is used', () => {
    const out = preferSavedImages('<picture><source srcset="x.avif"><img src="a.jpg" srcset="a-2x.jpg 2x" sizes="100vw"></picture>')
    expect(out).toBe('<picture><img src="a.jpg"></picture>')
  })

  it('picks the newest unread articles up to the limit', async () => {
    await db.items.bulkPut([item('old', { sortTime: 1 }), item('new', { sortTime: 3 }), item('mid', { sortTime: 2 }), item('read', { read: 1, sortTime: 9 })])
    expect((await unreadForOffline(2)).map((i) => i.id)).toEqual(['new', 'mid'])
    expect((await unreadForOffline(0)).length).toBe(3)
  })

  it('forgets offline copies of articles read over a week ago, but keeps starred ones', async () => {
    const now = 100 * 86_400_000
    const old = now - 8 * 86_400_000
    await db.items.bulkPut([
      item('gone', { read: 1, readTime: old, offlineAt: 1, offlineImages: [] }),
      item('starred', { read: 1, readTime: old, saved: 1, offlineAt: 1 }),
      item('recent', { read: 1, readTime: now - 86_400_000, offlineAt: 1 }),
      item('unread', { offlineAt: 1 }),
    ])
    expect(await cleanupOffline(now)).toBe(1)
    expect((await db.items.get('gone'))?.offlineAt).toBeUndefined()
    expect((await db.items.get('starred'))?.offlineAt).toBe(1)
    expect((await db.items.get('recent'))?.offlineAt).toBe(1)
    expect((await db.items.get('unread'))?.offlineAt).toBe(1)
  })

  it('formats sizes', () => {
    expect(formatBytes(2048)).toBe('2 KB')
    expect(formatBytes(5.5 * 1024 * 1024)).toBe('5.5 MB')
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3.0 GB')
  })
})
