import { db, feedDisplayTitle, type Feed, type Highlight, type HighlightColor, type Item } from './db'

export const HIGHLIGHT_COLORS: { id: HighlightColor; label: string; swatch: string }[] = [
  { id: 'yellow', label: 'Yellow', swatch: '#ffe066' },
  { id: 'green', label: 'Green', swatch: '#b7ebc8' },
  { id: 'blue', label: 'Blue', swatch: '#bcdcff' },
  { id: 'pink', label: 'Pink', swatch: '#f6c3e0' },
]

/** Stronger versions of the colors, for strokes on dark share images. */
export const HIGHLIGHT_INK: Record<HighlightColor, string> = {
  yellow: '#ffd43b',
  green: '#5cc583',
  blue: '#5aa5f0',
  pink: '#e07bb4',
}

export { anchorOf, locate, locateRange, textOf } from './textanchor'
import { locateRange, textOf } from './textanchor'

/**
 * Wraps each highlight's text in <mark class="hl hl-color" data-hl="id"> elements. Only adds
 * elements, so read aloud and tap-to-read see the same text and offsets.
 */
export function applyHighlights(html: string, highlights: Highlight[]): string {
  if (!highlights.length) return html
  const template = document.createElement('template')
  template.innerHTML = html
  const root = template.content
  const full = textOf(root)
  const spans = highlights
    .map((h) => ({ h, at: locateRange(full, h) }))
    .filter((s): s is { h: Highlight; at: [number, number] } => s.at !== null)
    .map((s) => ({ h: s.h, start: s.at[0], end: s.at[1] }))
    .sort((a, b) => a.start - b.start)
  if (!spans.length) return html

  // Collect text nodes with their offsets first: wrapping changes the tree
  const nodes: { node: Text; start: number }[] = []
  let total = 0
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    nodes.push({ node: n as Text, start: total })
    total += n.nodeValue?.length ?? 0
  }
  for (const { node, start } of nodes) {
    const end = start + (node.nodeValue?.length ?? 0)
    const parts = spans.filter((s) => s.start < end && s.end > start)
    if (!parts.length) continue
    // Text nodes inside <style>/<script> etc. can't take marks
    const parentTag = node.parentElement?.tagName
    if (parentTag && /^(STYLE|SCRIPT|TEXTAREA|OPTION)$/.test(parentTag)) continue
    const text = node.nodeValue ?? ''
    const frag = document.createDocumentFragment()
    let pos = 0
    for (const s of parts) {
      const a = Math.max(s.start - start, pos)
      const b = Math.min(s.end - start, text.length)
      if (b <= a) continue
      if (a > pos) frag.append(text.slice(pos, a))
      const mark = document.createElement('mark')
      mark.className = `hl hl-${s.h.color}${s.h.note ? ' has-note' : ''}`
      mark.dataset.hl = s.h.id
      mark.textContent = text.slice(a, b)
      frag.append(mark)
      pos = b
    }
    if (pos < text.length) frag.append(text.slice(pos))
    node.replaceWith(frag)
  }
  return template.innerHTML
}

/**
 * The article's picture for share images: the feed's image, else the first real picture in the
 * article text (many feeds, like TechCrunch, only put it there). Skips icons and tracking pixels.
 */
export function articlePicture(imageUrl: string | null, html: string): string | null {
  if (imageUrl) return imageUrl
  const template = document.createElement('template')
  template.innerHTML = html
  for (const img of template.content.querySelectorAll('img')) {
    const src = img.getAttribute('src') ?? ''
    if (!/^https?:/i.test(src)) continue
    const w = Number(img.getAttribute('width')) || 0
    const h = Number(img.getAttribute('height')) || 0
    if ((w && w < 120) || (h && h < 120)) continue
    if (/\b(pixel|tracking|spacer|emoji|avatar|gravatar)\b|\.gif(\?|$)/i.test(src)) continue
    return src
  }
  return null
}

export function newHighlight(item: Item, feed: Feed | undefined, anchor: { start: number; text: string; prefix: string }, color: HighlightColor, note: string, picture: string | null = item.imageUrl): Highlight {
  return {
    id: crypto.randomUUID(),
    itemId: item.id,
    itemTitle: item.title,
    itemLink: item.link,
    feedTitle: feedDisplayTitle(feed),
    imageUrl: picture,
    text: anchor.text,
    start: anchor.start,
    prefix: anchor.prefix,
    color,
    note: note.trim(),
    createdAt: Date.now(),
  }
}

export const saveHighlight = (h: Highlight) => db.highlights.put(h)
export const updateHighlight = (id: string, patch: Partial<Pick<Highlight, 'color' | 'note'>>) => db.highlights.update(id, { ...patch, modifiedAt: Date.now() })
export const deleteHighlight = (id: string) => db.highlights.delete(id)

/** What goes on the clipboard (and in a share's text): the quote, where it's from, and the link. */
export function quoteText(h: Pick<Highlight, 'text' | 'itemTitle' | 'feedTitle' | 'itemLink'>, note?: string): string {
  const from = [h.itemTitle, h.feedTitle].filter(Boolean).join(', ')
  return [`“${h.text}”`, note ? `\n${note}` : '', from ? `\n— ${from}` : '', h.itemLink ? `\n${h.itemLink}` : ''].join('')
}

/** All of an article's highlights as text, in reading order. */
export function allQuotesText(hs: Pick<Highlight, 'text' | 'note' | 'itemTitle' | 'feedTitle' | 'itemLink'>[], withNotes = true): string {
  if (!hs.length) return ''
  const first = hs[0]
  const quotes = hs.map((h) => `“${h.text}”${withNotes && h.note ? `\n  ${h.note}` : ''}`).join('\n\n')
  const from = [first.itemTitle, first.feedTitle].filter(Boolean).join(', ')
  return `${quotes}\n\n— ${from}${first.itemLink ? `\n${first.itemLink}` : ''}`
}
