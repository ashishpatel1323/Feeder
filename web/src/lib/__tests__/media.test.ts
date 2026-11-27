import { describe, expect, it } from 'vitest'
import { asBook, filesUnder, folderView, kindOf, mediaInListing, mergeMedia, playsHere, srtToVtt, uploadPath } from '../media/library'
import type { DropboxFile } from '../dropbox'
import type { MediaFile } from '../db'
import { frameAt, RETRY_MS, SIDECAR_RETRY_MS, shouldTry } from '../media/thumbs'
import { youtubePictureUrl } from '../media/fromDownloads'

const file = (path: string, hash = path, size = 100): DropboxFile => ({ id: `id:${path}`, name: path.split('/').pop()!, path, size, hash, modified: '2026-11-01T10:00:00Z' })

const media = (over: Partial<MediaFile> & { id: string; name: string }): MediaFile => ({
  folder: '',
  dropboxPath: `/Media/${over.name}`,
  size: 100,
  kind: 'video',
  duration: null,
  subs: null,
  local: false,
  addedAt: 0,
  lastOpenedAt: 0,
  ...over,
})

describe('Player listing', () => {
  it('knows audio from video, and what a browser cannot play', () => {
    expect(kindOf('a.MP4')).toBe('video')
    expect(kindOf('a.mkv')).toBe('video')
    expect(kindOf('song.flac')).toBe('audio')
    expect(kindOf('notes.txt')).toBeNull()
    expect(kindOf('cover.jpg')).toBeNull()
    expect(playsHere('a.mp4')).toBe(true)
    expect(playsHere('a.mkv')).toBe(false)
  })

  it('keeps folders, natural order, subtitles next to videos, and each file once', () => {
    const drafts = mediaInListing([
      file('/Media/Talks/10 Ending.mp4', 'h10'),
      file('/Media/Talks/2 Start.mp4', 'h2'),
      file('/Media/Talks/2 Start.en.srt', 's1'),
      file('/Media/Talks/2 Start.vtt', 's2'),
      file('/Media/Talks/2024/Keynote.webm', 'hk'),
      file('/Media/song.mp3', 'hs'),
      file('/Media/Copy/song.mp3', 'hs'),
      file('/Media/readme.txt', 'ht'),
      file('/Audiobooks/book.m4b', 'hb'),
    ])
    expect(drafts.map((d) => [d.folder, d.name])).toEqual([
      ['', 'song.mp3'],
      ['Talks', '2 Start.mp4'],
      ['Talks', '10 Ending.mp4'],
      ['Talks/2024', 'Keynote.webm'],
    ])
    expect(drafts.find((d) => d.id === 'h2')?.subs).toBe('/Media/Talks/2 Start.vtt')
    expect(drafts.find((d) => d.id === 'h10')?.subs).toBeNull()
    expect(drafts.find((d) => d.id === 'hs')?.kind).toBe('audio')
  })

  it('shows folders with what is in them, and the files of the folder', () => {
    const items = [
      media({ id: '1', name: 'b.mp4', folder: 'Talks' }),
      media({ id: '2', name: 'a.mp4', folder: 'Talks' }),
      media({ id: '3', name: 'k.mp4', folder: 'Talks/2024', size: 50 }),
      media({ id: '4', name: 'top.mp3', folder: '' }),
      media({ id: '5', name: 'x.mp3', folder: 'Music' }),
    ]
    const top = folderView(items, '')
    expect(top.folders.map((f) => [f.name, f.count, f.size])).toEqual([
      ['Music', 1, 100],
      ['Talks', 3, 250],
    ])
    expect(top.files.map((f) => f.id)).toEqual(['4'])
    const talks = folderView(items, 'Talks')
    expect(talks.folders.map((f) => f.path)).toEqual(['Talks/2024'])
    expect(talks.files.map((f) => f.id)).toEqual(['2', '1'])
    expect(filesUnder(items, 'Talks').map((f) => f.id)).toEqual(['2', '1', '3'])
    // "Talk" is not a prefix of "Talks"
    expect(folderView(items, 'Talk').files).toEqual([])
  })

  it('merges a new listing: new files, moves, and files gone from Dropbox', () => {
    const have = [
      media({ id: 'a', name: 'a.mp4', folder: 'Old', dropboxPath: '/Media/Old/a.mp4', duration: 60 }),
      media({ id: 'b', name: 'b.mp4', dropboxPath: '/Media/b.mp4' }),
      media({ id: 'c', name: 'c.mp4', dropboxPath: '/Media/c.mp4', local: true }),
      media({ id: 'd', name: 'd.mp4', dropboxPath: null, local: true }),
    ]
    const { put, remove } = mergeMedia(have, [
      { id: 'a', name: 'a.mp4', folder: 'New', dropboxPath: '/Media/New/a.mp4', size: 100, kind: 'video', subs: null, thumb: null, addedAt: 1 },
      { id: 'e', name: 'e.mp3', folder: '', dropboxPath: '/Media/e.mp3', size: 5, kind: 'audio', subs: null, thumb: null, addedAt: 2 },
    ])
    expect(remove).toEqual(['b'])
    const byId = new Map(put.map((m) => [m.id, m]))
    expect(byId.get('a')).toMatchObject({ folder: 'New', dropboxPath: '/Media/New/a.mp4', duration: 60 })
    expect(byId.get('e')).toMatchObject({ local: false, duration: null })
    expect(byId.get('c')).toMatchObject({ dropboxPath: null, local: true })
    expect(byId.has('d')).toBe(false)
  })

  it('uploads into the same folder under /Media', () => {
    expect(uploadPath({ folder: 'Holiday/Day 1', name: 'clip:1.mp4' })).toBe('/Media/Holiday/Day 1/clip 1.mp4')
    expect(uploadPath({ folder: '', name: 'a.mp3' })).toBe('/Media/a.mp3')
  })

  it('plays a file as a one-file book whose place syncs by its hash', () => {
    const b = asBook(media({ id: 'hash1', name: 'My_Clip.mp4', folder: 'Talks/2024', duration: 90 }))
    expect(b.id).toBe('hash1')
    expect(b.title).toBe('My Clip')
    expect(b.author).toBe('2024')
    expect(b.tracks).toHaveLength(1)
    expect(b.tracks[0]).toMatchObject({ hash: 'hash1', duration: 90, scanned: true })
  })
})

describe('srtToVtt', () => {
  it('turns SubRip cues into WebVTT', () => {
    const vtt = srtToVtt('﻿1\r\n00:00:01,500 --> 00:00:04,000\r\nHello\r\n\r\n2\r\n00:00:05,000 --> 00:00:06,250\r\nWorld 2\r\n')
    expect(vtt).toBe('WEBVTT\n\n00:00:01.500 --> 00:00:04.000\nHello\n\n00:00:05.000 --> 00:00:06.250\nWorld 2\n')
  })
})

describe('Downloads to Player', async () => {
  const { playerPathFor, pickSubtitle, safeName } = await import('../media/fromDownloads')
  it('puts a video in YouTube/<channel> with a safe name', () => {
    expect(playerPathFor('How to: fix a/b?', 'Tech  Channel')).toEqual({ folder: 'YouTube/Tech Channel', name: 'How to fix a b.mp4' })
    expect(playerPathFor('', '')).toEqual({ folder: 'YouTube/Unknown channel', name: 'Video.mp4' })
    expect(safeName('Ends with dots...', 'x')).toBe('Ends with dots')
  })
  it('keeps English subtitles first', () => {
    expect(pickSubtitle(['de', 'en-US', 'en'])).toBe('en')
    expect(pickSubtitle(['de', 'en-GB'])).toBe('en-GB')
    expect(pickSubtitle(['fr'])).toBe('fr')
    expect(pickSubtitle([])).toBeNull()
  })
})

describe('Player row pictures (same cases as the Android app\'s MediaThumbRulesTest)', () => {
  it('takes the frame a tenth of the way in', () => {
    expect(frameAt(null)).toBe(5)
    expect(frameAt(1)).toBe(0.5)
    expect(frameAt(5)).toBe(1)
    expect(frameAt(360)).toBe(36)
    expect(frameAt(3600)).toBe(60)
  })

  it('tries again after a week, once on this device, or when a picture appears in Dropbox', () => {
    const now = 10 * RETRY_MS
    expect(shouldTry(undefined, { local: false }, now)).toBe(true)
    expect(shouldTry({ id: 'a', url: null, at: now - 1000, local: false }, { local: false }, now)).toBe(false)
    expect(shouldTry({ id: 'a', url: null, at: now - 1000, local: false }, { local: true }, now)).toBe(true)
    expect(shouldTry({ id: 'a', url: null, at: now - 1000, local: true }, { local: true }, now)).toBe(false)
    expect(shouldTry({ id: 'a', url: null, at: now - RETRY_MS - 1, local: true }, { local: true }, now)).toBe(true)
    expect(shouldTry({ id: 'a', url: 'data:x', at: 0, local: false }, { local: false }, now)).toBe(false)
    expect(shouldTry({ id: 'a', url: 'data:x', at: now, local: false }, { local: false, thumb: '/Media/a.jpg' }, now)).toBe(true)
    expect(shouldTry({ id: 'a', url: 'data:y', at: now, local: false, from: '/Media/a.jpg' }, { local: false, thumb: '/Media/a.jpg' }, now)).toBe(false)
    // The Dropbox picture failed to load (offline): a frame shows, and the picture is tried again an hour later
    expect(shouldTry({ id: 'a', url: 'data:f', at: now, local: true, from: null, sideFailedAt: now - 1000 }, { local: true, thumb: '/Media/a.jpg' }, now)).toBe(false)
    expect(shouldTry({ id: 'a', url: 'data:f', at: now, local: true, from: null, sideFailedAt: now - SIDECAR_RETRY_MS - 1 }, { local: true, thumb: '/Media/a.jpg' }, now)).toBe(true)
  })

  it('finds a picture next to the file in Dropbox', () => {
    const drafts = mediaInListing([file('/Media/YouTube/Ch/Talk.mp4', 'h1'), file('/Media/YouTube/Ch/talk.JPG', 'p1'), file('/Media/YouTube/Ch/Other.mp4', 'h2'), file('/Media/Elsewhere/Other.png', 'p2')])
    expect(drafts.find((d) => d.id === 'h1')?.thumb).toBe('/Media/YouTube/Ch/talk.JPG')
    expect(drafts.find((d) => d.id === 'h2')?.thumb).toBeNull()
    expect(drafts).toHaveLength(2)
  })

  it('drops the picture when the file leaves Dropbox', () => {
    const { put } = mergeMedia([media({ id: 'a', name: 'a.mp4', thumb: '/Media/a.jpg', local: true })], [])
    expect(put[0].thumb).toBeNull()
  })

  it("keeps a moved YouTube video's own 16:9 thumbnail", () => {
    expect(youtubePictureUrl('dQw4w9WgXcQ')).toBe('https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg')
  })
})
