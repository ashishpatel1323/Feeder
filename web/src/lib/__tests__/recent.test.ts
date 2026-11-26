import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db, newFeed, setKv, type Item } from '../db'
import { decryptString, encryptString } from '../crypto'
import { recordHistory } from '../history'
import { saveHighlight, updateHighlight, deleteHighlight } from '../highlights'
import {
  applyRecent,
  backfillRecent,
  clearRecent,
  entryFromValue,
  groupRecent,
  matchesFilter,
  MAX_ENTRIES,
  noteFeedChange,
  noteItemOpened,
  noteRecent,
  progressText,
  recentEntries,
  recentKey,
  recentValue,
  removeRecent,
  titleText,
  trim,
} from '../recent'
import { saveVideoPos } from '../videopos'
import { deriveKeys, joinSync, recordId, syncNow, type Payload } from '../sync2'

const CODE = 'cd'.repeat(32)
const KEY = 'AAECAwQFBgcICQoLDA0ODw==:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8='
let store: MemoryStore

async function api(body: unknown) {
  const res = await handleSync2(new Request('http://x/api/v2/sync', { method: 'POST', body: JSON.stringify(body) }), {}, store)
  return { status: res.status, body: await res.json() }
}

async function otherDevice() {
  const keys = await deriveKeys(CODE, KEY)
  return {
    async push(...items: (Payload & { ts: number })[]) {
      const push: SyncRecord[] = await Promise.all(
        items.map(async (p) => ({ id: await recordId(keys, p.k, p.key), ts: p.ts, data: await encryptString(JSON.stringify({ k: p.k, key: p.key, v: p.v }), keys.secret) })),
      )
      return api({ account: keys.account, auth: keys.auth, since: 0, push })
    },
    async all(): Promise<(Payload & { ts: number })[]> {
      const r = await api({ account: keys.account, auth: keys.auth, since: 0, push: [] })
      return Promise.all(r.body.changes.map(async (c: SyncRecord) => ({ ...JSON.parse(await decryptString(c.data, keys.secret)), ts: c.ts })))
    },
  }
}

const FEED = 'https://pod.example/feed.xml'
const item = (guid: string, extra: Partial<Item> = {}): Item => ({
  id: `${FEED}\n${guid}`,
  feedUrl: FEED,
  guid,
  title: `Episode ${guid}`,
  link: `https://pod.example/${guid}`,
  contentHtml: '',
  snippet: '',
  imageUrl: 'https://pod.example/art.jpg',
  author: null,
  sortTime: 1,
  firstSeen: 1,
  wordCount: 100,
  read: 0,
  readTime: null,
  readSynced: 0,
  saved: 0,
  ...extra,
})

beforeEach(async () => {
  store = new MemoryStore()
  await db.delete()
  await db.open()
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/v2/status')) return new Response(JSON.stringify({ store: 'memory' }))
    if (url.startsWith('/api/v2/sync')) return handleSync2(new Request(`http://x${url}`, { method: init?.method, body: init?.body as string }), {}, store)
    return new Response('{}')
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('History log', () => {
  it('notes articles and episodes, with progress from saved positions', async () => {
    await db.feeds.put(newFeed(FEED, { title: 'The Pod' }))
    await db.items.bulkPut([item('a'), item('p', { enclosureUrl: 'https://pod.example/p.mp3', duration: 1000 })])
    await noteItemOpened(`${FEED}\na`)
    await noteItemOpened(`${FEED}\np`, { playlistId: 'pl1', playlistName: 'Commute' })
    await saveVideoPos(`${FEED}\np`, 400, 1000)
    const all = await recentEntries()
    const ep = all.find((e) => e.t === 'podcast')!
    expect(ep).toMatchObject({ n: 'Episode p', s: 'The Pod', pos: 400, dur: 1000, f: 0.4, pl: 'pl1', pn: 'Commute', synced: 0 })
    expect(progressText(ep)).toBe('6:40 / 16:40')
    const art = all.find((e) => e.t === 'article')!
    expect(progressText(art)).toBe('Read')
    expect(matchesFilter(ep, 'playlist')).toBe(true)
    expect(matchesFilter(art, 'playlist')).toBe(false)
    // Opening it again outside the playlist keeps it a playlist item, and moves it up
    await noteItemOpened(`${FEED}\np`)
    expect((await db.recent.get(recentKey('podcast', `${FEED}\np`)))?.pl).toBe('pl1')
  })

  it('notes highlights, notes and feeds made and deleted, with a snapshot', async () => {
    const h = { id: 'h1', itemId: `${FEED}\na`, itemTitle: 'Big article', itemLink: 'https://x/a', feedTitle: 'Site', imageUrl: null, text: 'A quote', start: 0, prefix: '', color: 'green' as const, note: '', createdAt: 1 }
    await saveHighlight(h)
    await updateHighlight('h1', { note: 'My thought' })
    await deleteHighlight('h1')
    await noteFeedChange('create', newFeed('https://f.example/rss', { title: 'F Blog' }))
    await new Promise((r) => setTimeout(r, 20))
    const all = await recentEntries()
    const titles = all.map(titleText).sort()
    expect(titles).toEqual(['Added note on Big article', 'Deleted highlight in Big article', 'Deleted note on Big article', 'Highlighted in Big article', 'Subscribed to F Blog'])
    expect(all.find((e) => e.t === 'note' && e.a === 'delete')).toMatchObject({ q: 'My thought', c: 'green', x: `${FEED}\na` })
    expect(all.filter((e) => matchesFilter(e, 'marks')).length).toBe(4)
  })

  it('central entries follow the per-book timeline', async () => {
    await db.audiobooks.put({ id: 'ab', title: 'Dune', author: 'Herbert', cover: null } as never)
    await recordHistory('audiobook', 'ab', 'play', { f: 0.25, x: '1:5000' }, 'app', 1000)
    await recordHistory('zim', 'wiki', 'open', { p: 'Paris', x: 'A/Paris' }, undefined, 2000)
    const all = await recentEntries()
    expect(all.find((e) => e.t === 'audiobook')).toMatchObject({ n: 'Dune', s: 'Herbert', f: 0.25, x: '1:5000' })
    expect(all.find((e) => e.t === 'zim')).toMatchObject({ i: 'wiki\nA/Paris', n: 'Paris', x: 'A/Paris' })
  })

  it('round-trips values and rejects broken ones', () => {
    const v = { t: 'youtube', a: 'open', i: 'u\ng', n: 'Video', at: 5, pos: 30, dur: 60, x: 'vid', dn: 'Pixel', dv: 'p1' }
    const e = entryFromValue('youtube:u\ng', v, 9)!
    expect(e).toMatchObject({ ...v, ts: 9, synced: 1 })
    expect(recentValue(e)).toEqual(v)
    expect(entryFromValue('youtube:other', v, 9)).toBeNull()
    expect(entryFromValue('x', { t: 'nope', i: 'x' }, 9)).toBeNull()
    expect(recentKey('feed', 'u', 'delete')).toBe('feed:u:delete')
  })

  it('newest change wins; removals and Clear history sync both ways', async () => {
    const T = Date.now() - 3_600_000
    await joinSync(CODE, KEY)
    await noteRecent({ t: 'ebook', i: 'b1', n: 'Book one', f: 0.5 }, { now: T + 1000 })
    await noteRecent({ t: 'ebook', i: 'b2', n: 'Book two' }, { now: T + 2000 })
    expect((await syncNow())?.sent).toBe(2)
    expect((await syncNow())?.sent).toBe(0)
    expect(await db.syncKnown.where('k').startsWith('recent\n').count()).toBe(0)

    const other = await otherDevice()
    // An older change from the phone loses; a newer one wins
    await other.push({ k: 'recent', key: 'ebook:b1', v: { t: 'ebook', a: 'open', i: 'b1', n: 'Old', at: T + 1 }, ts: T + 500 })
    await other.push({ k: 'recent', key: 'ebook:b2', v: { t: 'ebook', a: 'open', i: 'b2', n: 'Book two', at: T + 3000, f: 0.9, dn: 'Pixel' }, ts: T + 3000 })
    await syncNow()
    expect((await db.recent.get('ebook:b1'))?.n).toBe('Book one')
    expect(await db.recent.get('ebook:b2')).toMatchObject({ f: 0.9, dn: 'Pixel' })

    // Removing one sends a deletion
    await removeRecent('ebook:b1', T + 10_000)
    expect((await recentEntries()).map((e) => e.key)).toEqual(['ebook:b2'])
    await syncNow()
    expect((await other.all()).find((p) => p.key === 'ebook:b1')?.v).toEqual({ deleted: true })

    // Clear history on the phone empties it here, and older entries don't come back
    await other.push({ k: 'recent', key: '*', v: { before: T + 20_000 }, ts: T + 20_000 })
    await syncNow()
    expect(await recentEntries()).toEqual([])
    expect(await applyRecent('ebook:b3', { t: 'ebook', a: 'open', i: 'b3', n: 'x', at: T + 15_000 }, T + 30_000)).toBe(false)
    expect(await applyRecent('ebook:b4', { t: 'ebook', a: 'open', i: 'b4', n: 'y', at: T + 25_000 }, T + 30_000)).toBe(true)

    // Clearing here sends the marker
    await clearRecent(T + 40_000)
    await syncNow()
    expect((await other.all()).find((p) => p.key === '*')?.v).toEqual({ before: T + 40_000 })
  })

  it('keeps at most MAX_ENTRIES and groups by Mail-style dates', async () => {
    const now = Date.now()
    await db.recent.bulkPut(Array.from({ length: MAX_ENTRIES + 5 }, (_, k) => ({ key: `article:${k}`, t: 'article' as const, a: 'open' as const, i: String(k), n: 'x', at: now - k * 1000, ts: 1, synced: 1 })))
    await db.recent.put({ key: 'article:old', t: 'article', a: 'open', i: 'old', n: 'x', at: now - 400 * 86_400_000, ts: 1, synced: 1 })
    await trim(now)
    expect(await db.recent.count()).toBe(MAX_ENTRIES)
    expect(await db.recent.get('article:old')).toBeUndefined()
    const g = groupRecent([{ at: now }, { at: now - 86_400_000 }, { at: now - 1000 }], new Date(now))
    expect(g[0].label.startsWith('Today')).toBe(true)
    expect(g[0].entries.length).toBe(2)
  })

  it('backfills once from positions, never from read articles', async () => {
    await db.feeds.put(newFeed(FEED, { title: 'The Pod' }))
    await db.items.bulkPut([item('a', { read: 1, readTime: Date.now() }), item('p', { enclosureUrl: 'https://pod.example/p.mp3' })])
    await db.videoPos.put({ id: `${FEED}\np`, t: 50, d: 100, ts: Date.now() - 1000, dv: 'ph', dn: 'Pixel 8' })
    await setKv('recentBackfilled', false)
    expect(await backfillRecent()).toBe(1)
    expect(await backfillRecent()).toBe(0)
    const [e] = await recentEntries()
    expect(e).toMatchObject({ t: 'podcast', f: 0.5, dn: 'Pixel 8' })
  })
})
