/**
 * RSS 2.0 / RSS 1.0 / Atom / JSON Feed parser.
 *
 * Article ids must match the Android app exactly, or read marks from the phone won't land on
 * the right article. Feeder uses gofeed's GUID (RSS <guid>, Atom <id>, JSON Feed id), falling
 * back to the link, resolved against the feed URL (GoFeedExtensions.kt `guid`). When ids in a
 * feed aren't unique, or the feed has "alternate id" on, it uses "id|hash(text)|hash(title)".
 */

export interface ParsedArticle {
  guid: string
  title: string
  link: string | null
  contentHtml: string
  snippet: string
  imageUrl: string | null
  author: string | null
  published: number | null
  tags: string[]
  enclosureUrl: string | null
}

export interface ParsedFeed {
  title: string
  siteUrl: string | null
  iconUrl: string | null
  articles: ParsedArticle[]
}

/** Java's String.hashCode, used by Feeder's alternate ids. */
export function javaStringHash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h
}

const KNOWN_JAVA_PROTOCOLS = new Set(['http:', 'https:', 'ftp:', 'file:', 'jar:'])

/**
 * Port of Feeder's relativeLinkIntoAbsolute: an absolute link (or one with a scheme Java's URL
 * doesn't know, like tag: or urn:) is kept exactly as written; anything else is resolved
 * against the feed URL.
 */
export function relativeLinkIntoAbsolute(base: string, link: string): string {
  const scheme = link.match(/^([a-zA-Z][a-zA-Z0-9+.-]*):/)
  if (scheme) {
    const proto = scheme[1].toLowerCase() + ':'
    if (!KNOWN_JAVA_PROTOCOLS.has(proto)) return link
    try {
      new URL(link)
      return link
    } catch {
      return link
    }
  }
  try {
    return new URL(link, base).toString()
  } catch {
    return link
  }
}

export function htmlToText(html: string): string {
  if (!html) return ''
  if (!/[<&]/.test(html)) return html.replace(/\s+/g, ' ').trim()
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html')
  doc.querySelectorAll('script,style,noscript').forEach((n) => n.remove())
  return (doc.body.textContent ?? '').replace(/\s+/g, ' ').trim()
}

function firstImageInHtml(html: string, base: string): string | null {
  const m = html.match(/<img[^>]+src=["']([^"']+)["']/i)
  if (!m) return null
  return relativeLinkIntoAbsolute(base, m[1].replace(/&amp;/g, '&'))
}

function parseDate(text: string | null | undefined): number | null {
  if (!text) return null
  const t = Date.parse(text.trim())
  if (!Number.isNaN(t)) return t
  // RFC 822 dates with odd zones, e.g. "Tue, 10 Jun 2003 04:00:00 EST" are handled by Date.parse;
  // try stripping a trailing zone name otherwise
  const stripped = text.trim().replace(/\s+[A-Z]{2,5}$/, ' GMT')
  const t2 = Date.parse(stripped)
  return Number.isNaN(t2) ? null : t2
}

function children(el: Element, localName: string): Element[] {
  const out: Element[] = []
  for (const c of Array.from(el.children)) if (c.localName === localName) out.push(c)
  return out
}

function child(el: Element, localName: string): Element | null {
  for (const c of Array.from(el.children)) if (c.localName === localName) return c
  return null
}

function childText(el: Element, localName: string): string | null {
  const c = child(el, localName)
  const t = c?.textContent?.trim()
  return t ? t : null
}

function mediaImage(el: Element): string | null {
  for (const c of Array.from(el.children)) {
    if (c.localName === 'thumbnail' && c.getAttribute('url')) return c.getAttribute('url')
    if (c.localName === 'content' && c.getAttribute('url')) {
      const medium = c.getAttribute('medium') ?? ''
      const type = c.getAttribute('type') ?? ''
      if (medium === 'image' || type.startsWith('image/')) return c.getAttribute('url')
      const thumb = child(c, 'thumbnail')
      if (thumb?.getAttribute('url')) return thumb.getAttribute('url')
    }
    if (c.localName === 'group') {
      const inner = mediaImage(c)
      if (inner) return inner
    }
  }
  return null
}

interface RawItem {
  rawGuid: string | null
  rawLink: string | null
  title: string
  contentHtml: string
  summaryHtml: string
  imageUrl: string | null
  author: string | null
  published: number | null
  tags: string[]
  enclosureUrl: string | null
  enclosureType: string | null
}

function finish(feedUrl: string, raw: RawItem[], alternateId: boolean): ParsedArticle[] {
  const prepared = raw.map((r) => {
    const content = r.contentHtml || r.summaryHtml
    let plain = htmlToText(content)
    if (!plain && /img/.test(content)) plain = '<image>'
    const id = r.rawGuid ?? r.rawLink
    const guid = id ? relativeLinkIntoAbsolute(feedUrl, id) : null
    let link = r.rawLink ? relativeLinkIntoAbsolute(feedUrl, r.rawLink) : null
    if (!link && r.rawGuid && /^https?:\/\//i.test(r.rawGuid)) link = r.rawGuid
    const title = htmlToText(r.title)
    let image = r.imageUrl ? relativeLinkIntoAbsolute(feedUrl, r.imageUrl) : null
    if (!image && r.enclosureUrl && (r.enclosureType ?? '').startsWith('image/')) image = r.enclosureUrl
    if (!image) image = firstImageInHtml(content, link ?? feedUrl)
    return { r, guid, link, title, plain, content, image }
  })
  const ids = prepared.map((p) => p.guid)
  const notUnique = new Set(ids).size !== ids.length
  return prepared
    .map((p) => {
      const alt = `${p.guid}|${javaStringHash(p.plain)}|${javaStringHash(p.title)}`
      const guid = notUnique || alternateId ? alt : (p.guid ?? alt)
      return {
        guid,
        title: p.title,
        link: p.link,
        contentHtml: p.content,
        snippet: p.plain.slice(0, 200),
        imageUrl: p.image,
        author: p.r.author,
        published: p.r.published,
        tags: p.r.tags,
        enclosureUrl: p.r.enclosureUrl,
      }
    })
}

/** Atom text constructs: type="xhtml" holds real markup (inside a <div>), others hold text or escaped HTML. */
function atomText(el: Element | null): string {
  if (!el) return ''
  if ((el.getAttribute('type') ?? '').toLowerCase() !== 'xhtml') return el.textContent ?? ''
  const wrapper = child(el, 'div') ?? el
  const serializer = new XMLSerializer()
  return Array.from(wrapper.childNodes)
    .map((n) => serializer.serializeToString(n))
    .join('')
    .replace(/ xmlns="http:\/\/www\.w3\.org\/1999\/xhtml"/g, '')
}

function parseXml(text: string, feedUrl: string, alternateId: boolean): ParsedFeed {
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  const root = doc.documentElement
  if (!root || root.localName === 'parsererror' || doc.getElementsByTagName('parsererror').length) {
    throw new Error('Not a valid feed (XML parse error)')
  }

  if (root.localName === 'feed') {
    // Atom
    const linkOf = (el: Element): string | null => {
      const links = children(el, 'link')
      const alt = links.find((l) => (l.getAttribute('rel') ?? 'alternate') === 'alternate')
      return (alt ?? links[0])?.getAttribute('href') ?? null
    }
    const raw: RawItem[] = children(root, 'entry').map((e) => {
      const enclosure = children(e, 'link').find((l) => l.getAttribute('rel') === 'enclosure')
      return {
        rawGuid: childText(e, 'id'),
        rawLink: linkOf(e),
        title: child(e, 'title')?.textContent ?? '',
        contentHtml: atomText(child(e, 'content')),
        summaryHtml: atomText(child(e, 'summary')),
        imageUrl: mediaImage(e),
        author: (child(e, 'author') && childText(child(e, 'author')!, 'name')) || null,
        published: parseDate(childText(e, 'published') ?? childText(e, 'updated')),
        tags: children(e, 'category')
          .map((c) => c.getAttribute('term') ?? '')
          .filter(Boolean),
        enclosureUrl: enclosure?.getAttribute('href') ?? null,
        enclosureType: enclosure?.getAttribute('type') ?? null,
      }
    })
    return {
      title: htmlToText(child(root, 'title')?.textContent ?? ''),
      siteUrl: linkOf(root),
      iconUrl: childText(root, 'icon') ?? childText(root, 'logo'),
      articles: finish(feedUrl, raw, alternateId),
    }
  }

  // RSS 2.0 (<rss><channel><item>) or RSS 1.0 (<rdf:RDF><channel/><item/>)
  if (root.localName !== 'rss' && root.localName !== 'RDF') throw new Error('Not a feed')
  const channel = child(root, 'channel')
  if (!channel) throw new Error('Not a feed (no channel)')
  const itemEls = children(channel, 'item').length ? children(channel, 'item') : children(root, 'item')
  const raw: RawItem[] = itemEls.map((i) => {
    const enclosure = child(i, 'enclosure')
    return {
      rawGuid: childText(i, 'guid'),
      rawLink: childText(i, 'link') ?? i.getAttribute('rdf:about'),
      title: child(i, 'title')?.textContent ?? '',
      contentHtml: child(i, 'encoded')?.textContent ?? '',
      summaryHtml: child(i, 'description')?.textContent ?? '',
      imageUrl: mediaImage(i),
      author: childText(i, 'creator') ?? childText(i, 'author'),
      published: parseDate(childText(i, 'pubDate') ?? childText(i, 'date')),
      tags: children(i, 'category')
        .map((c) => c.textContent?.trim() ?? '')
        .filter(Boolean),
      enclosureUrl: enclosure?.getAttribute('url') ?? null,
      enclosureType: enclosure?.getAttribute('type') ?? null,
    }
  })
  const image = child(channel, 'image') ?? child(root, 'image')
  return {
    title: htmlToText(child(channel, 'title')?.textContent ?? ''),
    siteUrl: childText(channel, 'link'),
    iconUrl: image ? childText(image, 'url') : null,
    articles: finish(feedUrl, raw, alternateId),
  }
}

interface JsonFeedItem {
  id?: string | number
  url?: string
  external_url?: string
  title?: string
  content_html?: string
  content_text?: string
  summary?: string
  image?: string
  banner_image?: string
  date_published?: string
  date_modified?: string
  author?: { name?: string }
  authors?: { name?: string }[]
  tags?: string[]
  attachments?: { url?: string; mime_type?: string }[]
}

function parseJson(text: string, feedUrl: string, alternateId: boolean): ParsedFeed {
  const data = JSON.parse(text) as {
    title?: string
    home_page_url?: string
    icon?: string
    favicon?: string
    items?: JsonFeedItem[]
  }
  if (!Array.isArray(data.items)) throw new Error('Not a JSON Feed')
  const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;')
  const raw: RawItem[] = data.items.map((i) => ({
    rawGuid: i.id !== undefined && i.id !== null && String(i.id) !== '' ? String(i.id) : null,
    rawLink: i.url ?? i.external_url ?? null,
    title: i.title ?? '',
    contentHtml: i.content_html ?? (i.content_text ? `<p>${escape(i.content_text)}</p>` : ''),
    summaryHtml: i.summary ?? '',
    imageUrl: i.image ?? i.banner_image ?? null,
    author: i.authors?.[0]?.name ?? i.author?.name ?? null,
    published: parseDate(i.date_published ?? i.date_modified),
    tags: i.tags ?? [],
    enclosureUrl: i.attachments?.[0]?.url ?? null,
    enclosureType: i.attachments?.[0]?.mime_type ?? null,
  }))
  return {
    title: data.title ?? '',
    siteUrl: data.home_page_url ?? null,
    iconUrl: data.icon ?? data.favicon ?? null,
    articles: finish(feedUrl, raw, alternateId),
  }
}

export function parseFeed(text: string, feedUrl: string, alternateId = false): ParsedFeed {
  const trimmed = text.replace(/^﻿/, '').trimStart()
  if (trimmed.startsWith('{')) return parseJson(trimmed, feedUrl, alternateId)
  return parseXml(trimmed, feedUrl, alternateId)
}

/** Finds feed links advertised by an HTML page (for "add feed" with a site URL). */
export function findFeedLinks(html: string, pageUrl: string): { url: string; title: string }[] {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const out: { url: string; title: string }[] = []
  doc.querySelectorAll('link[rel~="alternate"]').forEach((l) => {
    const type = (l.getAttribute('type') ?? '').toLowerCase()
    const href = l.getAttribute('href')
    if (!href) return
    if (/rss|atom|feed\+json|json/.test(type)) {
      out.push({ url: new URL(href, pageUrl).toString(), title: l.getAttribute('title') ?? type })
    }
  })
  return out
}

export function countWords(text: string): number {
  const m = text.match(/\S+/g)
  return m ? m.length : 0
}
