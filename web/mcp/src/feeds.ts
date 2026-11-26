/** Fetches the synced feeds and stores their articles. Article text isn't synced, so this is how it gets here. */
import { Readability } from '@mozilla/readability'
import { parseHtml } from './dom.js'
import type { Db } from './db.js'
import { countWords, htmlToText, parseFeed } from './vendor/feedparse.js'
import { bestTranscriptLink, type TranscriptLink } from './vendor/transcriptFormats.js'

const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36 feeder-mcp'

export async function fetchText(url: string, timeoutMs = 20_000): Promise<{ text: string; type: string; url: string }> {
  const res = await fetch(url, { headers: { 'user-agent': UA, accept: '*/*' }, redirect: 'follow', signal: AbortSignal.timeout(timeoutMs) })
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`)
  return { text: await res.text(), type: res.headers.get('content-type') ?? '', url: res.url || url }
}

interface FeedRow {
  url: string
  title: string
  alternate_id: number
}

export async function fetchFeed(db: Db, feed: FeedRow): Promise<number> {
  const now = Date.now()
  try {
    const { text } = await fetchText(feed.url)
    const parsed = parseFeed(text, feed.url, !!feed.alternate_id)
    const transcripts = /podcast:transcript/i.test(text) ? transcriptIndex(text) : null
    const exists = db.prepare('SELECT id, sort_time FROM items WHERE id = ?')
    const upsert = db.prepare(
      `INSERT INTO items (id, feed_url, guid, title, link, author, image_url, sort_time, first_seen, content_html, snippet, word_count,
                          enclosure_url, duration, transcript_url, transcript_type, stub)
       VALUES (@id, @feed, @guid, @title, @link, @author, @image, @sort, @now, @html, @snippet, @words, @enc, @dur, @turl, @ttype, 0)
       ON CONFLICT(id) DO UPDATE SET title = excluded.title, link = excluded.link, author = excluded.author,
         image_url = COALESCE(excluded.image_url, items.image_url), sort_time = excluded.sort_time, content_html = excluded.content_html,
         snippet = excluded.snippet, word_count = excluded.word_count, enclosure_url = excluded.enclosure_url,
         duration = COALESCE(excluded.duration, items.duration),
         transcript_url = COALESCE(excluded.transcript_url, items.transcript_url),
         transcript_type = COALESCE(excluded.transcript_type, items.transcript_type), stub = 0`,
    )
    let added = 0
    db.transaction(() => {
      parsed.articles.forEach((a, index) => {
        const id = `${feed.url}\n${a.guid}`
        const old = exists.get(id) as { sort_time: number } | undefined
        if (!old) added++
        let turl: string | null = null
        let ttype: string | null = null
        if (transcripts && a.enclosureUrl) {
          const links = transcripts.get(a.guid.trim()) ?? transcripts.get(bare(a.enclosureUrl)) ?? []
          const best = bestTranscriptLink(links) ?? links[0] ?? null
          if (best) {
            turl = best.url
            ttype = best.type
          }
        }
        upsert.run({
          id,
          feed: feed.url,
          guid: a.guid,
          title: a.title,
          link: a.link,
          author: a.author,
          image: a.imageUrl,
          sort: a.published ?? old?.sort_time ?? now - index * 1000,
          now,
          html: a.contentHtml,
          snippet: a.snippet,
          words: countWords(htmlToText(a.contentHtml)),
          enc: a.enclosureUrl,
          dur: a.duration ?? null,
          turl,
          ttype,
        })
      })
      db.prepare(
        `UPDATE feeds SET title = CASE WHEN title = '' THEN ? ELSE title END, site_url = ?, last_fetched = ?, last_error = NULL WHERE url = ?`,
      ).run(parsed.title, parsed.siteUrl, now, feed.url)
    })()
    return added
  } catch (e) {
    db.prepare('UPDATE feeds SET last_fetched = ?, last_error = ? WHERE url = ?').run(now, String((e as Error).message ?? e).slice(0, 300), feed.url)
    return 0
  }
}

/** Fetches every feed (or the given ones), six at a time. */
export async function refreshFeeds(db: Db, urls?: string[], onProgress?: (done: number, total: number) => void): Promise<{ feeds: number; added: number; errors: number }> {
  const all = db.prepare('SELECT url, title, alternate_id FROM feeds WHERE deleted = 0').all() as FeedRow[]
  const feeds = urls ? all.filter((f) => urls.includes(f.url)) : all
  let next = 0
  let added = 0
  let done = 0
  const worker = async () => {
    while (next < feeds.length) {
      const f = feeds[next++]
      const n = await fetchFeed(db, f)
      added += n
      onProgress?.(++done, feeds.length)
    }
  }
  await Promise.all(Array.from({ length: Math.min(6, feeds.length) }, worker))
  const errors = (db.prepare('SELECT count(*) n FROM feeds WHERE deleted = 0 AND last_error IS NOT NULL').get() as { n: number }).n
  return { feeds: feeds.length, added, errors }
}

/** The article's full text from its web page, with Readability (as "Full text" does in the app). */
export async function fetchFullText(db: Db, id: string, link: string): Promise<string | null> {
  try {
    const { text, url } = await fetchText(link)
    const doc = parseHtml(text, url)
    const art = new Readability(doc).parse()
    if (!art?.content) return null
    db.prepare('UPDATE items SET full_html = ?, full_word_count = ? WHERE id = ?').run(art.content, countWords(art.textContent ?? ''), id)
    return art.content
  } catch {
    return null
  }
}

const bare = (u: string) => u.trim().replace(/^https?:\/\//, '').split('?')[0]

/** podcast:transcript links of every episode, by guid and by bare enclosure URL (one XML parse per feed). */
function transcriptIndex(feedXml: string): Map<string, TranscriptLink[]> {
  const out = new Map<string, TranscriptLink[]>()
  const doc = new DOMParser().parseFromString(feedXml, 'application/xml')
  for (const item of Array.from(doc.getElementsByTagName('item'))) {
    const links = Array.from(item.getElementsByTagName('*'))
      .filter((e) => e.localName === 'transcript' && (e.prefix === 'podcast' || e.namespaceURI?.includes('podcastindex')))
      .map((e) => ({
        url: (e.getAttribute('url') ?? '').trim(),
        type: e.getAttribute('type')?.trim() || null,
        language: e.getAttribute('language')?.trim() || null,
        rel: e.getAttribute('rel')?.trim() || null,
      }))
      .filter((l) => l.url)
    if (!links.length) continue
    const guid = Array.from(item.children).find((c) => c.localName === 'guid')?.textContent?.trim()
    if (guid) out.set(guid, links)
    for (const c of Array.from(item.children)) if (c.localName === 'enclosure' && c.getAttribute('url')) out.set(bare(c.getAttribute('url')!), links)
  }
  return out
}
