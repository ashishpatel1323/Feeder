import 'fake-indexeddb/auto'
import Dexie from 'dexie'
import { describe, expect, it } from 'vitest'

describe('full article by default', () => {
  it('new feeds fetch the full article', async () => {
    const { newFeed } = await import('../db')
    expect(newFeed('https://example.com/rss').fullTextByDefault).toBe(true)
  })

  it('switches existing feeds on once when the database upgrades', async () => {
    // A database as the previous version of the app left it
    const old = new Dexie('feeder-web')
    old.version(1).stores({ feeds: '&url, tag', items: '&id', remoteReadMarks: '&key', remoteFeeds: '&url', kv: '&key' })
    await old.table('feeds').bulkPut([
      { url: 'a', fullTextByDefault: false, whenModified: 5 },
      { url: 'b', fullTextByDefault: true, whenModified: 7 },
    ])
    old.close()

    const before = Date.now()
    const { db } = await import('../db')
    const a = await db.feeds.get('a')
    const b = await db.feeds.get('b')
    expect(a?.fullTextByDefault).toBe(true)
    expect(a?.whenModified).toBeGreaterThanOrEqual(before)
    expect(b?.fullTextByDefault).toBe(true)
    expect(b?.whenModified).toBe(7)
  })
})
