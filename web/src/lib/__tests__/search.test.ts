import { describe, expect, it } from 'vitest'
import type { Feed, Highlight, Item } from '../db'
import { makeContext, newPlaylist } from '../playlists'
import { findTerms, fold, hitsInScope, htmlText, markTerms, queryTerms, searchAll, snippetAround, type SearchData } from '../search'

const NOW = Date.UTC(2026, 10, 24)

function feed(url: string, title: string, tag = ''): Feed {
  return { url, title, customTitle: '', tag, imageUrl: null, fullTextByDefault: false, openArticlesWith: '', alternateId: false, fetchOgImages: false, blockRules: '', allowRules: '', whenModified: 0 }
}

function item(id: string, p: Partial<Item> = {}): Item {
  return {
    id,
    feedUrl: 'https://a.example/feed',
    guid: id,
    title: '',
    link: `https://a.example/${id}`,
    contentHtml: '',
    snippet: '',
    imageUrl: null,
    author: null,
    sortTime: NOW - 86_400_000,
    firstSeen: NOW,
    wordCount: 100,
    read: 0,
    readTime: null,
    readSynced: 1,
    saved: 0,
    ...p,
  }
}

function data(p: Partial<SearchData> = {}): SearchData {
  const feeds = new Map([
    ['https://a.example/feed', feed('https://a.example/feed', 'The Verge', 'Tech')],
    ['https://pod.example/feed', feed('https://pod.example/feed', 'Planet Money', 'Economics')],
  ])
  return { items: [], feeds, highlights: [], playlists: [], transcripts: new Map(), ctx: makeContext({ feeds }), now: NOW, ...p }
}

describe('text helpers', () => {
  it('folds case and accents without moving positions', () => {
    expect(fold('Café NVIDIA')).toBe('cafe nvidia')
    expect(fold('Café').length).toBe(4)
  })
  it('splits a query into distinct folded words', () => {
    expect(queryTerms('  Nvidia  nvidia CHIPS ')).toEqual(['nvidia', 'chips'])
    expect(queryTerms('   ')).toEqual([])
  })
  it('turns HTML into one line of text', () => {
    expect(htmlText('<p>Hello&nbsp;<b>world</b> &amp; more</p><script>x()</script><p>&#8217;s &#x41;</p>')).toBe('Hello world & more ’s A')
    expect(htmlText('bad &#99999999; entity')).toBe('bad entity')
  })
  it('needs every term to match', () => {
    expect(findTerms('nvidia rubin chips', ['nvidia', 'chips'])).toEqual([
      [0, 6],
      [13, 18],
    ])
    expect(findTerms('nvidia rubin', ['nvidia', 'chips'])).toBeNull()
  })
  it('marks every occurrence', () => {
    expect(markTerms('NVIDIA buys from Nvidia', ['nvidia']).ranges).toEqual([
      [0, 6],
      [17, 23],
    ])
  })
  it('cuts a snippet around the first match at word edges', () => {
    const text = `${'word '.repeat(40)}Oracle buys chips from NVIDIA to build ${'more '.repeat(60)}`
    const s = snippetAround(text, fold(text), ['nvidia'], 30, 100)
    expect(s.text.startsWith('…')).toBe(true)
    expect(s.text.endsWith('…')).toBe(true)
    const [a, b] = s.ranges[0]
    expect(s.text.slice(a, b)).toBe('NVIDIA')
  })
})

describe('searchAll', () => {
  it('finds nothing for an empty query', () => {
    const r = searchAll('  ', data({ items: [item('1', { title: 'Nvidia' })] }))
    expect(r.items).toEqual([])
    expect(r.counts.all).toBe(0)
  })

  it('ranks a title match above a match in the text', () => {
    const r = searchAll(
      'nvidia',
      data({
        items: [
          item('text', { title: 'Data centers', contentHtml: '<p>Oracle buys chips from NVIDIA.</p>' }),
          item('title', { title: "Nvidia's Rubin chips ship early" }),
        ],
      }),
    )
    expect(r.items.map((h) => [h.id, h.field])).toEqual([
      ['title', 'title'],
      ['text', 'text'],
    ])
    expect(r.items[1].snippet?.text).toContain('NVIDIA')
  })

  it('finds a podcast episode by its saved transcript, with the line time', () => {
    const ep = item('ep', { feedUrl: 'https://pod.example/feed', title: 'Two ways big bets on data centers might affect you', enclosureUrl: 'https://pod.example/ep.mp3' })
    const r = searchAll(
      'nvidia',
      data({
        items: [ep],
        transcripts: new Map([
          [
            'ep',
            [
              { startMs: 960_000, text: 'This bit has more implications.' },
              { startMs: 977_000, text: 'So, Oracle buys chips from NVIDIA to build this cloud.' },
              { startMs: 982_000, text: 'And then NVIDIA invests money into OpenAI.' },
            ],
          ],
        ]),
      }),
    )
    expect(r.items).toHaveLength(1)
    expect(r.items[0]).toMatchObject({ kind: 'podcast', field: 'transcript', atMs: 977_000, transcriptMatches: 2 })
    expect(r.counts.podcast).toBe(1)
  })

  it('matches words split between the title and the text', () => {
    const r = searchAll('rubin oracle', data({ items: [item('1', { title: 'Rubin ships', contentHtml: 'Oracle is a buyer' })] }))
    expect(r.items.map((h) => h.id)).toEqual(['1'])
  })

  it('finds highlights by their text or note, feeds, folders and playlists', () => {
    const hl: Highlight = {
      id: 'h1',
      itemId: 'x',
      itemTitle: 'Two ways big bets',
      itemLink: null,
      feedTitle: 'Planet Money',
      imageUrl: null,
      text: 'And then NVIDIA invests money into OpenAI',
      start: 0,
      prefix: '',
      color: 'yellow',
      note: 'circular money',
      createdAt: NOW,
    }
    const pl = newPlaylist({ name: 'Money talks', smart: false })
    const d = data({ highlights: [hl], playlists: [pl] })
    expect(searchAll('nvidia', d).highlights.map((h) => h.id)).toEqual(['h1'])
    expect(searchAll('circular', d).highlights.map((h) => h.id)).toEqual(['h1'])
    const money = searchAll('money', d)
    expect(money.library.map((h) => h.type).sort()).toEqual(['feed', 'playlist'])
    expect(searchAll('econ', d).library.map((h) => h.type)).toEqual(['folder'])
    expect(searchAll('verge', d).library[0]).toMatchObject({ type: 'feed', id: 'https://a.example/feed' })
  })

  it('counts each scope and lists a scope best first', () => {
    const d = data({
      items: [
        item('a', { title: 'Chips everywhere' }),
        item('v', { title: 'Chips video', link: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' }),
        item('p', { title: 'Chips pod', enclosureUrl: 'https://pod.example/x.mp3' }),
      ],
    })
    const r = searchAll('chips', d)
    expect(r.counts).toMatchObject({ all: 3, article: 1, youtube: 1, podcast: 1, highlight: 0, library: 0 })
    expect(hitsInScope(r, 'youtube').map((h) => h.id)).toEqual(['v'])
    expect(hitsInScope(r, 'all')).toHaveLength(3)
  })

  it('finds accented titles without accents typed', () => {
    const r = searchAll('cafe', data({ items: [item('1', { title: 'Le Café du Coin' })] }))
    expect(r.items[0].title.ranges).toEqual([[3, 7]])
  })
})
