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
