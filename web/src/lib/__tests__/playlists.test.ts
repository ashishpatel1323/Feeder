import { describe, expect, it } from 'vitest'
import { newFeed, type Item } from '../db'
import { parseDuration, parseFeed } from '../feedparse'
import {
  describeRules,
  EMPTY_RULES,
  formatTotal,
  itemKind,
  itemSeconds,
  makeContext,
  matchesRules,
  moveItem,
  newPlaylist,
  playlistItems,
  playStatus,
  readAloudSeconds,
  sortItems,
  starterPlaylists,
  withItems,
  withoutItems,
} from '../playlists'

const DAY = 86_400_000
const NOW = 1_800_000_000_000
let n = 0
function item(feedUrl: string, p: Partial<Item> = {}): Item {
  n++
  return { id: `${feedUrl}\n${n}`, feedUrl, guid: String(n), title: `t${n}`, link: null, contentHtml: '', snippet: '', imageUrl: null, author: null, sortTime: NOW - n * 1000, firstSeen: NOW, wordCount: 700, read: 0, readTime: null, readSynced: 1, saved: 0, ...p }
}

const feeds = new Map([
  ['pod', newFeed('pod', { title: 'Pod', tag: 'Podcasts' })],
  ['news', newFeed('news', { title: 'News', tag: 'News', fullTextByDefault: false })],
  ['yt', newFeed('yt', { title: 'YT', tag: '' })],
])
const ctx = (p: Partial<Parameters<typeof makeContext>[0]> = {}) => makeContext({ feeds, now: NOW, ...p })

const ep = (p: Partial<Item> = {}) => item('pod', { enclosureUrl: 'https://cdn.example.com/ep.mp3', duration: 1800, ...p })
const art = (p: Partial<Item> = {}) => item('news', p)
const vid = (p: Partial<Item> = {}) => item('yt', { link: 'https://www.youtube.com/watch?v=abcdefghijk', ...p })

describe('parseDuration', () => {
  it('reads seconds and clock times', () => {
    expect(parseDuration('3600')).toBe(3600)
    expect(parseDuration('61:05')).toBe(3665)
    expect(parseDuration('1:01:05')).toBe(3665)
    expect(parseDuration(' 45:00 ')).toBe(2700)
  })
  it('ignores junk', () => {
    expect(parseDuration('')).toBeNull()
    expect(parseDuration('about an hour')).toBeNull()
    expect(parseDuration('0')).toBeNull()
  })
})

describe('feed durations', () => {
  it('takes itunes:duration from RSS items', () => {
    const xml = `<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>P</title>
      <item><guid>1</guid><title>Ep</title><enclosure url="https://x/1.mp3" type="audio/mpeg"/><itunes:duration>48:10</itunes:duration></item>
      <item><guid>2</guid><title>No length</title></item></channel></rss>`
    const f = parseFeed(xml, 'https://x/feed')
    expect(f.articles[0].duration).toBe(2890)
    expect(f.articles[1].duration).toBeNull()
  })
  it('takes media:content duration from Atom entries', () => {
    const xml = `<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/"><title>T</title>
      <entry><id>a</id><title>V</title><link href="https://x/a"/><media:group><media:content url="https://x/a.mp4" duration="125"/></media:group></entry></feed>`
    expect(parseFeed(xml, 'https://x/atom').articles[0].duration).toBe(125)
  })
  it('takes duration_in_seconds from JSON Feed attachments', () => {
    const json = JSON.stringify({ version: 'https://jsonfeed.org/version/1.1', title: 'J', items: [{ id: '1', content_text: 'x', attachments: [{ url: 'https://x/1.mp3', mime_type: 'audio/mpeg', duration_in_seconds: 600 }] }] })
    expect(parseFeed(json, 'https://x/j.json').articles[0].duration).toBe(600)
  })
})

describe('itemKind', () => {
  it('tells podcasts, videos and articles apart', () => {
    expect(itemKind(ep())).toBe('podcast')
    expect(itemKind(vid())).toBe('youtube')
    expect(itemKind(art())).toBe('article')
    expect(itemKind(art({ enclosureUrl: 'https://x/cover.jpg' }))).toBe('article')
  })
  it('follows a feed set to Text', () => {
    expect(itemKind(ep(), 'text')).toBe('article')
  })
})

describe('lengths', () => {
  it('counts articles as their read-aloud time', () => {
    const s = readAloudSeconds(700)
    expect(s).toBe(300)
    expect(readAloudSeconds(700, 2)).toBe(150)
    expect(itemSeconds(art(), ctx())).toBe(300)
  })
  it('uses the feed length for episodes and the learned length for videos', () => {
    expect(itemSeconds(ep(), ctx())).toBe(1800)
    expect(itemSeconds(ep({ duration: null }), ctx())).toBeNull()
    expect(itemSeconds(vid(), ctx({ videoDurations: { abcdefghijk: 900 } }))).toBe(900)
  })
  it('formats totals', () => {
    expect(formatTotal(48 * 60)).toBe('48 min')
    expect(formatTotal(125 * 60)).toBe('2 h 05 m')
  })
})

describe('matchesRules', () => {
  it('matches everything with no rules', () => {
    expect(matchesRules(art(), EMPTY_RULES, ctx())).toBe(true)
  })
  it('filters by folder or feed', () => {
    const r = { ...EMPTY_RULES, folders: ['News'] }
    expect(matchesRules(art(), r, ctx())).toBe(true)
    expect(matchesRules(ep(), r, ctx())).toBe(false)
    expect(matchesRules(vid(), { ...r, feeds: ['yt'] }, ctx())).toBe(true)
  })
  it('filters by type, status, saved, downloaded and age', () => {
    expect(matchesRules(ep(), { ...EMPTY_RULES, kinds: ['podcast'] }, ctx())).toBe(true)
    expect(matchesRules(art(), { ...EMPTY_RULES, kinds: ['podcast'] }, ctx())).toBe(false)
    expect(matchesRules(art({ read: 1 }), { ...EMPTY_RULES, statuses: ['unread'] }, ctx())).toBe(false)
    expect(matchesRules(art(), { ...EMPTY_RULES, savedOnly: true }, ctx())).toBe(false)
    expect(matchesRules(art({ offlineAt: 1 }), { ...EMPTY_RULES, downloadedOnly: true }, ctx())).toBe(true)
    expect(matchesRules(art({ sortTime: NOW - 3 * DAY }), { ...EMPTY_RULES, withinDays: 2 }, ctx())).toBe(false)
    expect(matchesRules(art({ sortTime: NOW - DAY }), { ...EMPTY_RULES, withinDays: 2 }, ctx())).toBe(true)
  })
  it('knows what is in progress', () => {
    const e = ep()
    const c = ctx({ positions: new Map([[e.id, { t: 600, d: 1800 }]]) })
    expect(playStatus(e, c)).toBe('inprogress')
    expect(matchesRules(e, { ...EMPTY_RULES, statuses: ['unread'] }, c)).toBe(false)
    expect(matchesRules(e, { ...EMPTY_RULES, statuses: ['unread', 'inprogress'] }, c)).toBe(true)
  })
  it('filters by length, leaving out unknown lengths', () => {
    const under40 = { ...EMPTY_RULES, maxMinutes: 40 }
    expect(matchesRules(ep(), under40, ctx())).toBe(true)
    expect(matchesRules(ep({ duration: 3600 }), under40, ctx())).toBe(false)
    expect(matchesRules(ep({ duration: null }), under40, ctx())).toBe(false)
    expect(matchesRules(art(), { ...EMPTY_RULES, minMinutes: 10 }, ctx())).toBe(false)
  })
})

describe('playlist items', () => {
  it('sorts smart playlists', () => {
    const a = ep({ duration: 600, sortTime: NOW - 1 })
    const b = ep({ duration: 3000, sortTime: NOW - 2 })
    const c = ep({ duration: null, sortTime: NOW - 3 })
    expect(sortItems([c, b, a], 'newest', ctx()).map((i) => i.id)).toEqual([a.id, b.id, c.id])
    expect(sortItems([a, b, c], 'oldest', ctx()).map((i) => i.id)).toEqual([c.id, b.id, a.id])
    expect(sortItems([c, b, a], 'shortest', ctx()).map((i) => i.id)).toEqual([a.id, b.id, c.id])
    expect(sortItems([c, a, b], 'longest', ctx()).map((i) => i.id)).toEqual([b.id, a.id, c.id])
  })
  it('keeps the hand-made order of manual playlists and drops deleted items', () => {
    const a = art()
    const b = ep()
    const pl = newPlaylist({ name: 'Mine', smart: false, itemIds: [b.id, 'gone', a.id] })
    expect(playlistItems(pl, [a, b], ctx()).map((i) => i.id)).toEqual([b.id, a.id])
  })
  it('adds, removes and moves items in manual playlists', () => {
    let pl = newPlaylist({ name: 'Mine', smart: false })
    pl = withItems(pl, ['a', 'b', 'a', 'c'])
    expect(pl.itemIds).toEqual(['a', 'b', 'c'])
    expect(moveItem(pl, 'c', 0).itemIds).toEqual(['c', 'a', 'b'])
    expect(moveItem(pl, 'a', 2).itemIds).toEqual(['b', 'c', 'a'])
    expect(withoutItems(pl, ['b']).itemIds).toEqual(['a', 'c'])
  })
  it('starts with three smart playlists', () => {
    const s = starterPlaylists()
    expect(s.map((p) => p.name)).toEqual(['Morning drive', 'New podcast episodes', 'Short reads'])
    expect(new Set(s.map((p) => p.color)).size).toBe(3)
    const drive = s[0]
    expect(describeRules(drive.rules, feeds).map((c) => c.value)).toEqual(['Podcast · Article', 'Unread or In progress', 'last 2 days', 'under 40 min'])
  })
})
