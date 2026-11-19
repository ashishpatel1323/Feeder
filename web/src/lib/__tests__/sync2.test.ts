import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db, FEED_ORDER_KEY, getKv, hideItems, itemId, newFeed, setKv, unhideItems, type Item } from '../db'
import { decryptString, encryptString } from '../crypto'
import {
  addDeviceLink,
  deriveKeys,
  getSyncState,
  joinSync,
  knownStateFor,
  moveFromOldChain,
  randomSecretKey,
  randomSyncCode,
  recordId,
  syncNow,
  type Payload,
} from '../sync2'

const CODE = 'ab'.repeat(32)
const KEY = 'AAECAwQFBgcICQoLDA0ODw==:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='

let store: MemoryStore

const item = (feedUrl: string, guid: string, extra: Partial<Item> = {}): Item => ({
  id: itemId(feedUrl, guid),
  feedUrl,
  guid,
  title: guid,
  link: `https://x/${guid}`,
  contentHtml: '',
  snippet: '',
  imageUrl: null,
  author: null,
  sortTime: 1,
  firstSeen: 1,
  wordCount: 0,
  read: 0,
  readTime: null,
  readSynced: 1,
  saved: 0,
  ...extra,
})

/** Calls the server the way the browser does, against an in-memory store. */
async function api(body: unknown) {
  const res = await handleSync2(new Request('http://x/api/v2/sync', { method: 'POST', body: JSON.stringify(body) }), {}, store)
  return { status: res.status, body: await res.json() }
}

/** Another device: writes and reads records directly. */
async function otherDevice() {
  const keys = await deriveKeys(CODE, KEY)
  return {
    keys,
    async push(...items: (Payload & { ts: number })[]) {
      const push: SyncRecord[] = await Promise.all(
        items.map(async (p) => ({
          id: await recordId(keys, p.k, p.key),
          ts: p.ts,
          data: await encryptString(JSON.stringify({ k: p.k, key: p.key, v: p.v, a: p.a }), keys.secret),
        })),
      )
      return api({ account: keys.account, auth: keys.auth, since: 0, push })
    },
    async all(): Promise<(Payload & { ts: number })[]> {
      const r = await api({ account: keys.account, auth: keys.auth, since: 0, push: [] })
      return Promise.all(
        r.body.changes.map(async (c: SyncRecord) => ({ ...JSON.parse(await decryptString(c.data, keys.secret)), ts: c.ts })),
      )
    },
  }
}

beforeEach(async () => {
  store = new MemoryStore()
  await db.delete()
  await db.open()
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/v2/status')) return new Response(JSON.stringify({ store: 'memory' }))
    if (url.startsWith('/api/v2/sync')) {
      return handleSync2(new Request(`http://x${url}`, { method: init?.method, body: init?.body as string }), {}, store)
    }
    return new Response('{"error":"old chain is gone"}', { status: 404 })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('sync server', () => {
  it('creates an account on first use and then checks its secret', async () => {
    const keys = await deriveKeys(CODE, KEY)
    expect((await api({ account: keys.account, auth: keys.auth, since: 0, push: [] })).status).toBe(200)
    expect((await api({ account: keys.account, auth: 'x'.repeat(43), since: 0, push: [] })).status).toBe(403)
  })

  it('keeps the newest version of a record and tells the loser who won', async () => {
    const keys = await deriveKeys(CODE, KEY)
    const a = { account: keys.account, auth: keys.auth }
    const first = await api({ ...a, since: 0, push: [{ id: 'r'.repeat(20), ts: 200, data: 'new' }] })
    expect(first.body.changes).toEqual([{ id: 'r'.repeat(20), ts: 200, data: 'new' }])
    const cursor = first.body.cursor
    const lost = await api({ ...a, since: cursor, push: [{ id: 'r'.repeat(20), ts: 100, data: 'old' }] })
    expect(lost.body.changes).toEqual([{ id: 'r'.repeat(20), ts: 200, data: 'new' }])
    expect(lost.body.cursor).toBe(cursor)
  })

  it('pages through many changes with the cursor', async () => {
    const keys = await deriveKeys(CODE, KEY)
    const a = { account: keys.account, auth: keys.auth }
    const push = Array.from({ length: 30 }, (_, n) => ({ id: `rec${String(n).padStart(20, '0')}`, ts: n, data: 'x' }))
    await api({ ...a, since: 0, push, limit: 1 })
    let since = 0
    let seen = 0
    for (let more = true; more; ) {
      const r = await api({ ...a, since, push: [], limit: 7 })
      seen += r.body.changes.length
      since = r.body.cursor
      more = r.body.more
    }
    expect(seen).toBe(30)
  })

  it('limits how many accounts can exist', async () => {
    for (let n = 0; n < 3; n++) {
      const k = await deriveKeys(randomSyncCode(), randomSecretKey())
      expect((await api({ account: k.account, auth: k.auth, push: [] })).status).toBe(200)
    }
    const k = await deriveKeys(randomSyncCode(), randomSecretKey())
    expect((await api({ account: k.account, auth: k.auth, push: [] })).status).toBe(403)
  })
})

describe('sync client', () => {
  it('derives the same account from the same code and key', async () => {
    const a = await deriveKeys(CODE, KEY)
    const b = await deriveKeys(CODE.toUpperCase(), KEY)
    expect(a.account).toBe(b.account)
    expect(a.account).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(await recordId(a, 'read', 'f\ng')).toBe(await recordId(b, 'read', 'f\ng'))
    expect(addDeviceLink({ syncCode: CODE, secretKey: KEY })).toContain(`sync_code=${CODE}`)
  })

  it('sends feeds, read marks, saved, deletions and order, encrypted', async () => {
    await joinSync(CODE, KEY)
    await db.feeds.put(newFeed('https://a/feed', { title: 'A', tag: 'News' }))
    await db.items.bulkPut([
      item('https://a/feed', '1', { read: 1, readTime: 50, readSynced: 0 }),
      item('https://a/feed', '2', { saved: 1, readSynced: 0 }),
      item('https://a/feed', '3'),
    ])
    await hideItems([itemId('https://a/feed', '3')])
    await setKv(FEED_ORDER_KEY, ['https://a/feed'])
    const r = await syncNow()
    expect(r?.sent).toBe(5)

    const records = await (await otherDevice()).all()
    const byKind = Object.fromEntries(records.map((p) => [`${p.k}:${p.key.split('\n')[1] ?? p.key}`, p]))
    expect(byKind['feed:https://a/feed'].v).toMatchObject({ title: 'A', tag: 'News' })
    expect(byKind['read:1']).toMatchObject({ v: true, ts: 50 })
    expect(byKind['saved:2']).toMatchObject({ v: true, a: { title: '2' } })
    expect(byKind['hidden:3'].v).toBe(true)
    expect(byKind['layout:main'].v).toEqual({ feedOrder: ['https://a/feed'], folderOrder: [], emptyFolders: [] })
    // Nothing raw reaches the server
    expect(JSON.stringify(await store.exchange((await deriveKeys(CODE, KEY)).account, [], 0, 100))).not.toContain('https://a/feed')

    // A second sync has nothing to send
    expect((await syncNow())?.sent).toBe(0)
    expect(await db.items.where('readSynced').equals(0).count()).toBe(0)
  })

  it('applies what other devices changed', async () => {
    await joinSync(CODE, KEY)
    await db.feeds.bulkPut([newFeed('https://a/feed', { whenModified: 10 }), newFeed('https://gone/feed', { whenModified: 10 })])
    await db.items.bulkPut([item('https://a/feed', '1'), item('https://a/feed', '2'), item('https://gone/feed', 'x')])
    await syncNow()

    const other = await otherDevice()
    await other.push(
      { k: 'feed', key: 'https://gone/feed', v: { deleted: true }, ts: 9e12 },
      { k: 'feed', key: 'https://new/feed', v: { title: 'New', tag: 'T' }, ts: 9e12 },
      { k: 'read', key: itemId('https://a/feed', '1'), v: true, ts: 9e12 },
      { k: 'hidden', key: itemId('https://a/feed', '2'), v: true, ts: 9e12 },
      { k: 'read', key: itemId('https://a/feed', 'later'), v: true, ts: 9e12 },
      { k: 'saved', key: itemId('https://new/feed', 's'), v: true, a: { title: 'Kept', link: 'https://x/s', sortTime: 5 }, ts: 9e12 },
      { k: 'layout', key: 'main', v: { feedOrder: ['https://new/feed'], folderOrder: ['T'], emptyFolders: [] }, ts: 9e12 },
      { k: 'readlater', key: 'https://some/link', v: true, ts: 9e12 },
    )
    const r = await syncNow()
    expect(r?.addedFeeds).toEqual(['https://new/feed'])
    expect(await db.feeds.get('https://gone/feed')).toBeUndefined()
    expect(await db.items.where('feedUrl').equals('https://gone/feed').count()).toBe(0)
    expect((await db.feeds.get('https://new/feed'))?.tag).toBe('T')
    expect((await db.items.get(itemId('https://a/feed', '1')))?.read).toBe(1)
    expect(await db.items.get(itemId('https://a/feed', '2'))).toBeUndefined()
    expect(await db.hidden.get(itemId('https://a/feed', '2'))).toBeTruthy()
    expect((await db.items.get(itemId('https://new/feed', 's')))).toMatchObject({ title: 'Kept', saved: 1 })
    expect(await getKv(FEED_ORDER_KEY, [])).toEqual(['https://new/feed'])
    // Marks for articles not fetched yet wait for them
    expect((await knownStateFor([itemId('https://a/feed', 'later')])).get(itemId('https://a/feed', 'later'))?.read).toBe(true)
    // Applying remote changes doesn't echo them back
    expect((await syncNow())?.sent).toBe(0)
  })

  it('newer local changes win over older remote ones', async () => {
    await joinSync(CODE, KEY)
    await db.feeds.put(newFeed('https://a/feed', { customTitle: 'Mine', whenModified: 9e12 }))
    const other = await otherDevice()
    await other.push({ k: 'feed', key: 'https://a/feed', v: { customTitle: 'Old' }, ts: 5 })
    await syncNow()
    expect((await db.feeds.get('https://a/feed'))?.customTitle).toBe('Mine')
    const remote = (await other.all()).find((p) => p.k === 'feed')
    expect(remote?.v).toMatchObject({ customTitle: 'Mine' })
  })

  it('undoing a delete brings the article back everywhere', async () => {
    await joinSync(CODE, KEY)
    const it3 = item('https://a/feed', '3')
    await db.items.put(it3)
    await hideItems([it3.id])
    await syncNow()
    await unhideItems([it3])
    await syncNow()
    const rec = (await (await otherDevice()).all()).find((p) => p.k === 'hidden')
    expect(rec?.v).toBe(false)
    expect(await db.items.get(it3.id)).toBeTruthy()
  })

  it('moves over from the old sync chain with the same code and key', async () => {
    await setKv('sync', { syncCode: CODE, secretKey: KEY, deviceId: '1', latestMessageTimestamp: 0 })
    await db.remoteFeeds.bulkPut([{ url: 'https://kept/feed' }, { url: 'https://removed/feed' }])
    await db.remoteReadMarks.put({ key: itemId('https://kept/feed', 'r') })
    await db.feeds.put(newFeed('https://kept/feed'))
    await db.items.bulkPut([item('https://kept/feed', 'a', { read: 1, readTime: 7 }), item('https://kept/feed', 'b', { saved: 1 })])

    const state = await moveFromOldChain()
    expect(state).toMatchObject({ syncCode: CODE, fromChain: true })
    expect(await getKv('sync', 'x')).toBeNull()
    await syncNow()

    const records = await (await otherDevice()).all()
    const find = (k: string, key: string) => records.find((p) => p.k === k && p.key === key)
    expect(find('feed', 'https://kept/feed')).toBeTruthy()
    expect(find('feed', 'https://removed/feed')?.v).toEqual({ deleted: true })
    expect(find('read', itemId('https://kept/feed', 'a'))?.v).toBe(true)
    expect(find('saved', itemId('https://kept/feed', 'b'))?.v).toBe(true)
    // Unread articles send nothing
    expect(records.filter((p) => p.k === 'read')).toHaveLength(1)
    expect((await getSyncState())?.lastError).toBeNull()
  })
})
