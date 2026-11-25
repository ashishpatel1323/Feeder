import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleSync2, MemoryStore, type SyncRecord } from '../../../server/sync2'
import { db } from '../db'
import { decryptString, encryptString } from '../crypto'
import { byDay, clockText, eventFromValue, historyOf, recordHistory, reasonText } from '../history'
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

describe('history', () => {
  it('notes events with the device, skips quick repeats, newest first', async () => {
    await recordHistory('audiobook', 'b1', 'play', { p: 'Ch. 2 · 01:03', s: 'Two', f: 0.1, x: '1:63000' }, 'app', 1000)
    expect(await recordHistory('audiobook', 'b1', 'play', {}, 'app', 2000)).toBeNull()
    await recordHistory('audiobook', 'b1', 'pause', { p: 'Ch. 2 · 05:00' }, 'bluetooth', 300_000)
    await recordHistory('ebook', 'e1', 'open', { p: 'p. 4 of 10' }, undefined, 5000)
    const h = await historyOf('audiobook', 'b1')
    expect(h.map((e) => e.a)).toEqual(['pause', 'play'])
    expect(h[0]).toMatchObject({ r: 'bluetooth', p: 'Ch. 2 · 05:00', synced: 0 })
    expect(typeof h[0].dn).toBe('string')
    expect(reasonText('bluetooth')).toBe('Headphones disconnected')
    expect(clockText(63_000)).toBe('01:03')
    expect(clockText(3_723_000)).toBe('1:02:03')
  })

  it('groups by day', () => {
    const now = new Date(2026, 10, 25, 12).getTime()
    const ev = (at: number) => ({ id: String(at), item: 'x', c: 'zim' as const, i: 'z', a: 'open' as const, at, synced: 1 })
    const g = byDay([ev(now - 1000), ev(now - 86_400_000), ev(now - 3600_000), ev(now - 3 * 86_400_000)], now)
    expect(g.map((d) => d.day.length > 0)).toEqual([true, true, true])
    expect(g[0].day).toBe('Today')
    expect(g[0].events.length).toBe(2)
    expect(g[1].day).toBe('Yesterday')
  })

  it('syncs events both ways, once each, and never overwrites one', async () => {
    await joinSync(CODE, KEY)
    await recordHistory('audiobook', 'b1', 'play', { p: 'Ch. 1 · 00:00' }, 'app', 1000)
    await recordHistory('ebook', 'e1', 'close', { p: '37%', x: 'epubcfi(/6/4)' }, 'background', 2000)
    expect((await syncNow())?.sent).toBe(2)
    expect((await syncNow())?.sent).toBe(0)
    // Not kept in syncKnown
    expect(await db.syncKnown.where('k').startsWith('history\n').count()).toBe(0)
    const other = await otherDevice()
    const sent = (await other.all()).filter((p) => p.k === 'history')
    expect(sent.map((p) => (p.v as { a: string }).a).sort()).toEqual(['close', 'play'])
    expect(sent.find((p) => (p.v as { c: string }).c === 'ebook')?.v).toMatchObject({ c: 'ebook', i: 'e1', a: 'close', r: 'background', at: 2000, p: '37%', x: 'epubcfi(/6/4)' })

    // The phone paused it; a broken event is ignored
    await other.push({ k: 'history', key: 'ph-1', v: { c: 'audiobook', i: 'b1', a: 'pause', r: 'bluetooth', at: 9000, p: 'Ch. 1 · 02:00', dv: 'phone', dn: 'Pixel 8' }, ts: 9000 })
    await other.push({ k: 'history', key: 'ph-2', v: { c: 'nope', i: 'b1', a: 'pause' }, ts: 9001 })
    await syncNow()
    const h = await historyOf('audiobook', 'b1')
    expect(h.map((e) => [e.a, e.dn ?? null])).toEqual([
      ['pause', 'Pixel 8'],
      ['play', h[1].dn],
    ])
    expect(h[0].synced).toBe(1)
    expect((await syncNow())?.sent).toBe(0)
    expect(eventFromValue('x', { c: 'zim', i: 'z', a: 'open' }, 77)?.at).toBe(77)
  })
})
