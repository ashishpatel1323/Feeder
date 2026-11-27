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
import { noteHighlightChange } from './recent'
import { parseSketch, placeStrip, sketchStrip } from './sketch'

/**
 * Wraps each highlight's text in <mark class="hl hl-color" data-hl="id"> elements. Only adds
 * elements, so read aloud and tap-to-read see the same text and offsets.
 */
export function applyHighlights(html: string, all: Highlight[]): string {
  if (!all.length) return html
  const template = document.createElement('template')
  template.innerHTML = html
  const root = template.content
  const full = textOf(root)
  // Sketches go after their paragraph, once the marks are in (wrapping replaces text nodes)
  // Handwriting is drawn on the live page (lib/pageink.ts), not marked in the text
  const highlights = all.filter((h) => !h.sketch && !h.ink)
  const sketches = all.filter((h) => h.sketch)
  const spans = highlights
    .map((h) => ({ h, at: locateRange(full, h) }))
    .filter((s): s is { h: Highlight; at: [number, number] } => s.at !== null)
    .map((s) => ({ h: s.h, start: s.at[0], end: s.at[1] }))
    .sort((a, b) => a.start - b.start)

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
  addSketchStrips(root, full, sketches)
  return template.innerHTML
}

/**
 * Adds each sketch's strip after the paragraph its text ends in. Sketches whose text isn't in the
 * article any more go at the end, so they're never lost. Strips hold no text, so offsets stay the same.
 */
export function addSketchStrips(root: DocumentFragment | Element, full: string, sketches: Highlight[]) {
  if (!sketches.length) return
  const doc = root.ownerDocument ?? document
  const placed = sketches
    .map((h) => ({ h, sk: parseSketch(h.sketch), at: locateRange(full, h) }))
    .filter((x) => x.sk)
    .sort((a, b) => (a.at?.[1] ?? Infinity) - (b.at?.[1] ?? Infinity))
  // Text node ends, to find the node each sketch's text ends in
  const ends: { node: Text; end: number }[] = []
  let total = 0
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    total += n.nodeValue?.length ?? 0
    ends.push({ node: n as Text, end: total })
  }
  for (const { h, sk, at } of placed) {
    const strip = sketchStrip(doc, h.id, h.color, sk!)
    const hit = at ? ends.find((e) => e.end >= at[1]) : undefined
    if (hit) placeStrip(root, hit.node, strip)
    else root.append(strip)
  }
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

/** Saves a highlight made here (History notes it, and its note if it has one). */
export async function saveHighlight(h: Highlight) {
  const before = await db.highlights.get(h.id)
  await db.highlights.put(h)
  await noteHighlightChange(before, h)
}

export async function updateHighlight(id: string, patch: Partial<Pick<Highlight, 'color' | 'note' | 'sketch' | 'ink'>>) {
  const before = await db.highlights.get(id)
  const n = await db.highlights.update(id, { ...patch, modifiedAt: Date.now() })
  if (before) await noteHighlightChange(before, { ...before, ...patch })
  return n
}

export async function deleteHighlight(id: string) {
  const before = await db.highlights.get(id)
  await db.highlights.delete(id)
  if (before) await noteHighlightChange(before, null)
}

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
