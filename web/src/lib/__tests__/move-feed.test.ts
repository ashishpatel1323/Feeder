import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import { db, moveFeedToTag, newFeed } from '../db'

describe('moving a feed to another folder', () => {
  it('changes the tag and bumps whenModified so the move syncs', async () => {
    await db.feeds.put(newFeed('https://a.example/rss', { tag: '2 - Weekly', whenModified: 5 }))
    const before = Date.now()
    expect(await moveFeedToTag('https://a.example/rss', '1 - Daily')).toBe(true)
    const f = await db.feeds.get('https://a.example/rss')
    expect(f?.tag).toBe('1 - Daily')
    expect(f?.whenModified).toBeGreaterThanOrEqual(before)
  })

  it('can take a feed out of every folder', async () => {
    await db.feeds.put(newFeed('https://b.example/rss', { tag: '1 - Daily' }))
    expect(await moveFeedToTag('https://b.example/rss', '')).toBe(true)
    expect((await db.feeds.get('https://b.example/rss'))?.tag).toBe('')
  })

  it('does nothing when dropped on its own folder', async () => {
    await db.feeds.put(newFeed('https://c.example/rss', { tag: '1 - Daily', whenModified: 9 }))
    expect(await moveFeedToTag('https://c.example/rss', '1 - Daily')).toBe(false)
    expect((await db.feeds.get('https://c.example/rss'))?.whenModified).toBe(9)
  })
})
