import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db, getKv, setKv } from '../db'
import { decryptString, encryptString } from '../crypto'
import { saveHighlight } from '../highlights'
import { applyDropboxLink, DROPBOX_KEY, DROPBOX_TS_KEY, dropboxLink, signOutDropbox } from '../dropbox'
import { bookItemId, savePosition } from '../ebooks/library'
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

beforeEach(async () => {
  store = new MemoryStore()
  await db.delete()
  await db.open()
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/v2/status')) return new Response(JSON.stringify({ store: 'memory' }))
    if (url.startsWith('/api/v2/sync')) return handleSync2(new Request(`http://x${url}`, { method: init?.method, body: init?.body as string }), {}, store)
    // Dropbox token revoke on sign-out
    return new Response('{}')
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ebook sync', () => {
  it('syncs where a book was left, newest wins', async () => {
    await joinSync(CODE, KEY)
    await savePosition('book1', 'epubcfi(/6/4!/4/2/1:0)', 0.123456)
    expect((await syncNow())?.sent).toBe(1)
    const other = await otherDevice()
    expect((await other.all()).find((p) => p.k === 'bookpos')).toMatchObject({ key: 'book1', v: { cfi: 'epubcfi(/6/4!/4/2/1:0)', f: 0.1235 } })
    expect((await syncNow())?.sent).toBe(0)

    // Read further on the phone
    await other.push({ k: 'bookpos', key: 'book1', v: { cfi: 'epubcfi(/6/8!/4/2/1:0)', f: 0.5 }, ts: Date.now() + 5000 })
    await syncNow()
    expect(await db.bookPos.get('book1')).toMatchObject({ cfi: 'epubcfi(/6/8!/4/2/1:0)', f: 0.5 })
    expect((await syncNow())?.sent).toBe(0)

    // An older position loses to the one here
    await other.push({ k: 'bookpos', key: 'book1', v: { cfi: 'epubcfi(/6/2!/4)', f: 0.01 }, ts: 10 })
    await syncNow()
    expect((await db.bookPos.get('book1'))?.f).toBe(0.5)
  })

  it('syncs the page number with the position', async () => {
    await joinSync(CODE, KEY)
    await savePosition('book2', 'epubcfi(/6/4!/4)', 0.14, { page: 45, pages: 312 })
    await syncNow()
    const other = await otherDevice()
    expect((await other.all()).find((p) => p.k === 'bookpos' && p.key === 'book2')?.v).toMatchObject({ p: 45, n: 312 })
    await other.push({ k: 'bookpos', key: 'book2', v: { cfi: 'epubcfi(/6/8!/4)', f: 0.5, p: 156, n: 312 }, ts: Date.now() + 5000 })
    await syncNow()
    expect(await db.bookPos.get('book2')).toMatchObject({ p: 156, n: 312 })
    expect((await syncNow())?.sent).toBe(0)
  })

  it('syncs ebook highlights with their CFI', async () => {
    await joinSync(CODE, KEY)
    await saveHighlight({
      id: 'bh1',
      itemId: bookItemId('book1'),
      itemTitle: 'A Book',
      itemLink: null,
      feedTitle: 'An Author',
      imageUrl: null,
      text: 'quoted',
      start: 0,
      prefix: '',
      color: 'green',
      note: '',
      createdAt: 1000,
      cfi: 'epubcfi(/6/4!/4/2,/1:0,/1:6)',
    })
    await syncNow()
    const other = await otherDevice()
    const sent = (await other.all()).find((p) => p.k === 'highlight')!
    expect(sent.v).toMatchObject({ item: 'ebook:\nbook1', cfi: 'epubcfi(/6/4!/4/2,/1:0,/1:6)', color: 'green' })
    expect((await syncNow())?.sent).toBe(0)
    await other.push({ k: 'highlight', key: 'bh2', v: { ...(sent.v as object), text: 'other', cfi: 'epubcfi(/6/6!/4/2,/1:0,/1:5)' }, ts: 2000 })
    await syncNow()
    expect(await db.highlights.get('bh2')).toMatchObject({ itemId: 'ebook:\nbook1', cfi: 'epubcfi(/6/6!/4/2,/1:0,/1:5)' })
    expect((await syncNow())?.sent).toBe(0)
  })

  it('shares the Dropbox sign-in through sync, and signing out', async () => {
    await joinSync(CODE, KEY)
    expect((await syncNow())?.sent).toBe(0)
    const link = { appKey: 'app123', refreshToken: 'refresh-abc', name: 'Ashish', email: 'a@example.com' }
    await setKv(DROPBOX_KEY, { ...link, accessToken: 'at', expiresAt: Date.now() + 1000 })
    await setKv(DROPBOX_TS_KEY, 5000)
    expect((await syncNow())?.sent).toBe(1)
    const other = await otherDevice()
    // Only the link travels, never the short-lived access token
    expect((await other.all()).find((p) => p.k === 'dropbox')).toMatchObject({ key: 'main', v: link, ts: 5000 })
    expect((await syncNow())?.sent).toBe(0)

    // Signed out on another device
    await other.push({ k: 'dropbox', key: 'main', v: { deleted: true }, ts: 9000 })
    await syncNow()
    expect(await dropboxLink()).toBeNull()
    expect((await syncNow())?.sent).toBe(0)

    // Signed in on another device
    await other.push({ k: 'dropbox', key: 'main', v: { ...link, refreshToken: 'refresh-new' }, ts: 12000 })
    await syncNow()
    expect(await dropboxLink()).toMatchObject({ refreshToken: 'refresh-new', appKey: 'app123' })
    expect((await syncNow())?.sent).toBe(0)

    // Signing out here goes out
    await signOutDropbox()
    expect((await syncNow())?.sent).toBe(1)
    expect((await other.all()).find((p) => p.k === 'dropbox')?.v).toEqual({ deleted: true })
  })

  it('a synced link keeps the access token when nothing changed', async () => {
    const link = { appKey: 'k', refreshToken: 'r', name: 'n', email: 'e' }
    await setKv(DROPBOX_KEY, { ...link, accessToken: 'keep', expiresAt: 99 })
    await applyDropboxLink(link)
    expect(await getKv<{ accessToken: string }>(DROPBOX_KEY, { accessToken: '' })).toMatchObject({ accessToken: 'keep' })
  })
})
