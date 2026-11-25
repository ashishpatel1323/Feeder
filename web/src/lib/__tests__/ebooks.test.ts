import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { contentHash, headerArg } from '../dropbox'
import { authorString, bookIdOfItem, bookItemId, extOf, isBookName, langString, mergeDropbox, titleFromName } from '../ebooks/library'
import { injectCsp } from '../ebooks/reader'
import type { Book } from '../db'

/** Dropbox's documented algorithm, written separately with Node's crypto. */
function reference(bytes: Uint8Array): string {
  const BLOCK = 4 * 1024 * 1024
  const all: Buffer[] = []
  for (let at = 0; at < bytes.length; at += BLOCK) all.push(createHash('sha256').update(bytes.subarray(at, at + BLOCK)).digest())
  return createHash('sha256').update(Buffer.concat(all)).digest('hex')
}

describe('Dropbox helpers', () => {
  it('computes the content hash like Dropbox, across 4 MB blocks', async () => {
    const big = new Uint8Array(9 * 1024 * 1024 + 123)
    for (let i = 0; i < big.length; i++) big[i] = (i * 31) & 255
    expect(await contentHash(new Blob([big]))).toBe(reference(big))
    const small = new TextEncoder().encode('hello')
    expect(await contentHash(new Blob([small]))).toBe(reference(small))
  })

  it('keeps the API argument header ASCII', () => {
    expect(headerArg({ path: '/Ebooks/Café – 日本.epub' })).toBe('{"path":"/Ebooks/Caf\\u00e9 \\u2013 \\u65e5\\u672c.epub"}')
    expect(JSON.parse(headerArg({ path: '/É' }))).toEqual({ path: '/É' })
  })
})

describe('ebook library', () => {
  it('reads names', () => {
    expect(extOf('A.EPUB')).toBe('epub')
    expect(extOf('x.fb2.zip')).toBe('fbz')
    expect(isBookName('notes.pdf')).toBe(true)
    expect(isBookName('notes.txt')).toBe(false)
    expect(isBookName('book.azw3')).toBe(true)
    expect(titleFromName('The_Time Machine.epub')).toBe('The Time Machine')
    expect(bookIdOfItem(bookItemId('abc'))).toBe('abc')
    expect(bookIdOfItem('https://feed\nguid')).toBeNull()
    expect(langString({ ja: '草枕', en: 'Kusamakura' })).toBe('Kusamakura')
    expect(authorString([{ name: 'A' }, 'B'])).toBe('A, B')
  })

  const book = (id: string, extra: Partial<Book> = {}): Book => ({
    id,
    title: id,
    author: '',
    ext: 'epub',
    size: 1,
    cover: null,
    dropboxPath: null,
    local: false,
    described: false,
    addedAt: 0,
    lastOpenedAt: 0,
    ...extra,
  })
  const file = (name: string, hash: string) => ({ id: `id:${hash}`, name, path: `/Ebooks/${name}`, size: 10, hash, modified: '2026-11-01T00:00:00Z' })

  it('merges a Dropbox listing into the library', () => {
    const books = [
      book('kept', { dropboxPath: '/Ebooks/kept.epub', local: true, described: true, title: 'Kept' }),
      book('moved', { dropboxPath: '/Ebooks/old.epub' }),
      book('gone-remote', { dropboxPath: '/Ebooks/x.epub' }),
      book('gone-local', { dropboxPath: '/Ebooks/y.epub', local: true }),
      book('local-only', { local: true }),
    ]
    const { put, remove } = mergeDropbox(books, [file('kept.epub', 'kept'), file('New_Book.epub', 'new'), file('new.mobi', 'moved'), file('notes.txt', 'txt'), file('dupe.epub', 'new')])
    const byId = Object.fromEntries(put.map((b) => [b.id, b]))
    expect(byId.new).toMatchObject({ title: 'New Book', local: false, dropboxPath: '/Ebooks/New_Book.epub' })
    expect(byId.moved).toMatchObject({ dropboxPath: '/Ebooks/new.mobi', title: 'new' })
    expect(byId['gone-local']).toMatchObject({ dropboxPath: null, local: true })
    expect(byId.kept).toBeUndefined()
    expect(byId.txt).toBeUndefined()
    expect(remove).toEqual(['gone-remote'])
  })
})

describe('book page safety', () => {
  it('puts a script-blocking policy into every page', () => {
    expect(injectCsp('<html><head><title>x</title></head><body/></html>')).toMatch(/^<html><head><meta http-equiv="Content-Security-Policy" content="script-src 'none'/)
    expect(injectCsp('<html xmlns="x"><body>b</body></html>')).toContain('<html xmlns="x"><head><meta http-equiv="Content-Security-Policy"')
    expect(injectCsp('<p>bare</p>')).toMatch(/^<meta http-equiv/)
  })
})
