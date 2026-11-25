import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { db } from '../db'
import { formatClock, isWatched, positionLabel, resumeAt, saveVideoPos } from '../videopos'
import { youtubeEmbedUrl } from '../youtube'

beforeEach(async () => {
  await db.delete()
  await db.open()
})

describe('video positions', () => {
  it('resumes a few seconds back, and from the start near the end or the beginning', () => {
    expect(resumeAt(undefined)).toBe(0)
    expect(resumeAt({ t: 252, d: 859 })).toBe(249)
    expect(resumeAt({ t: 3, d: 859 })).toBe(0)
    expect(resumeAt({ t: 840, d: 859 })).toBe(0) // last 30s count as watched
    expect(resumeAt({ t: 120, d: 0 })).toBe(117) // length not known yet
    expect(isWatched({ t: 829, d: 859 })).toBe(true)
    expect(isWatched({ t: 828, d: 859 })).toBe(false)
  })

  it('labels the list position', () => {
    expect(positionLabel({ t: 252, d: 859 })).toBe('4:12 / 14:19')
    expect(positionLabel({ t: 252, d: 0 })).toBe('4:12')
    expect(positionLabel({ t: 859, d: 859 })).toBe('Watched')
    expect(formatClock(3725)).toBe('1:02:05')
  })

  it('saves positions, skipping the first seconds and repeats', async () => {
    await saveVideoPos('f\na', 2, 600, 1000)
    expect(await db.videoPos.get('f\na')).toBeUndefined()
    await saveVideoPos('f\na', 61.4, 600.2, 2000)
    expect(await db.videoPos.get('f\na')).toMatchObject({ id: 'f\na', t: 61, d: 600, ts: 2000 })
    await saveVideoPos('f\na', 61.2, 600, 3000)
    expect((await db.videoPos.get('f\na'))!.ts).toBe(2000)
    // Starting over after watching saves the new, earlier position
    await saveVideoPos('f\na', 2, NaN, 4000)
    expect(await db.videoPos.get('f\na')).toMatchObject({ id: 'f\na', t: 2, d: 600, ts: 4000 })
  })

  it('embeds with the player API on and the start position', () => {
    const u = new URL(youtubeEmbedUrl('dQw4w9WgXcQ', { start: 249.7, origin: 'http://localhost:5173' }))
    expect(u.searchParams.get('enablejsapi')).toBe('1')
    expect(u.searchParams.get('start')).toBe('249')
    expect(u.searchParams.get('origin')).toBe('http://localhost:5173')
    expect(new URL(youtubeEmbedUrl('dQw4w9WgXcQ')).searchParams.has('start')).toBe(false)
  })
})
