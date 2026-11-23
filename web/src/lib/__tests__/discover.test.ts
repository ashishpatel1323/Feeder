import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  compactNumber,
  CURATED_TOPICS,
  defaultCountry,
  feedlyQueries,
  googleNewsResult,
  isFollowed,
  looksLikeUrl,
  parseChartIds,
  parseFeedly,
  parseItunes,
  parseYouTubeChannels,
  searchCurated,
  youtubeFeedUrl,
} from '../discover'

describe('Discover sources', () => {
  it('reads Feedly results and keeps the feed address', () => {
    const r = parseFeedly({
      results: [
        { feedId: 'feed/http://www.livemint.com/rss/opinion', title: 'mint - opinion', website: 'https://www.livemint.com', subscribers: 9483, description: 'Opinion' },
        { feedId: 'user/abc/category/x', title: 'not a feed' },
        { id: 'feed/https://www.youtube.com/feeds/videos.xml?channel_id=UCHnyfMqiRRG1u-2MsSQLbXA', title: 'Veritasium' },
      ],
    })
    expect(r).toHaveLength(2)
    expect(r[0]).toMatchObject({ kind: 'text', feedUrl: 'http://www.livemint.com/rss/opinion', meta: '9.5K Feedly followers' })
    expect(r[1].kind).toBe('youtube')
  })

  it('retries strict Feedly searches with joined words and single words', () => {
    expect(feedlyQueries('Mint India')).toEqual(['mint india', 'mintindia', 'india', 'mint'])
    expect(feedlyQueries('livemint')).toEqual(['livemint'])
  })

  it('reads podcasts from iTunes search and chart ids', () => {
    const r = parseItunes({ results: [{ collectionName: 'Lex Fridman Podcast', artistName: 'Lex Fridman', feedUrl: 'https://lexfridman.com/feed/podcast/', artworkUrl600: 'https://x/600.jpg', trackCount: 500 }, { collectionName: 'No feed' }] })
    expect(r).toEqual([{ kind: 'podcast', title: 'Lex Fridman Podcast', feedUrl: 'https://lexfridman.com/feed/podcast/', site: undefined, image: 'https://x/600.jpg', meta: 'Lex Fridman · 500 episodes' }])
    expect(parseChartIds({ feed: { entry: [{ id: { attributes: { 'im:id': '1' } } }, { id: { attributes: { 'im:id': '2' } } }] } })).toEqual(['1', '2'])
    expect(parseChartIds({ feed: { entry: { id: { attributes: { 'im:id': '7' } } } } })).toEqual(['7'])
    expect(parseChartIds({})).toEqual([])
  })

  it('reads channels from a YouTube search page', () => {
    const data = {
      contents: {
        list: [
          {
            channelRenderer: {
              channelId: 'UCHnyfMqiRRG1u-2MsSQLbXA',
              title: { simpleText: 'Veritasium' },
              thumbnail: { thumbnails: [{ url: '//yt3.ggpht.com/small' }, { url: '//yt3.ggpht.com/big' }] },
              descriptionSnippet: { runs: [{ text: 'An element of ' }, { text: 'truth' }] },
              videoCountText: { simpleText: '21.5M subscribers' },
              subscriberCountText: { simpleText: '@veritasium' },
            },
          },
          { videoRenderer: { videoId: 'x' } },
        ],
      },
    }
    const html = `<html><script>var ytInitialData = ${JSON.stringify(data)};</script></html>`
    const r = parseYouTubeChannels(html)
    expect(r).toEqual([
      {
        kind: 'youtube',
        title: 'Veritasium',
        feedUrl: youtubeFeedUrl('UCHnyfMqiRRG1u-2MsSQLbXA'),
        site: 'https://www.youtube.com/@veritasium',
        image: 'https://yt3.ggpht.com/big',
        description: 'An element of truth',
        meta: '@veritasium · 21.5M subscribers',
      },
    ])
    expect(parseYouTubeChannels('<html>nothing</html>')).toEqual([])
  })

  it('makes a Google News search feed for the browser region', () => {
    expect(googleNewsResult('mint india', 'en-IN').feedUrl).toBe('https://news.google.com/rss/search?q=mint%20india&hl=en-IN&gl=IN&ceid=IN:en')
  })

  it('knows followed feeds, YouTube channels by id', () => {
    const followed = new Set(['https://www.youtube.com/feeds/videos.xml?channel_id=UCHnyfMqiRRG1u-2MsSQLbXA&x=1', 'https://www.livemint.com/rss/news'])
    expect(isFollowed({ kind: 'youtube', title: '', feedUrl: youtubeFeedUrl('UCHnyfMqiRRG1u-2MsSQLbXA') }, followed)).toBe(true)
    expect(isFollowed({ kind: 'text', title: '', feedUrl: 'http://livemint.com/rss/news/' }, followed)).toBe(true)
    expect(isFollowed({ kind: 'text', title: '', feedUrl: 'https://www.livemint.com/rss/opinion' }, followed)).toBe(false)
  })

  it('tells addresses from words', () => {
    expect(looksLikeUrl('livemint.com')).toBe(true)
    expect(looksLikeUrl('https://example.com/feed')).toBe(true)
    expect(looksLikeUrl('mint india')).toBe(false)
    expect(looksLikeUrl('livemint')).toBe(false)
  })

  it('formats counts and picks a chart country', () => {
    expect(compactNumber(9483)).toBe('9.5K')
    expect(compactNumber(21_500_000)).toBe('22M')
    expect(compactNumber(1_200_000)).toBe('1.2M')
    expect(defaultCountry('en-IN', 'UTC')).toBe('in')
    expect(defaultCountry('en-US', 'Asia/Kolkata')).toBe('in')
    expect(defaultCountry('fr-FR', 'Europe/Paris')).toBe('us')
  })
})

describe('Bundled topics', () => {
  it('has India business with the Livemint sections', () => {
    const t = CURATED_TOPICS.find((x) => x.name === 'India: Business')
    expect(t?.feeds.map((f) => f.u)).toEqual(expect.arrayContaining(['https://www.livemint.com/rss/markets', 'https://www.livemint.com/rss/money']))
    expect(searchCurated('mint markets').map((r) => r.feedUrl)).toContain('https://www.livemint.com/rss/markets')
  })

  it('is the same list the Android app ships', () => {
    const web = readFileSync(resolve(process.cwd(), 'src/lib/discover-feeds.json'), 'utf8')
    const android = readFileSync(resolve(process.cwd(), '../app/src/main/assets/discover-feeds.json'), 'utf8')
    expect(android).toBe(web)
  })
})
