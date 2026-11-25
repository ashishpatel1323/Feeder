import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db } from '../db'
import { decryptString, encryptString } from '../crypto'
import { saveAudioPos } from '../audiobooks/library'
import { deriveKeys, joinSync, recordId, syncNow, type Payload } from '../sync2'

const CODE = 'ab'.repeat(32)
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
    return new Response('{}')
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('audiobook sync', () => {
  it('syncs where an audiobook was left, with the device, newest wins', async () => {
    await joinSync(CODE, KEY)
    await saveAudioPos('ab1', 2, 'hash2', 61_234.4, 0.123456)
    expect((await syncNow())?.sent).toBe(1)
    const other = await otherDevice()
    const rec = (await other.all()).find((p) => p.k === 'audiopos')
    expect(rec).toMatchObject({ key: 'ab1', v: { i: 2, h: 'hash2', ms: 61_234, f: 0.1235 } })
    expect(typeof (rec?.v as { dv?: string }).dv).toBe('string')
    expect((await syncNow())?.sent).toBe(0)

    // Listened further on the phone
    await other.push({ k: 'audiopos', key: 'ab1', v: { i: 3, h: 'hash3', ms: 5000, f: 0.4, dv: 'phone', dn: 'Pixel' }, ts: Date.now() + 5000 })
    await syncNow()
    expect(await db.audioPos.get('ab1')).toMatchObject({ i: 3, h: 'hash3', ms: 5000, f: 0.4, dn: 'Pixel' })
    expect((await syncNow())?.sent).toBe(0)

    // An older one loses; a broken one is ignored
    await other.push({ k: 'audiopos', key: 'ab1', v: { i: 0, h: 'hash0', ms: 1, f: 0 }, ts: 10 })
    await other.push({ k: 'audiopos', key: 'ab2', v: { i: 'x' }, ts: Date.now() + 9000 })
    await syncNow()
    expect((await db.audioPos.get('ab1'))?.i).toBe(3)
    expect(await db.audioPos.get('ab2')).toBeUndefined()
  })
})
