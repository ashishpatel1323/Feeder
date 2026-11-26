import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import { autoStatus, effectiveStatus, parseGoogleBooks, parseItunes, parseOpenLibrary } from '../shelfMarks'
import { db } from '../db'
import { applyRemote } from '../sync2'

describe('Books status (shelfMarks.ts)', () => {
  it('follows progress', () => {
    expect(autoStatus(null, false)).toBe('new')
    expect(autoStatus(null, true)).toBe('current')
    expect(autoStatus(0.4, false)).toBe('current')
    expect(autoStatus(0.985, true)).toBe('done')
    expect(autoStatus(1, true, false)).toBe('current')
  })
  it('keeps a picked status until the book is used again', () => {
    expect(effectiveStatus('current', { s: 'done', ts: 100 }, 90)).toBe('done')
    expect(effectiveStatus('current', { s: 'done', ts: 100 }, 120)).toBe('current')
    expect(effectiveStatus('new', { s: 'bogus', ts: 5 }, 0)).toBe('new')
  })
  it('reads cover search results', () => {
    expect(parseItunes({ results: [{ collectionName: 'Dune', artistName: 'Frank Herbert', artworkUrl100: 'https://is1.mzstatic.com/a/b/100x100bb.jpg' }] })[0]).toEqual({
      url: 'https://is1.mzstatic.com/a/b/600x600bb.jpg',
      thumb: 'https://is1.mzstatic.com/a/b/200x200bb.jpg',
      label: 'Dune · Frank Herbert',
    })
    expect(parseOpenLibrary({ docs: [{ cover_i: 42, title: 'Dune' }, { title: 'none' }] }).map((c) => c.url)).toEqual(['https://covers.openlibrary.org/b/id/42-L.jpg'])
    expect(parseGoogleBooks({ items: [{ volumeInfo: { title: 'Dune', imageLinks: { thumbnail: 'http://books.google.com/x?id=1&zoom=1&edge=curl' } } }] })[0].url).toBe('https://books.google.com/x?id=1&zoom=0')
  })
  it('applies statuses and covers from other devices, newest wins', async () => {
    const result = { applied: 0, addedFeeds: [] as string[] }
    await applyRemote({ k: 'shelfstatus', key: 'ebook:b1', v: { s: 'done' } }, 200, result)
    await applyRemote({ k: 'shelfstatus', key: 'ebook:b1', v: { s: 'new' } }, 100, result)
    expect(await db.shelfStatus.get('ebook:b1')).toMatchObject({ s: 'done', ts: 200 })
    await applyRemote({ k: 'bookcover', key: 'audiobook:a1', v: { url: 'https://example.com/c.jpg' } }, 300, result)
    expect(await db.bookCovers.get('audiobook:a1')).toMatchObject({ url: 'https://example.com/c.jpg', ts: 300 })
  })
})
