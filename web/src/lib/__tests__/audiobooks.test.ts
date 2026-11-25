import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { bytesReader, readId3, readMp4, readTags, tidyMarks } from '../audiobooks/tags'
import { booksInListing, comparePaths, mergeBooks } from '../audiobooks/library'
import { bookLengthMs, chapterAt, chaptersOf, clock, fractionAt, lengthText, locate, resumePoint } from '../audiobooks/timeline'
import type { DropboxFile } from '../dropbox'
import type { AudioTrack, Audiobook } from '../db'

const fixture = (name: string) => new Uint8Array(readFileSync(join(__dirname, 'fixtures', name)))

/** Renames every [from] box type in the file to [to], to hide a box from the parser. */
function renameBox(bytes: Uint8Array, from: string, to: string): Uint8Array {
  const out = bytes.slice()
  const a = new TextEncoder().encode(from)
  const b = new TextEncoder().encode(to)
  for (let i = 0; i + 4 <= out.length; i++) {
    if (out[i] === a[0] && out[i + 1] === a[1] && out[i + 2] === a[2] && out[i + 3] === a[3]) out.set(b, i)
  }
  return out
}

const titles = ['Opening', 'Middle part', 'The End']

describe('MP4 audiobook tags', () => {
  it('reads length, tags and Nero chapters, with moov after the audio', async () => {
    const t = await readMp4(bytesReader(fixture('tiny.m4b')))
    expect(t.duration).toBeGreaterThan(5.9)
    expect(t.duration).toBeLessThan(6.2)
    expect(t.title).toBe('Test Book')
    expect(t.artist).toBe('Jane Author')
    expect(t.album).toBe('Test Book Album')
    expect(t.chapters.map((c) => c.title)).toEqual(titles)
    expect(t.chapters.map((c) => c.startMs)).toEqual([0, 2000, 4000])
  })

  it('falls back to the QuickTime chapter track when there is no chpl', async () => {
    const t = await readMp4(bytesReader(renameBox(fixture('tiny.m4b'), 'chpl', 'free')))
    expect(t.chapters.map((c) => c.title)).toEqual(titles)
    expect(t.chapters[1].startMs).toBeGreaterThan(1900)
    expect(t.chapters[1].startMs).toBeLessThan(2100)
  })

  it('picks the reader by extension and gives empty tags for a file it cannot read', async () => {
    expect((await readTags('x.m4b', bytesReader(fixture('tiny.m4b')))).chapters).toHaveLength(3)
    const none = await readTags('x.m4b', bytesReader(new Uint8Array(40)))
    expect(none.chapters).toEqual([])
    expect(none.duration).toBeNull()
    expect((await readTags('x.ogg', bytesReader(fixture('tiny.m4b')))).title).toBeNull()
  })
})

describe('MP3 audiobook tags', () => {
  it('reads ID3 tags and CHAP chapters', async () => {
    const t = await readId3(bytesReader(fixture('tiny.mp3')))
    expect(t.title).toBe('Test Book')
    expect(t.artist).toBe('Jane Author')
    expect(t.chapters.map((c) => c.title)).toEqual(titles)
    expect(t.chapters.map((c) => c.startMs)).toEqual([0, 2000, 4000])
  })
})

describe('tidyMarks', () => {
  it('sorts, drops duplicates and marks past the end, names blanks, starts at zero', () => {
    expect(
      tidyMarks(
        [
          { startMs: 5000, title: '' },
          { startMs: 1000, title: 'A' },
          { startMs: 1200, title: 'dup' },
          { startMs: 99_000, title: 'past the end' },
        ],
        60,
      ),
    ).toEqual([
      { startMs: 0, title: 'A' },
      { startMs: 5000, title: 'Chapter 2' },
    ])
    expect(tidyMarks([{ startMs: 3000, title: 'only' }], null)).toEqual([])
  })
})

describe('audiobook library', () => {
  const file = (path: string, hash = path): DropboxFile => ({ id: path, name: path.split('/').pop()!, path, size: 100, hash, modified: '2026-01-01T00:00:00Z' })

  it('orders files naturally, files before subfolders', () => {
    const names = ['10.mp3', '2.mp3', 'CD 2/1.mp3', '1.mp3', 'CD 1/2.mp3', 'CD 1/1.mp3']
    expect([...names].sort(comparePaths)).toEqual(['1.mp3', '2.mp3', '10.mp3', 'CD 1/1.mp3', 'CD 1/2.mp3', 'CD 2/1.mp3'])
  })

  it('makes one book per top-level file and per top-level folder', async () => {
    const books = await booksInListing([
      file('/Audiobooks/Dune.m4b', 'h-dune'),
      file('/Audiobooks/notes.txt'),
      file('/Audiobooks/The Hobbit/02.mp3', 'h2'),
      file('/Audiobooks/The Hobbit/01.mp3', 'h1'),
      file('/Audiobooks/The Hobbit/cover.jpg'),
      file('/Audiobooks/The Hobbit/Extras/bonus.mp3', 'h3'),
      file('/Audiobooks/Empty/readme.txt'),
    ])
    expect(books).toHaveLength(2)
    const dune = books.find((b) => !b.folder)!
    expect(dune).toMatchObject({ id: 'h-dune', title: 'Dune', dropboxPath: '/Audiobooks/Dune.m4b' })
    const hobbit = books.find((b) => b.folder)!
    expect(hobbit.title).toBe('The Hobbit')
    expect(hobbit.dropboxPath).toBe('/Audiobooks/The Hobbit')
    expect(hobbit.tracks.map((t) => t.hash)).toEqual(['h1', 'h2', 'h3'])
    expect(hobbit.coverPath).toBe('/Audiobooks/The Hobbit/cover.jpg')
    // Renaming the folder keeps the id
    const renamed = await booksInListing([file('/Audiobooks/Hobbit/01.mp3', 'h1'), file('/Audiobooks/Hobbit/02.mp3', 'h2'), file('/Audiobooks/Hobbit/Extras/bonus.mp3', 'h3')])
    expect(renamed[0].id).toBe(hobbit.id)
  })

  it('keeps what was read from files when a book moves, and drops books gone from Dropbox', async () => {
    const [d] = await booksInListing([file('/Audiobooks/A/1.mp3', 'x1')])
    const old: Audiobook = { ...mergeBooks([], [d]).put[0], described: true, title: 'Read title' }
    old.tracks = [{ ...old.tracks[0], duration: 42, scanned: true }]
    const [moved] = await booksInListing([file('/Audiobooks/B/1.mp3', 'x1')])
    const { put, remove } = mergeBooks([old, { ...old, id: 'gone' }], [moved])
    expect(remove).toEqual(['gone'])
    expect(put[0]).toMatchObject({ title: 'Read title', dropboxPath: '/Audiobooks/B' })
    expect(put[0].tracks[0]).toMatchObject({ duration: 42, scanned: true, path: '/Audiobooks/B/1.mp3' })
  })
})

describe('audiobook timeline', () => {
  const track = (hash: string, duration: number | null, marks: { startMs: number; title: string }[] = []): AudioTrack => ({ path: `/${hash}`, name: `${hash}.mp3`, hash, size: 1, duration, title: null, marks, scanned: true })
  const book = {
    tracks: [
      track('a', 60, [
        { startMs: 0, title: 'One' },
        { startMs: 30_000, title: 'Two' },
      ]),
      track('b', 120),
    ],
  }

  it('lists chapters across files', () => {
    const ch = chaptersOf(book)
    expect(ch.map((c) => [c.title, c.i, c.bookMs, c.lengthMs])).toEqual([
      ['One', 0, 0, 30_000],
      ['Two', 0, 30_000, 30_000],
      ['b', 1, 60_000, 120_000],
    ])
    expect(chapterAt(ch, 0, 45_000)).toBe(1)
    expect(chapterAt(ch, 1, 5_000)).toBe(2)
    expect(bookLengthMs(book)).toBe(180_000)
    expect(fractionAt(book, 1, 30_000)).toBe(0.5)
    expect(locate(book, 90_000)).toEqual({ i: 1, ms: 30_000 })
  })

  it('resumes by content hash when files moved, and starts over when finished', () => {
    expect(resumePoint(book, { i: 0, h: 'b', ms: 10_000, f: 0.4 })).toEqual({ i: 1, ms: 10_000 })
    expect(resumePoint(book, { i: 1, h: 'gone', ms: 10_000, f: 0.4 })).toEqual({ i: 1, ms: 10_000 })
    expect(resumePoint(book, { i: 1, h: 'b', ms: 120_000, f: 1 })).toBeNull()
    expect(resumePoint(book, { i: 0, h: 'a', ms: 59_900, f: 0.3 })).toEqual({ i: 0, ms: 58_000 })
    expect(clock(3_723_000)).toBe('1:02:03')
    expect(lengthText(312 * 60_000)).toBe('5 h 12 min')
  })
})
