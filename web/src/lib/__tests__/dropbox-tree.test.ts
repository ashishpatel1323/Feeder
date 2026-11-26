import 'fake-indexeddb/auto'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { db, setKv } from '../db'
import { listTree } from '../dropbox'

const entry = (tag: string, path: string) => ({ '.tag': tag, id: path, name: path.split('/').pop(), path_lower: path.toLowerCase(), path_display: path, size: 1, content_hash: `h${path}`, server_modified: '2026-01-01T00:00:00Z' })
const json = (v: unknown) => new Response(JSON.stringify(v), { headers: { 'Content-Type': 'application/json' } })

beforeEach(async () => {
  await db.delete()
  await db.open()
  await setKv('dropbox', { appKey: 'k', refreshToken: 'r', name: '', email: '', accessToken: 't', expiresAt: Date.now() + 3600e3 })
})
afterEach(() => vi.unstubAllGlobals())

describe('listTree', () => {
  it('lists folder by folder when the recursive listing cannot be fetched', async () => {
    const tree: Record<string, ReturnType<typeof entry>[]> = {
      '/Audiobooks': [entry('file', '/Audiobooks/One.m4b'), entry('folder', '/Audiobooks/Two')],
      '/Audiobooks/Two': [entry('file', '/Audiobooks/Two/1.mp3'), entry('folder', '/Audiobooks/Two/CD 2')],
      '/Audiobooks/Two/CD 2': [entry('file', '/Audiobooks/Two/CD 2/1.mp3')],
    }
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      const arg = JSON.parse(String(init.body))
      if (arg.recursive) throw new TypeError('Failed to fetch')
      return json({ entries: tree[arg.path] ?? [], cursor: 'c', has_more: false })
    })
    const files = await listTree('/Audiobooks')
    expect(files.map((f) => f.path).sort()).toEqual(['/Audiobooks/One.m4b', '/Audiobooks/Two/1.mp3', '/Audiobooks/Two/CD 2/1.mp3'])
  })

  it('says which request failed when Dropbox cannot be reached at all', async () => {
    vi.stubGlobal('fetch', async () => {
      throw new TypeError('Failed to fetch')
    })
    await expect(listTree('/Audiobooks')).rejects.toThrow('Dropbox: could not reach files/list_folder (Failed to fetch)')
  })
})

describe('uploadFile', () => {
  it('uploads a big file in chunks through the files/upload_session endpoints', async () => {
    const { uploadFile } = await import('../dropbox')
    const urls: string[] = []
    vi.stubGlobal('fetch', async (url: string) => {
      urls.push(url)
      if (url.endsWith('/start')) return json({ session_id: 's1' })
      if (url.endsWith('/finish')) return json(entry('file', '/Audiobooks/Big.m4b'))
      return json(null)
    })
    const big = new Blob([new Uint8Array(40 * 1024 * 1024)])
    const f = await uploadFile('/Audiobooks/Big.m4b', big)
    expect(f.path).toBe('/Audiobooks/Big.m4b')
    expect(urls.map((u) => u.replace('https://content.dropboxapi.com/2/', ''))).toEqual([
      'files/upload_session/start',
      'files/upload_session/append_v2',
      'files/upload_session/append_v2',
      'files/upload_session/append_v2',
      'files/upload_session/append_v2',
      'files/upload_session/finish',
    ])
  })

  /** A Dropbox that keeps how many bytes each session has; [fail] can make a request fail. */
  function fakeDropbox(fail: (what: string, offset: number) => 'network' | 'lost' | number | null = () => null) {
    const sessions = new Map<string, number>()
    const sent: number[] = []
    let n = 0
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      const what = url.split('/').pop()!
      const arg = JSON.parse((init.headers as Record<string, string>)['Dropbox-API-Arg'])
      const size = (init.body as Blob).size
      const offset = arg.cursor?.offset ?? 0
      const f = fail(what, offset)
      if (f === 'network') throw new TypeError('Failed to fetch')
      if (typeof f === 'number') return new Response('{"error_summary":"oops"}', { status: f })
      if (what === 'start') {
        sessions.set(`s${++n}`, 0)
        return json({ session_id: `s${n}` })
      }
      const have = sessions.get(arg.cursor.session_id)
      if (have === undefined) return new Response(JSON.stringify({ error_summary: 'lookup_failed/not_found/' }), { status: 409 })
      if (have !== offset) return new Response(JSON.stringify({ error_summary: 'incorrect_offset/', error: { '.tag': 'incorrect_offset', correct_offset: have } }), { status: 409 })
      sent.push(offset)
      sessions.set(arg.cursor.session_id, have + size)
      if (f === 'lost') throw new TypeError('Failed to fetch')
      return what === 'finish' ? json(entry('file', '/Audiobooks/Big.m4b')) : json(null)
    })
    return { sent }
  }

  it('tries a piece again when the network drops, without sending it twice', async () => {
    const { uploadFile, uploadWaits } = await import('../dropbox')
    uploadWaits.ms = [1, 1, 1, 1]
    let drops = 0
    const { sent } = fakeDropbox((what, offset) => {
      if (what === 'append_v2' && offset === 8 * 1024 * 1024 && drops < 2) return ++drops === 1 ? 'network' : 'lost'
      return null
    })
    const f = await uploadFile('/Audiobooks/Big.m4b', new File([new Uint8Array(20 * 1024 * 1024)], 'Big.m4b'))
    expect(f.path).toBe('/Audiobooks/Big.m4b')
    expect(sent).toEqual([0, 8 * 1024 * 1024, 16 * 1024 * 1024])
  })

  it('goes on where it stopped when the upload is tried again later', async () => {
    const { uploadFile, uploadWaits, hasUnfinishedUpload } = await import('../dropbox')
    uploadWaits.ms = [1]
    const MB = 1024 * 1024
    const file = new File([new Uint8Array(30 * MB)], 'Big.m4b', { lastModified: 1 })
    let offline = false
    let wentOffline = false
    const { sent } = fakeDropbox((what, offset) => {
      if (what === 'append_v2' && offset === 16 * MB && !wentOffline) offline = wentOffline = true
      return offline ? 'network' : null
    })
    await expect(uploadFile('/Audiobooks/Big.m4b', file)).rejects.toThrow('could not reach')
    expect(hasUnfinishedUpload('/Audiobooks/Big.m4b', file)).toBe(true)
    offline = false
    sent.length = 0
    const progress: number[] = []
    await uploadFile('/Audiobooks/Big.m4b', file, (d) => progress.push(d))
    expect(sent).toEqual([16 * MB, 24 * MB])
    expect(progress[0]).toBe(16 * MB)
    expect(hasUnfinishedUpload('/Audiobooks/Big.m4b', file)).toBe(false)
  })

  it('gives up at once on errors that do not go away', async () => {
    const { uploadFile, uploadWaits } = await import('../dropbox')
    uploadWaits.ms = [1, 1, 1]
    let tries = 0
    fakeDropbox((what) => (what === 'append_v2' ? (tries++, 409) : null))
    await expect(uploadFile('/Audiobooks/Big.m4b', new File([new Uint8Array(20 * 1024 * 1024)], 'x'))).rejects.toThrow('oops')
    expect(tries).toBe(1)
  })
})
