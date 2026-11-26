import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { gunzipSync } from 'node:zlib'
import { join } from 'node:path'
import { parseComments, parseNext, parsePlayer } from '../../../server/youtube'

/** Real answers from youtube.com/youtubei/v1 (saved Nov 2026, video dQw4w9WgXcQ), shared with the Android tests. */
const load = (name: string) => {
  const raw = readFileSync(join(__dirname, 'fixtures', name))
  return JSON.parse((name.endsWith('.gz') ? gunzipSync(raw) : raw).toString('utf8'))
}

describe('InnerTube parsing (server/youtube.ts)', () => {
  it('reads length, views and likes from the player endpoint', () => {
    const meta = parsePlayer('dQw4w9WgXcQ', load('player.json'))!
    expect(meta.duration).toBe(213)
    expect(meta.views!).toBeGreaterThan(1_000_000_000)
    expect(meta.likes!).toBeGreaterThan(1_000_000)
    expect(meta.isLive).toBe(false)
  })

  it('reads channel, likes, comments token and related videos from the next endpoint', () => {
    const next = parseNext(load('next.json.gz'))
    expect(next.channelName).toBe('Rick Astley')
    expect(next.channelId).toBe('UCuAXFkgsw1L7xaCfnd5JJOw')
    expect(next.subscribersText).toBe('4.56M subscribers')
    expect(next.likesText).toBe('19M')
    expect(next.channelAvatar).toBeTruthy()
    expect(next.commentsToken).toBeTruthy()
    expect(next.title).toMatch(/Never Gonna Give You Up/)
    expect(next.related.length).toBeGreaterThanOrEqual(5)
    const first = next.related.find((r) => r.videoId === 'b2U2agYvFWU')!
    expect(first.durationText).toBe('1:46:23')
    expect(first.channel).toBe('Christmas Melodies')
    expect(first.viewsText).toBe('43K views')
    expect(first.ageText).toBe('3w ago')
    expect(first.thumbnail!.startsWith('https://i.ytimg.com/')).toBe(true)
    expect(first.channelAvatar!.startsWith('https://yt3.ggpht.com/')).toBe(true)
  })

  it('reads comments in order with the next page', () => {
    const page = parseComments(load('comments.json.gz'))
    expect(page.countText).toBe('2,462,578 Comments')
    expect(page.comments.length).toBeGreaterThanOrEqual(10)
    const top = page.comments[0]
    expect(top.author).toBe('@bdr8823')
    expect(top.text).toBe('My algebra teacher said that this was a link to the test answers')
    expect(top.likes).toBe('73K')
    expect(top.replies).toBe('721')
    expect(top.published).toBe('7 years ago')
    expect(page.next).toBeTruthy()
  })
})
