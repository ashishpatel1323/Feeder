/** Fetching feeds and storing their articles. */
import { proxyFetch } from './api'
import { db, getKv, itemId, newFeed, type Feed, type Item } from './db'
import { countWords, findFeedLinks, htmlToText, parseFeed } from './feedparse'
import { makeFilter } from './filterrules'

export const MAX_ITEMS_PER_FEED_KEY = 'maxItemsPerFeed'

async function fetchOne(feed: Feed): Promise<number> {
  const { text } = await proxyFetch(feed.url)
  const parsed = parseFeed(text, feed.url, feed.alternateId)
  const keep = makeFilter(feed.blockRules, feed.allowRules)
  const now = Date.now()
  const articles = parsed.articles.filter(keep)
  let added = 0
  const max = await getKv<number>(MAX_ITEMS_PER_FEED_KEY, 200)

  await db.transaction('rw', db.items, db.feeds, db.remoteReadMarks, async () => {
    const ids = articles.map((a) => itemId(feed.url, a.guid))
    const existing = new Map((await db.items.bulkGet(ids)).filter(Boolean).map((i) => [i!.id, i!]))
    const remoteRead = new Set(
      (await db.remoteReadMarks.bulkGet(ids)).filter(Boolean).map((r) => r!.key),
    )
    const rows: Item[] = []
    // Feeds list newest first; give undated items a slightly older time the further down they are
    articles.forEach((a, index) => {
      const id = itemId(feed.url, a.guid)
      const old = existing.get(id)
      const fallback = old?.sortTime ?? now - index * 1000
      const words = countWords(htmlToText(a.contentHtml))
      if (old) {
        rows.push({
          ...old,
          title: a.title,
          link: a.link,
          contentHtml: a.contentHtml,
          snippet: a.snippet,
          imageUrl: a.imageUrl ?? old.imageUrl,
          author: a.author,
          wordCount: words,
          enclosureUrl: a.enclosureUrl,
          sortTime: a.published ?? fallback,
        })
      } else {
        const read = remoteRead.has(id)
        rows.push({
          id,
          feedUrl: feed.url,
          guid: a.guid,
          title: a.title,
          link: a.link,
          contentHtml: a.contentHtml,
          snippet: a.snippet,
          imageUrl: a.imageUrl,
          author: a.author,
          sortTime: a.published ?? fallback,
          firstSeen: now,
          wordCount: words,
          read: read ? 1 : 0,
          readTime: read ? now : null,
          readSynced: read ? 1 : 0,
          saved: 0,
          enclosureUrl: a.enclosureUrl,
        })
        added++
      }
    })
    await db.items.bulkPut(rows)

    // Keep the newest N unsaved articles per feed
    const all = await db.items.where('feedUrl').equals(feed.url).toArray()
    if (all.length > max) {
      const drop = all
        .filter((i) => !i.saved)
        .sort((a, b) => b.sortTime - a.sortTime)
        .slice(max)
        .map((i) => i.id)
      await db.items.bulkDelete(drop)
    }

    await db.feeds.update(feed.url, {
      // Title only filled in when empty, so a refresh doesn't count as an edit to sync
      ...(feed.title ? {} : { title: parsed.title }),
      siteUrl: parsed.siteUrl,
      lastFetched: now,
      lastError: null,
    })
  })
  return added
}

export interface RefreshProgress {
  done: number
  total: number
}

/** Refreshes all feeds (or the given ones), a few at a time. Returns the number of new articles. */
export async function refreshFeeds(urls?: string[], onProgress?: (p: RefreshProgress) => void): Promise<number> {
  const feeds = urls ? (await db.feeds.bulkGet(urls)).filter((f): f is Feed => !!f) : await db.feeds.toArray()
  let done = 0
  let added = 0
  const queue = [...feeds]
  const worker = async () => {
    for (let feed = queue.shift(); feed; feed = queue.shift()) {
      try {
        added += await fetchOne(feed)
      } catch (e) {
        await db.feeds.update(feed.url, { lastError: e instanceof Error ? e.message : String(e), lastFetched: Date.now() })
      }
      done++
      onProgress?.({ done, total: feeds.length })
    }
  }
  await Promise.all(Array.from({ length: Math.min(6, queue.length) }, worker))
  return added
}

/**
 * Adds a feed from a feed URL or a website URL (looks for an advertised feed).
 * Returns the URL actually subscribed.
 */
export async function addFeed(input: string, tag = ''): Promise<string> {
  let url = input.trim()
  if (!/^https?:\/\//i.test(url)) url = `https://${url}`
  const { text, finalUrl, contentType } = await proxyFetch(url)
  let feedUrl = url
  let title = ''
  try {
    title = parseFeed(text, url).title
  } catch {
    if (!/html/i.test(contentType) && !/<html/i.test(text)) throw new Error('That address is not a feed')
    const links = findFeedLinks(text, finalUrl)
    if (!links.length) throw new Error('No feed found on that page')
    feedUrl = links[0].url
    title = parseFeed((await proxyFetch(feedUrl)).text, feedUrl).title
  }
  const existing = await db.feeds.get(feedUrl)
  if (!existing) await db.feeds.put(newFeed(feedUrl, { title, tag }))
  await refreshFeeds([feedUrl])
  return feedUrl
}

export async function deleteFeed(url: string) {
  await db.transaction('rw', db.feeds, db.items, async () => {
    await db.feeds.delete(url)
    await db.items.where('feedUrl').equals(url).delete()
  })
}

/** OPML import: adds every feed outline, using its category as tag. Returns how many were new. */
export async function importOpml(xml: string): Promise<number> {
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  let count = 0
  const outlines = Array.from(doc.querySelectorAll('outline[xmlUrl], outline[xmlurl]'))
  for (const o of outlines) {
    const url = o.getAttribute('xmlUrl') ?? o.getAttribute('xmlurl')
    if (!url) continue
    const parent = o.parentElement
    const tag = parent && parent.tagName.toLowerCase() === 'outline' ? (parent.getAttribute('title') ?? parent.getAttribute('text') ?? '') : ''
    if (await db.feeds.get(url)) continue
    await db.feeds.put(
      newFeed(url, {
        title: o.getAttribute('title') ?? o.getAttribute('text') ?? '',
        tag,
        customTitle: '',
      }),
    )
    count++
  }
  return count
}

export async function exportOpml(): Promise<string> {
  const feeds = await db.feeds.toArray()
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
  const byTag = new Map<string, Feed[]>()
  for (const f of feeds) byTag.set(f.tag, [...(byTag.get(f.tag) ?? []), f])
  const line = (f: Feed) =>
    `<outline type="rss" title="${esc(f.customTitle || f.title)}" text="${esc(f.customTitle || f.title)}" xmlUrl="${esc(f.url)}"/>`
  let body = ''
  for (const [tag, list] of byTag) {
    if (!tag) body += list.map((f) => `    ${line(f)}\n`).join('')
    else body += `    <outline title="${esc(tag)}" text="${esc(tag)}">\n${list.map((f) => `      ${line(f)}\n`).join('')}    </outline>\n`
  }
  return `<?xml version="1.0" encoding="UTF-8"?>\n<opml version="1.1">\n  <head><title>Feeder</title></head>\n  <body>\n${body}  </body>\n</opml>\n`
}
