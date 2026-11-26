import { describe, expect, it } from 'vitest'
import { newFeed, type Item, type VideoPos, type YouTubeMeta } from '../db'
import { ageText, buildTab, channelFeedUrl, channelInputToUrl, channelsShown, channelsOf, durationText, filterRows, isYouTubeFeed, loadPrefs, parseTakeoutCsv } from '../youtubeTab'
import { normalizeBarTabs, shownTab } from '../../components/Tabs'

const A = channelFeedUrl('UCaaaaaaaaaaaaaaaaaaaaaa')
const B = channelFeedUrl('UCbbbbbbbbbbbbbbbbbbbbbb')
const item = (feedUrl: string, id: string, t: number, extra: Partial<Item> = {}): Item => ({
  id: `${feedUrl}\n${id}`,
  feedUrl,
  guid: id,
  title: `Video ${id}`,
  link: `https://www.youtube.com/watch?v=${id}`,
  contentHtml: '',
  snippet: '',
  imageUrl: null,
  author: null,
  sortTime: t,
  firstSeen: t,
  wordCount: 0,
  read: 0,
  readTime: null,
  readSynced: 0,
  saved: 0,
  ...extra,
})

describe('YouTube tab (youtubeTab.ts)', () => {
  const feeds = [newFeed(A, { title: 'Alpha', tag: 'Science' }), newFeed(B, { title: 'Beta', youtubeMusic: true }), newFeed('https://example.com/feed', { title: 'Blog', tag: 'News' })]
  const now = Date.now()
  const items = [item(A, 'aaaaaaaaaa1', now - 1000), item(B, 'bbbbbbbbbb1', now - 500), item(A, 'aaaaaaaaaa2', now - 2000), item('https://example.com/feed', 'x', now)]

  it('several groups at once: videos and Shorts of channels in any picked group', () => {
    const C = channelFeedUrl('UCcccccccccccccccccccccc')
    const fs = [newFeed(A, { title: 'Alpha', tag: 'Science' }), newFeed(B, { title: 'Beta', tag: 'Health' }), newFeed(C, { title: 'Gamma', tag: 'Music' })]
    const it2 = [item(A, 'aaaaaaaaaa1', now - 1000), item(B, 'bbbbbbbbbb1', now - 500), item(C, 'cccccccccc1', now - 100), item(C, 'cccccccccc2', now - 50, { link: 'https://www.youtube.com/shorts/cccccccccc2' })]
    const two = buildTab(fs, it2, new Map(), new Map(), ['Science', 'Music'])
    expect(two.picked).toEqual(['Music', 'Science'])
    expect(two.videos.map((r) => r.videoId)).toEqual(['cccccccccc1', 'aaaaaaaaaa1'])
    expect(two.shorts.map((r) => r.videoId)).toEqual(['cccccccccc2'])
    expect(buildTab(fs, it2, new Map(), new Map(), ['Health']).shorts).toEqual([])
  })

  it('only takes YouTube feeds, groups are their folders, videos newest first', () => {
    expect(isYouTubeFeed(A)).toBe(true)
    expect(isYouTubeFeed('https://example.com/feed')).toBe(false)
    const meta = new Map<string, YouTubeMeta>([['aaaaaaaaaa2', { id: 'aaaaaaaaaa2', duration: 61, views: 1200, isLive: false, isShort: true, at: now }]])
    const pos = new Map<string, VideoPos>([[`${A}\naaaaaaaaaa1`, { id: `${A}\naaaaaaaaaa1`, t: 30, d: 120, ts: now }]])
    const s = buildTab(feeds, items, meta, pos, [])
    expect(s.channels.map((c) => c.title).sort()).toEqual(['Alpha', 'Beta'])
    expect(s.groups).toEqual(['Science'])
    expect(s.videos.map((r) => r.videoId)).toEqual(['bbbbbbbbbb1', 'aaaaaaaaaa1'])
    expect(s.shorts.map((r) => r.videoId)).toEqual(['aaaaaaaaaa2'])
    expect(s.videos[1].duration).toBe(120)
    expect(s.videos[1].progress).toBeCloseTo(0.25)
    expect(buildTab(feeds, items, meta, pos, ['Science']).videos.map((r) => r.videoId)).toEqual(['aaaaaaaaaa1'])
    // A group that no longer exists is dropped from the pick; none left = All
    expect(buildTab(feeds, items, meta, pos, ['Gone']).picked).toEqual([])
    expect(buildTab(feeds, items, meta, pos, ['Gone', 'Science']).picked).toEqual(['Science'])
    expect(filterRows(s.videos, 'beta').map((r) => r.videoId)).toEqual(['bbbbbbbbbb1'])
  })

  it('splits video and music channels and sorts them', () => {
    const chans = channelsOf(feeds, items, now)
    expect(channelsShown(chans, '', false, 'name').map((c) => c.title)).toEqual(['Alpha'])
    expect(channelsShown(chans, '', true, 'name').map((c) => c.title)).toEqual(['Beta'])
    expect(chans.find((c) => c.title === 'Alpha')!.recentCount).toBe(2)
  })

  it('reads Takeout subscriptions.csv', () => {
    const csv = 'Channel Id,Channel Url,Channel Title\nUCaaaaaaaaaaaaaaaaaaaaaa,http://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa,"Alpha, the channel"\n\nUCaaaaaaaaaaaaaaaaaaaaaa,x,dup\n'
    expect(parseTakeoutCsv(csv)).toEqual([{ id: 'UCaaaaaaaaaaaaaaaaaaaaaa', title: 'Alpha, the channel' }])
  })

  it('turns links, handles and ids into something to subscribe to', () => {
    expect(channelInputToUrl('@veritasium')).toBe('https://www.youtube.com/@veritasium')
    expect(channelInputToUrl('veritasium')).toBe('https://www.youtube.com/@veritasium')
    expect(channelInputToUrl('https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa')).toBe(A)
    expect(channelInputToUrl('https://www.youtube.com/playlist?list=PL123')).toBe('https://www.youtube.com/feeds/videos.xml?playlist_id=PL123')
    expect(channelInputToUrl(A)).toBe(A)
    expect(channelInputToUrl('')).toBeNull()
  })

  it('words lengths and ages as YouTube does', () => {
    expect(durationText(213)).toBe('3:33')
    expect(durationText(6383)).toBe('1:46:23')
    expect(ageText(now - 3 * 86_400_000, now)).toBe('3 days ago')
    expect(ageText(now - 3_600_000, now)).toBe('1 hour ago')
  })
})

describe('Bottom bar tabs (Tabs.tsx)', () => {
  it('keeps Feeds and Library, drops unknown names and caps at five', () => {
    expect(normalizeBarTabs(['youtube', 'nope'])).toEqual(['youtube', 'feeds', 'library'])
    expect(normalizeBarTabs(['feeds', 'youtube', 'playlists', 'books', 'audiobooks', 'zim', 'library'])).toEqual(['feeds', 'youtube', 'playlists', 'books', 'library'])
  })
  it('lights up Library for a tab that is not in the bar', () => {
    expect(shownTab('zim', ['feeds', 'youtube', 'library'])).toBe('library')
    expect(shownTab('youtube', ['feeds', 'youtube', 'library'])).toBe('youtube')
  })
})

describe('YouTube tab prefs', () => {
  const store = (v: unknown) => ({ getItem: () => JSON.stringify(v) })
  it('keeps several groups, and reads the single group older versions saved', () => {
    expect(loadPrefs(store({ groups: ['A', 'B'] })).groups).toEqual(['A', 'B'])
    expect(loadPrefs(store({ group: 'Science' })).groups).toEqual(['Science'])
    expect(loadPrefs(store({ group: null })).groups).toEqual([])
    expect(loadPrefs(store({})).groups).toEqual([])
  })
})
