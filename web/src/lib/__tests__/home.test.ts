import { describe, expect, it } from 'vitest'
import { newFeed, type Item } from '../db'
import { buildHomeFeeds, detectKind, filterHome, groupByFolder, initials, NO_FILTER, sortHome } from '../home'

let n = 0
function item(feedUrl: string, p: Partial<Item> = {}): Item {
  n++
  return { id: `${feedUrl}\n${n}`, feedUrl, guid: String(n), title: 't', link: null, contentHtml: '', snippet: '', imageUrl: null, author: null, sortTime: n, firstSeen: n, wordCount: 10, read: 0, readTime: null, readSynced: 1, saved: 0, ...p }
}

describe('detectKind', () => {
  it('spots YouTube feeds by address', () => {
    expect(detectKind({ url: 'https://www.youtube.com/feeds/videos.xml?channel_id=x' }, [])).toBe('youtube')
  })
  it('spots YouTube feeds by their links', () => {
    const its = [item('a', { link: 'https://www.youtube.com/watch?v=abcdefghijk' }), item('a', { link: 'https://youtu.be/abcdefghijk' })]
    expect(detectKind({ url: 'https://example.com/rss' }, its)).toBe('youtube')
  })
  it('spots podcasts by audio attachments', () => {
    const its = [item('p', { enclosureUrl: 'https://cdn.example.com/ep1.mp3?x=1' }), item('p', { enclosureUrl: 'https://cdn.example.com/ep2' })]
    expect(detectKind({ url: 'https://example.com/pod' }, its)).toBe('podcast')
  })
  it('treats image attachments and plain posts as text', () => {
    const its = [item('t', { enclosureUrl: 'https://example.com/a.jpg' }), item('t')]
    expect(detectKind({ url: 'https://example.com/blog' }, its)).toBe('text')
    expect(detectKind({ url: 'https://example.com/empty' }, [])).toBe('text')
  })
})

describe('home list', () => {
  const feeds = [
    newFeed('https://a.example/rss', { title: 'Alpha', tag: 'Tech' }),
    newFeed('https://b.example/rss', { title: 'Bravo', tag: 'Tech' }),
    newFeed('https://c.example/rss', { title: 'Charlie', tag: '' }),
    newFeed('https://www.youtube.com/feeds/videos.xml?channel_id=d', { title: 'Delta', tag: 'Fun' }),
  ]
  const items = [
    item('https://a.example/rss', { sortTime: 10 }),
    item('https://b.example/rss', { sortTime: 30, read: 1 }),
    item('https://c.example/rss', { sortTime: 20 }),
    item('https://c.example/rss', { sortTime: 21 }),
  ]
  const home = buildHomeFeeds(feeds, items, { 'https://c.example/rss': 'podcast' })

  it('counts unread and uses a picked type over the guess', () => {
    const c = home.find((h) => h.title === 'Charlie')!
    expect(c.unread).toBe(2)
    expect(c.kind).toBe('podcast')
    expect(home.find((h) => h.title === 'Delta')!.kind).toBe('youtube')
  })

  it('filters by type, folder and unread, and can skip a filter for chip counts', () => {
    expect(filterHome(home, { ...NO_FILTER, kind: 'text' }).map((h) => h.title)).toEqual(['Alpha', 'Bravo'])
    expect(filterHome(home, { ...NO_FILTER, folder: '' }).map((h) => h.title)).toEqual(['Charlie'])
    expect(filterHome(home, { ...NO_FILTER, unreadOnly: true }).map((h) => h.title)).toEqual(['Alpha', 'Charlie'])
    expect(filterHome(home, { ...NO_FILTER, kind: 'text', folder: 'Fun' }, ['kind']).map((h) => h.title)).toEqual(['Delta'])
  })

  it('sorts by latest, name and unread', () => {
    expect(sortHome(home, 'latest').map((h) => h.title)).toEqual(['Bravo', 'Charlie', 'Alpha', 'Delta'])
    expect(sortHome(home, 'az').map((h) => h.title)).toEqual(['Alpha', 'Bravo', 'Charlie', 'Delta'])
    expect(sortHome(home, 'unread')[0].title).toBe('Charlie')
  })

  it('groups folders first, then feeds with no folder', () => {
    const g = groupByFolder(home, 'az')
    expect(g.folders.map((f) => [f.name, f.feeds.length, f.unread])).toEqual([
      ['Fun', 1, 0],
      ['Tech', 2, 1],
    ])
    expect(g.loose.map((h) => h.title)).toEqual(['Charlie'])
  })
})

describe('initials', () => {
  it('takes two letters', () => {
    expect(initials('Hacker News')).toBe('HN')
    expect(initials('The Verge')).toBe('VE')
    expect(initials('Stratechery')).toBe('ST')
    expect(initials('Paul Graham: Essays')).toBe('PG')
  })
})
