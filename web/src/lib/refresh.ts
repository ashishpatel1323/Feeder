/** Fetching feeds and storing their articles. */
import { proxyFetch } from './api'
import { db, getKv, itemId, newFeed, setKv, type Feed, type Item } from './db'
import { countWords, findFeedLinks, htmlToText, parseFeed, type ParsedArticle, type ParsedFeed } from './feedparse'
import { makeFilter } from './filterrules'
import { getSyncState, knownStateFor } from './sync2'

export const MAX_ITEMS_PER_FEED_KEY = 'maxItemsPerFeed'

/**
 * The [max] newest articles, in feed order. Only that many are kept per feed anyway, so a podcast
 * serving its whole archive (hundreds of episodes) shouldn't have every one stored and then pruned.
 * Uses publish dates when every article has one, else the feed's own newest-first order.
 */
export function newestArticles(articles: ParsedArticle[], max: number): ParsedArticle[] {
  if (articles.length <= max) return articles
  if (articles.some((a) => a.published === null)) return articles.slice(0, max)
  const keep = new Set(
    articles
      .map((a, i) => ({ t: a.published as number, i }))
      .sort((x, y) => y.t - x.t)
      .slice(0, max)
      .map((x) => x.i),
  )
  return articles.filter((_, i) => keep.has(i))
}

/** Downloads (unless [prefetched], the feed just downloaded for adding it) and stores a feed's articles. */
async function fetchOne(feed: Feed, prefetched?: ParsedFeed): Promise<number> {
  const parsed = prefetched ?? parseFeed((await proxyFetch(feed.url)).text, feed.url, feed.alternateId)
  const keep = makeFilter(feed.blockRules, feed.allowRules)
  const now = Date.now()
  const max = await getKv<number>(MAX_ITEMS_PER_FEED_KEY, 200)
  const articles = newestArticles(parsed.articles.filter(keep), max)
  let added = 0
  const syncing = (await getSyncState()) !== null

  await db.transaction('rw', [db.items, db.feeds, db.remoteReadMarks, db.syncKnown, db.hidden], async () => {
    // The feed may have been deleted while it was downloading
    if (!(await db.feeds.get(feed.url))) return
    const ids = articles.map((a) => itemId(feed.url, a.guid))
    const existing = new Map((await db.items.bulkGet(ids)).filter(Boolean).map((i) => [i!.id, i!]))
    const remoteRead = new Set(
      (await db.remoteReadMarks.bulkGet(ids)).filter(Boolean).map((r) => r!.key),
    )
    // Deleted for good, here or on another device
    const hidden = new Set((await db.hidden.bulkGet(ids)).filter(Boolean).map((h) => h!.id))
    // Read/saved from other devices before this article was fetched here
    const known = await knownStateFor(ids.filter((id) => !existing.has(id)))
    const rows: Item[] = []
    // Feeds list newest first; give undated items a slightly older time the further down they are
    articles.forEach((a, index) => {
      const id = itemId(feed.url, a.guid)
      if (hidden.has(id)) return
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
          duration: a.duration ?? old.duration ?? null,
          sortTime: a.published ?? fallback,
        })
      } else {
        const k = known.get(id)
        const read = remoteRead.has(id) || !!k?.read
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
          readTime: read ? (k?.readTs ?? now) : null,
          readSynced: 1,
          saved: k?.saved ? 1 : 0,
          enclosureUrl: a.enclosureUrl,
          duration: a.duration,
        })
        added++
      }
    })
    await db.items.bulkPut(rows)

    // Keep the newest N unsaved articles per feed
    const all = await db.items.where('feedUrl').equals(feed.url).toArray()
    if (all.length > max) {
      const drop = all
        // Keep saved articles, and read marks the sync chain hasn't received yet
        // and articles downloaded for offline reading (offline.ts cleans those up after reading)
        .filter((i) => !i.saved && !i.offlineAt && !i.offlineVideo && !(syncing && i.read && !i.readSynced))
        .sort((a, b) => b.sortTime - a.sortTime)
        .slice(max)
        .map((i) => i.id)
      await db.items.bulkDelete(drop)
    }

    await db.feeds.update(feed.url, {
      // Title only filled in when empty, so a refresh doesn't count as an edit to sync
      ...(feed.title ? {} : { title: parsed.title }),
      siteUrl: parsed.siteUrl,
      // The feed's own artwork (podcast cover, channel logo) for Home's tiles, when it has none yet
      ...(feed.imageUrl || !parsed.iconUrl ? {} : { imageUrl: absoluteIcon(feed.url, parsed.iconUrl) }),
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
  // In the background: a refresh shouldn't wait on channel pages
  void fillYouTubeIcons().catch(() => undefined)
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
  let parsed: ParsedFeed
  try {
    parsed = parseFeed(text, url)
  } catch {
    if (!/html/i.test(contentType) && !/<html/i.test(text)) throw new Error('That address is not a feed')
    const links = findFeedLinks(text, finalUrl)
    if (!links.length) throw new Error('No feed found on that page')
    feedUrl = links[0].url
    parsed = parseFeed((await proxyFetch(feedUrl)).text, feedUrl)
  }
  const existing = await db.feeds.get(feedUrl)
  if (existing) {
    await refreshFeeds([feedUrl])
  } else {
    const feed = newFeed(feedUrl, { title: parsed.title, tag })
    await db.feeds.put(feed)
    // Store what was just downloaded and parsed instead of fetching a big feed a second time
    try {
      await fetchOne(feed, parsed)
    } catch (e) {
      await db.feeds.update(feedUrl, { lastError: e instanceof Error ? e.message : String(e), lastFetched: Date.now() })
    }
  }
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

const ICON_TRIED_KEY = 'iconTried'

/** The channel id of a YouTube channel feed (feeds/videos.xml?channel_id=…), else null. */
export function youtubeChannelId(feedUrl: string): string | null {
  try {
    const u = new URL(feedUrl)
    if (!/(^|\.)youtube\.com$/.test(u.hostname)) return null
    return u.searchParams.get('channel_id')
  } catch {
    return null
  }
}

/** The og:image (channel avatar) on a YouTube channel page. */
export function ogImage(html: string): string | null {
  const m = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i) ?? html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i)
  return m ? m[1].replace(/&amp;/g, '&') : null
}

/**
 * YouTube channel feeds carry no artwork, so Home's tile takes the avatar from the channel page.
 * Tried at most once a day per channel that still has no picture.
 */
export async function fillYouTubeIcons(): Promise<void> {
  const tried = await getKv<Record<string, number>>(ICON_TRIED_KEY, {})
  const now = Date.now()
  const todo = (await db.feeds.toArray()).filter((f) => !f.imageUrl && youtubeChannelId(f.url) && now - (tried[f.url] ?? 0) > 86_400_000)
  if (!todo.length) return
  for (const f of todo) {
    tried[f.url] = now
    try {
      const icon = ogImage((await proxyFetch(`https://www.youtube.com/channel/${youtubeChannelId(f.url)}`)).text)
      if (icon) await db.feeds.update(f.url, { imageUrl: icon })
    } catch {
      // No picture this time; the generated tile stays
    }
  }
  await setKv(ICON_TRIED_KEY, tried)
}

function absoluteIcon(feedUrl: string, icon: string): string | null {
  try {
    return new URL(icon, feedUrl).href
  } catch {
    return null
  }
}
