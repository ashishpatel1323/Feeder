import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db, deleteFeeds, FEED_ORDER_KEY, getKv, hideItems, itemId, newFeed, setKv, unhideItems, updateFeed, type Item } from '../db'
import { decryptString, encryptString } from '../crypto'
import { saveVideoPos } from '../videopos'
import { deviceId, deviceName, otherDeviceText } from '../device'
import { deleteHighlight, saveHighlight, updateHighlight } from '../highlights'
import { deletePlaylist, loadPlaylists, newPlaylist, savePlaylist, seedPlaylists, PLAYLISTS_KEY } from '../playlists'
import {
  addDeviceLink,
  deriveKeys,
  forceSync,
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
    await db.feeds.put(newFeed('https://a/feed', { customTitle: 'Start', whenModified: 10 }))
    await syncNow()
    await updateFeed('https://a/feed', { customTitle: 'Mine' })
    const other = await otherDevice()
    await other.push({ k: 'feed', key: 'https://a/feed', v: { customTitle: 'Old' }, ts: 5 })
    await syncNow()
    expect((await db.feeds.get('https://a/feed'))?.customTitle).toBe('Mine')
    const remote = (await other.all()).find((p) => p.k === 'feed')
    expect(remote?.v).toMatchObject({ customTitle: 'Mine' })
  })

  it('joining with feeds already here keeps the folders other devices synced', async () => {
    const other = await otherDevice()
    await other.push(
      { k: 'feed', key: 'https://a/feed', v: { title: 'A', tag: 'Tech' }, ts: 1000 },
      { k: 'feed', key: 'https://gone/feed', v: { deleted: true }, ts: 1000 },
    )
    // An OPML import or an old copy: stamped now, but in old folders
    await db.feeds.bulkPut([
      newFeed('https://a/feed', { tag: 'Old folder' }),
      newFeed('https://gone/feed', { tag: 'Old folder' }),
      newFeed('https://only-here/feed', { tag: 'Mine' }),
    ])
    await joinSync(CODE, KEY)
    await syncNow()

    expect((await db.feeds.get('https://a/feed'))?.tag).toBe('Tech')
    expect(await db.feeds.get('https://gone/feed')).toBeUndefined()
    const records = await other.all()
    const find = (key: string) => records.find((p) => p.k === 'feed' && p.key === key)?.v
    expect(find('https://a/feed')).toMatchObject({ tag: 'Tech' })
    expect(find('https://gone/feed')).toEqual({ deleted: true })
    // What only this device has still goes out
    expect(find('https://only-here/feed')).toMatchObject({ tag: 'Mine' })
    expect((await syncNow())?.sent).toBe(0)
  })

  it('Force sync sends what is here without undoing changes made elsewhere', async () => {
    await joinSync(CODE, KEY)
    await db.feeds.bulkPut([newFeed('https://a/feed', { tag: 'Tech', whenModified: 10 }), newFeed('https://b/feed', { whenModified: 10 })])
    await db.items.put(item('https://a/feed', 's', { saved: 1, readSynced: 0 }))
    await syncNow()
    await deleteFeeds(['https://b/feed'])
    await syncNow()

    // Elsewhere, later: moved to another folder, subscribed again, unsaved
    const other = await otherDevice()
    const later = Date.now()
    await other.push(
      { k: 'feed', key: 'https://a/feed', v: { tag: 'News' }, ts: later },
      { k: 'feed', key: 'https://b/feed', v: { title: 'B again', tag: '' }, ts: later },
      { k: 'saved', key: itemId('https://a/feed', 's'), v: false, ts: later },
    )
    // This device never got those, and has the item saved still. Its copy of the feed was stamped
    // since without a change (like Android's Full article by default switch did to every feed).
    await db.feeds.update('https://a/feed', { whenModified: later + 5 })
    await new Promise((r) => setTimeout(r, 10))
    await forceSync()

    expect((await db.feeds.get('https://a/feed'))?.tag).toBe('News')
    expect((await db.feeds.get('https://b/feed'))?.title).toBe('B again')
    expect((await db.items.get(itemId('https://a/feed', 's')))?.saved).toBe(0)
    const records = await other.all()
    expect(records.find((p) => p.k === 'feed' && p.key === 'https://a/feed')?.v).toMatchObject({ tag: 'News' })
    expect(records.find((p) => p.k === 'feed' && p.key === 'https://b/feed')?.v).toMatchObject({ title: 'B again' })
  })

  it('Force sync still sends an unsubscribe the server lost', async () => {
    await joinSync(CODE, KEY)
    await db.feeds.put(newFeed('https://b/feed', { whenModified: 10 }))
    await syncNow()
    await deleteFeeds(['https://b/feed'])
    await syncNow()
    store = new MemoryStore() // the server starts over
    const other = await otherDevice()
    await other.push({ k: 'feed', key: 'https://b/feed', v: { title: 'B' }, ts: 10 })
    await forceSync()
    expect(await db.feeds.get('https://b/feed')).toBeUndefined()
    expect((await other.all()).find((p) => p.k === 'feed')?.v).toEqual({ deleted: true })
  })

  it('syncs video positions, newest wins', async () => {
    await joinSync(CODE, KEY)
    const a = itemId('https://yt/feed', 'a')
    const b = itemId('https://yt/feed', 'b')
    await saveVideoPos(a, 252, 859, 1000)
    expect((await syncNow())?.sent).toBe(1)
    const other = await otherDevice()
    expect((await other.all()).find((p) => p.k === 'video')).toMatchObject({ key: a, v: { t: 252, d: 859 }, ts: 1000 })
    expect((await syncNow())?.sent).toBe(0)

    // Newer from elsewhere wins, older loses to what is here, and nothing echoes back
    await saveVideoPos(b, 100, 600, 5000)
    await other.push({ k: 'video', key: a, v: { t: 500, d: 859 }, ts: 2000 }, { k: 'video', key: b, v: { t: 40, d: 600 }, ts: 3000 })
    await syncNow()
    expect(await db.videoPos.get(a)).toEqual({ id: a, t: 500, d: 859, ts: 2000 })
    expect(await db.videoPos.get(b)).toMatchObject({ t: 100, ts: 5000 })
    expect((await other.all()).find((p) => p.k === 'video' && p.key === b)).toMatchObject({ v: { t: 100 }, ts: 5000 })
    expect((await syncNow())?.sent).toBe(0)

    // Watching on here again sends the new position
    await saveVideoPos(a, 520, 859)
    expect((await syncNow())?.sent).toBe(1)
  })

  it('says which device saved a position, and keeps it without echoing', async () => {
    await joinSync(CODE, KEY)
    const a = itemId('https://yt/feed', 'a')
    const b = itemId('https://yt/feed', 'b')
    await saveVideoPos(a, 252, 859, 1000)
    await syncNow()
    const other = await otherDevice()
    expect((await other.all()).find((p) => p.k === 'video')?.v).toEqual({ t: 252, d: 859, dv: deviceId(), dn: deviceName() })

    await other.push({ k: 'video', key: b, v: { t: 40, d: 600, dv: 'phone-1', dn: 'Pixel 8' }, ts: 3000 }, { k: 'bookpos', key: 'book1', v: { cfi: 'epubcfi(/6/4)', f: 0.4, dv: 'phone-1', dn: 'Pixel 8' }, ts: 3000 })
    await syncNow()
    expect(await db.videoPos.get(b)).toEqual({ id: b, t: 40, d: 600, ts: 3000, dv: 'phone-1', dn: 'Pixel 8' })
    expect(await db.bookPos.get('book1')).toMatchObject({ dv: 'phone-1', dn: 'Pixel 8' })
    expect(otherDeviceText(await db.videoPos.get(b), 3000 + 2 * 3_600_000)).toBe('on Pixel 8, 2h ago')
    expect(otherDeviceText(await db.videoPos.get(a))).toBe('')
    expect((await syncNow())?.sent).toBe(0)

    // Playing it here makes it this device's again
    await saveVideoPos(b, 80, 600)
    expect((await db.videoPos.get(b))?.dv).toBe(deviceId())
    expect((await syncNow())?.sent).toBe(1)
  })

  it('syncs highlights: new, recolored, noted, deleted, newest wins', async () => {
    await joinSync(CODE, KEY)
    const article = itemId('https://f/feed', 'g1')
    const h = {
      id: 'hl-1',
      itemId: article,
      itemTitle: 'An article',
      itemLink: 'https://f/a',
      feedTitle: 'F',
      imageUrl: null,
      text: 'a quote',
      start: 10,
      prefix: 'before ',
      color: 'yellow' as const,
      note: '',
      createdAt: 1000,
    }
    await saveHighlight(h)
    // The highlight, and "Highlighted in An article" in History (recent.ts)
    expect((await syncNow())?.sent).toBe(2)
    const other = await otherDevice()
    const sent = (await other.all()).find((p) => p.k === 'highlight')
    expect(sent).toMatchObject({ key: 'hl-1', ts: 1000, v: { item: article, title: 'An article', text: 'a quote', start: 10, prefix: 'before ', color: 'yellow', note: '' } })
    expect((await syncNow())?.sent).toBe(0)

    // Recolored elsewhere, and a new one from the phone for an article this browser never fetched
    const v = sent!.v as Record<string, unknown>
    await other.push(
      { k: 'highlight', key: 'hl-1', v: { ...v, color: 'pink', note: 'nice' }, ts: 5000 },
      { k: 'highlight', key: 'hl-2', v: { ...v, item: 'https://f/feed\ng9', text: 'from the phone' }, ts: 4000 },
    )
    await syncNow()
    expect(await db.highlights.get('hl-1')).toMatchObject({ color: 'pink', note: 'nice', modifiedAt: 5000 })
    expect(await db.highlights.get('hl-2')).toMatchObject({ itemId: 'https://f/feed\ng9', text: 'from the phone', itemTitle: 'An article' })
    expect((await syncNow())?.sent).toBe(0)

    // An older edit loses to the one here
    await other.push({ k: 'highlight', key: 'hl-1', v: { ...v, color: 'blue' }, ts: 4500 })
    await syncNow()
    expect((await db.highlights.get('hl-1'))?.color).toBe('pink')

    // Editing and deleting here go out
    await updateHighlight('hl-1', { note: 'nicer' })
    // Only the highlight: changing a note that came from the phone adds nothing to History here
    expect((await syncNow())?.sent).toBe(1)
    expect((await other.all()).find((p) => p.key === 'hl-1')?.v).toMatchObject({ note: 'nicer', color: 'pink' })
    await deleteHighlight('hl-2')
    // The deletion, and "Deleted highlight" in History
    expect((await syncNow())?.sent).toBe(2)
    expect((await other.all()).find((p) => p.key === 'hl-2')?.v).toEqual({ deleted: true })

    // Deleted elsewhere
    await other.push({ k: 'highlight', key: 'hl-1', v: { deleted: true }, ts: Date.now() + 1000 })
    await syncNow()
    expect(await db.highlights.get('hl-1')).toBeUndefined()
    expect((await syncNow())?.sent).toBe(0)
  })

  it('syncs playlists: new, edited, deleted, and starters only once', async () => {
    await joinSync(CODE, KEY)
    await seedPlaylists()
    const mine = newPlaylist({ name: 'Commute', smart: false, itemIds: [itemId('https://p/feed', 'e1')] })
    await savePlaylist(mine)
    await syncNow()
    const other = await otherDevice()
    const lists = (await other.all()).filter((p) => p.k === 'playlist')
    expect(lists.map((p) => p.key).sort()).toEqual([mine.id, 'starter-morning-drive', 'starter-new-podcast-episodes', 'starter-short-reads'].sort())
    expect(lists.find((p) => p.key === mine.id)?.v).toMatchObject({ name: 'Commute', smart: false, items: ['https://p/feed\ne1'] })
    // Starters nobody edited go out with the oldest time, so an edit anywhere wins over them
    expect(lists.find((p) => p.key === 'starter-short-reads')?.ts).toBe(1)
    expect((await syncNow())?.sent).toBe(0)

    // Edited elsewhere: the edit lands here and is not sent back
    const v = lists.find((p) => p.key === mine.id)!.v as Record<string, unknown>
    await other.push({ k: 'playlist', key: mine.id, v: { ...v, name: 'Commute home', items: ['https://p/feed\ne2', 'https://p/feed\ne1'] }, ts: Date.now() + 1000 })
    await syncNow()
    const here = (await loadPlaylists()).find((p) => p.id === mine.id)!
    expect(here.name).toBe('Commute home')
    expect(here.itemIds).toEqual(['https://p/feed\ne2', 'https://p/feed\ne1'])
    expect((await syncNow())?.sent).toBe(0)

    // A new playlist from elsewhere, then deleting one here
    await other.push({ k: 'playlist', key: 'pl-other', v: { ...v, name: 'From the phone', smart: true, items: [] }, ts: 50 })
    await syncNow()
    expect((await loadPlaylists()).some((p) => p.id === 'pl-other' && p.smart)).toBe(true)
    await deletePlaylist(mine.id)
    expect((await syncNow())?.sent).toBe(1)
    expect((await other.all()).find((p) => p.k === 'playlist' && p.key === mine.id)?.v).toEqual({ deleted: true })
  })

  it('puts back a synced playlist that went missing here instead of deleting it everywhere', async () => {
    await joinSync(CODE, KEY)
    const mine = newPlaylist({ name: 'Road trip', smart: false, itemIds: [itemId('https://p/feed', 'e1')] })
    await savePlaylist(mine)
    await syncNow()
    // Lost here without anyone deleting it (a wiped or unreadable store)
    await setKv(PLAYLISTS_KEY, [])
    await syncNow()
    expect((await loadPlaylists()).map((p) => p.name)).toEqual(['Road trip'])
    expect((await loadPlaylists())[0].itemIds).toEqual(['https://p/feed\ne1'])
    const other = await otherDevice()
    expect((await other.all()).find((p) => p.k === 'playlist' && p.key === mine.id)?.v).toMatchObject({ name: 'Road trip' })

    // Deleting it here still deletes it everywhere, once
    await deletePlaylist(mine.id)
    expect((await syncNow())?.sent).toBe(1)
    expect((await other.all()).find((p) => p.k === 'playlist' && p.key === mine.id)?.v).toEqual({ deleted: true })
    expect((await syncNow())?.sent).toBe(0)
    expect(await loadPlaylists()).toEqual([])
  })

  it('gives starter playlists made before fixed ids their fixed id', async () => {
    await joinSync(CODE, KEY)
    await setKv(PLAYLISTS_KEY, [{ ...newPlaylist({ name: 'Short reads', smart: true }), id: 'pl-old', createdAt: 500 }])
    await syncNow()
    expect((await loadPlaylists()).map((p) => p.id)).toEqual(['starter-short-reads'])
    const sent = (await (await otherDevice()).all()).find((p) => p.k === 'playlist')
    expect(sent).toMatchObject({ key: 'starter-short-reads', ts: 500 })
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
