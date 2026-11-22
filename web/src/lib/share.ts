/**
 * Shared links: any web page or YouTube video added by pasting a link (or shared from the
 * Android app). They become saved articles of a built-in source that is not a real feed, so
 * the reader, read aloud, highlights and the YouTube player all work on them, and they sync
 * to other devices as ordinary saved records (key = SHARED_FEED_URL \n page URL).
 */
import { Readability } from '@mozilla/readability'
import { proxyFetch } from './api'
import { db, hideItems, itemId, newFeed, type Feed, type Item } from './db'
import { sanitize } from './extras'
import { countWords, findFeedLinks, htmlToText, parseFeed } from './feedparse'
import { youtubeVideoId, youtubeWatchUrl } from './youtube'

/** The "feed" every shared link belongs to. Never fetched; .invalid never resolves. Same on Android. */
export const SHARED_FEED_URL = 'https://shared.feeder.invalid/'

/** Stands in for a feed row so titles, grouping and the reader treat shared links like a feed. */
export const SHARED_FEED: Feed = newFeed(SHARED_FEED_URL, { title: 'Shared links', fullTextByDefault: true, whenModified: 0 })

export const isShared = (i: Pick<Item, 'feedUrl'>) => i.feedUrl === SHARED_FEED_URL

/** The site a shared link came from, for the article list ("paulgraham.com"). */
export function siteOf(link: string | null): string {
  if (!link) return ''
  try {
    return new URL(link).hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}

/** The first http(s) link in pasted or shared text ("Look at this https://…"), or null. */
export function findLink(text: string): string | null {
  const m = text.match(/https?:\/\/[^\s<>"']+/i)
  if (m) return m[0].replace(/[),.;!?]+$/, '')
  // A bare address such as "example.com/post"
  const t = text.trim()
  if (/^[a-z0-9-]+(\.[a-z0-9-]+)+(\/\S*)?$/i.test(t)) return `https://${t}`
  return null
}

/** Removes tracking parameters, so the same page shared twice is one article. */
export function normalizeLink(link: string): string {
  const vid = youtubeVideoId(link)
  if (vid) return youtubeWatchUrl(vid)
  try {
    const u = new URL(link)
    u.hash = ''
    for (const k of [...u.searchParams.keys()]) {
      if (/^(utm_|fbclid$|gclid$|mc_cid$|mc_eid$|ref$|ref_src$|igshid$|si$)/i.test(k)) u.searchParams.delete(k)
    }
    return u.toString()
  } catch {
    return link
  }
}

export type AddResult =
  | { kind: 'added'; item: Item; existed: boolean }
  /** The link is a feed, or a page that only points at one: offer to subscribe instead. */
  | { kind: 'feed'; url: string; title: string }

function metaContent(doc: Document, names: string[]): string | null {
  for (const n of names) {
    const el = doc.querySelector(`meta[property="${n}"], meta[name="${n}"]`)
    const v = el?.getAttribute('content')?.trim()
    if (v) return v
  }
  return null
}

function absolute(url: string | null, base: string): string | null {
  if (!url) return null
  try {
    return new URL(url, base).toString()
  } catch {
    return null
  }
}

async function videoArticle(link: string, id: string): Promise<Partial<Item>> {
  const base: Partial<Item> = { link, title: 'YouTube video', imageUrl: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`, contentHtml: '' }
  try {
    const { text } = await proxyFetch(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent(link)}`)
    const o = JSON.parse(text) as { title?: string; author_name?: string; thumbnail_url?: string }
    return { ...base, title: o.title || base.title, author: o.author_name ?? null, imageUrl: o.thumbnail_url || base.imageUrl }
  } catch {
    return base
  }
}

async function pageArticle(link: string): Promise<Partial<Item> | AddResult> {
  const { text, finalUrl, contentType } = await proxyFetch(link)
  // A feed itself: subscribing is what was meant
  if (!/html/i.test(contentType) && !/<html/i.test(text.slice(0, 2000))) {
    try {
      return { kind: 'feed', url: link, title: parseFeed(text, link).title }
    } catch {
      throw new Error('That link is not a web page')
    }
  }
  const doc = new DOMParser().parseFromString(text, 'text/html')
  const baseEl = doc.createElement('base')
  baseEl.href = finalUrl
  doc.head.prepend(baseEl)
  const metaTitle = metaContent(doc, ['og:title', 'twitter:title']) ?? doc.title.trim()
  const image = absolute(metaContent(doc, ['og:image', 'og:image:url', 'twitter:image']), finalUrl)
  const description = metaContent(doc, ['og:description', 'description', 'twitter:description']) ?? ''
  const siteName = metaContent(doc, ['og:site_name'])
  const published = Date.parse(metaContent(doc, ['article:published_time', 'og:published_time']) ?? '')
  // Readability changes the document, so read everything else first
  const article = new Readability(doc).parse()
  const html = article?.content ? sanitize(article.content, finalUrl) : ''
  const plain = html ? htmlToText(html) : description
  if (!html && !metaTitle) {
    // Maybe a home page with a feed but no article
    const feeds = findFeedLinks(text, finalUrl)
    if (feeds.length) return { kind: 'feed', url: feeds[0].url, title: siteName ?? siteOf(finalUrl) }
    throw new Error('Could not read that page')
  }
  return {
    link,
    title: article?.title?.trim() || metaTitle || siteOf(link),
    author: article?.byline?.trim() || siteName || null,
    imageUrl: image,
    contentHtml: html || (description ? `<p>${description.replace(/</g, '&lt;')}</p>` : ''),
    fullHtml: html || undefined,
    fullWordCount: html ? countWords(plain) : undefined,
    snippet: (article?.excerpt?.trim() || description || plain).slice(0, 300),
    wordCount: countWords(plain),
    sortTime: Number.isFinite(published) && published < Date.now() ? published : undefined,
  }
}

/**
 * Adds a link to Saved: fetches the page (or the video's title) and stores it as a saved
 * article. A link already in Feeder is saved again and moved to the top, not duplicated.
 */
export async function addSharedLink(input: string): Promise<AddResult> {
  const found = findLink(input)
  if (!found) throw new Error('That is not a link')
  const link = normalizeLink(found)
  const id = itemId(SHARED_FEED_URL, link)
  const existing = await db.items.get(id)
  const now = Date.now()
  if (existing) {
    await db.items.update(id, { saved: 1, read: 0, readTime: null, readSynced: 0, sortTime: now })
    await db.hidden.delete(id)
    return { kind: 'added', item: { ...existing, saved: 1, read: 0, readTime: null, readSynced: 0, sortTime: now }, existed: true }
  }
  const vid = youtubeVideoId(link)
  const got = vid ? await videoArticle(link, vid) : await pageArticle(link)
  if ('kind' in got) return got as AddResult
  const fields = got as Partial<Item>
  const item: Item = {
    id,
    feedUrl: SHARED_FEED_URL,
    guid: link,
    title: fields.title ?? link,
    link,
    contentHtml: fields.contentHtml ?? '',
    snippet: fields.snippet ?? '',
    imageUrl: fields.imageUrl ?? null,
    author: fields.author ?? null,
    // Newest added first: the time it was added, not when it was written
    sortTime: now,
    firstSeen: now,
    wordCount: fields.wordCount ?? 0,
    read: 0,
    readTime: null,
    readSynced: 0,
    saved: 1,
    ...(fields.fullHtml ? { fullHtml: fields.fullHtml, fullWordCount: fields.fullWordCount } : {}),
  }
  await db.transaction('rw', db.items, db.hidden, async () => {
    await db.hidden.delete(id)
    await db.items.put(item)
  })
  return { kind: 'added', item, existed: false }
}

/** Undo for a link just added: deletes it for good, here and (if it already synced) on other devices. */
export async function undoSharedLink(item: Item, existed: boolean): Promise<void> {
  if (existed) return
  await hideItems([item.id])
}
