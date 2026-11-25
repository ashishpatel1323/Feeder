/**
 * Highlights and read aloud marks on a ZIM page. Shared by the web app's ZIM reader (the page is
 * in a same-origin iframe) and the Android app (built into a script it injects into its WebView,
 * see src/zim-android), so both find highlights at the same places.
 *
 * Nothing is added to the page's tree: marks are CSS Custom Highlights, so the page's own
 * scripts and styles see the page as it was, and text offsets stay the same.
 *
 * A highlight is anchored like an article highlight: its text, where it starts in the text of
 * the page's body (every text node, see textOf) and a few characters before it.
 */
import { anchorOf, locateRange, textOf } from '../textanchor'

export const ZIM_ITEM_PREFIX = 'zim:\n'

/** A ZIM page's id as a highlight's item: "zim:\n<book id>\n<path>" (the book id is the file name without .zim). */
export const zimItemId = (book: string, path: string) => `${ZIM_ITEM_PREFIX}${book}\n${path}`

export function parseZimItemId(itemId: string): { book: string; path: string } | null {
  if (!itemId.startsWith(ZIM_ITEM_PREFIX)) return null
  const rest = itemId.slice(ZIM_ITEM_PREFIX.length)
  const nl = rest.indexOf('\n')
  return nl < 0 ? { book: rest, path: '' } : { book: rest.slice(0, nl), path: rest.slice(nl + 1) }
}

export interface PageMark {
  id: string
  text: string
  start: number
  prefix: string
  color: string
}

type HighlightWindow = Window & { CSS?: typeof CSS; Highlight?: typeof Highlight }

const COLORS = ['yellow', 'green', 'blue', 'pink']

/** Adds the look of highlights and of the spoken sentence and word to the page, once. */
export function injectMarkStyles(doc: Document) {
  if (doc.getElementById('feeder-zim-marks')) return
  const style = doc.createElement('style')
  style.id = 'feeder-zim-marks'
  style.textContent = `
    ::highlight(feeder-hl-yellow) { background-color: rgba(255, 212, 59, 0.5); }
    ::highlight(feeder-hl-green) { background-color: rgba(92, 197, 131, 0.45); }
    ::highlight(feeder-hl-blue) { background-color: rgba(90, 165, 240, 0.45); }
    ::highlight(feeder-hl-pink) { background-color: rgba(224, 123, 180, 0.45); }
    ::highlight(ra-sentence) { background-color: rgba(31, 111, 74, 0.18); }
    ::highlight(ra-word) { background-color: rgba(31, 111, 74, 0.45); }
  `
  ;(doc.head ?? doc.documentElement).append(style)
}

/** The range of [start, end) in `root`'s text (counted as textOf counts it). */
export function rangeAt(root: Node, start: number, end: number): Range | null {
  const doc = root.ownerDocument ?? (root as Document)
  const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let total = 0
  let range: Range | null = null
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const len = n.nodeValue?.length ?? 0
    if (!range && start < total + len) {
      range = doc.createRange()
      range.setStart(n, start - total)
    }
    if (range && end <= total + len) {
      range.setEnd(n, end - total)
      return range
    }
    total += len
  }
  return null
}

/** Paints the highlights on the page; returns where each one is (for taps). */
export function paintHighlights(doc: Document, marks: PageMark[]): Map<string, Range> {
  const w = doc.defaultView as HighlightWindow | null
  const out = new Map<string, Range>()
  const root = doc.body
  if (!root) return out
  const full = textOf(root)
  const byColor = new Map<string, Range[]>()
  for (const m of marks) {
    const at = locateRange(full, m)
    if (!at) continue
    const r = rangeAt(root, at[0], at[1])
    if (!r) continue
    out.set(m.id, r)
    const color = COLORS.includes(m.color) ? m.color : 'yellow'
    byColor.set(color, [...(byColor.get(color) ?? []), r])
  }
  const registry = w?.CSS && 'highlights' in w.CSS ? w.CSS.highlights : null
  if (registry && w?.Highlight) {
    for (const c of COLORS) {
      const ranges = byColor.get(c)
      if (ranges?.length) registry.set(`feeder-hl-${c}`, new w.Highlight(...ranges))
      else registry.delete(`feeder-hl-${c}`)
    }
  }
  return out
}

/** The text position under a point of the page's viewport. */
export function caretAt(doc: Document, x: number, y: number): { node: Node; offset: number } | null {
  const d = doc as Document & { caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null }
  const pos = d.caretPositionFromPoint?.(x, y)
  if (pos) return { node: pos.offsetNode, offset: pos.offset }
  const r = doc.caretRangeFromPoint?.(x, y)
  return r ? { node: r.startContainer, offset: r.startOffset } : null
}

/** The highlight under a tap, if any. */
export function highlightAt(doc: Document, x: number, y: number, painted: Map<string, Range>): string | null {
  const at = caretAt(doc, x, y)
  if (!at) return null
  for (const [id, r] of painted) {
    try {
      if (r.isPointInRange(at.node, at.offset)) {
        // The caret snaps to the nearest letter: make sure the tap is really on the text
        for (const box of r.getClientRects()) if (x >= box.left - 2 && x <= box.right + 2 && y >= box.top - 2 && y <= box.bottom + 2) return id
      }
    } catch {
      // a point in another document
    }
  }
  return null
}

/** The page's selection as a highlight anchor, or null when nothing is selected. */
export function selectionAnchor(doc: Document): { start: number; text: string; prefix: string } | null {
  const sel = doc.getSelection()
  if (!sel || sel.isCollapsed || !sel.rangeCount || !doc.body) return null
  return anchorOf(doc.body, sel.getRangeAt(0))
}

/** The first text near the top of the page's viewport, to start reading aloud from. */
export function firstVisibleText(doc: Document): { node: Node; offset: number } | null {
  const w = doc.defaultView
  if (!w || !doc.body) return null
  if (w.scrollY < 8) return null
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (n.nodeValue?.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
  })
  const r = doc.createRange()
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    r.selectNodeContents(n)
    const box = r.getBoundingClientRect()
    if (box.height > 0 && box.bottom > 4) return { node: n, offset: 0 }
  }
  return null
}

/**
 * Keeps the spoken text in view: when it leaves the comfortable reading band, scrolls it back
 * to about a third of the way down (like the article reader).
 */
export function keepInView(doc: Document, r: Range) {
  const w = doc.defaultView
  if (!w) return
  const rect = r.getClientRects()[0] ?? r.getBoundingClientRect()
  if (!rect || (rect.width === 0 && rect.height === 0)) return
  const h = w.innerHeight
  if (rect.top > h * 0.12 && rect.bottom < h * 0.72) return
  w.scrollTo({ top: Math.max(0, w.scrollY + rect.top - h * 0.33), behavior: 'smooth' })
}

/** Scrolls a highlight to about a third of the way down. */
export function scrollToRange(doc: Document, r: Range) {
  const w = doc.defaultView
  const rect = r.getClientRects()[0] ?? r.getBoundingClientRect()
  if (!w || !rect) return
  w.scrollTo({ top: Math.max(0, w.scrollY + rect.top - w.innerHeight * 0.33) })
}

/**
 * What read aloud reads: the page's main content when it marks one (Wikipedia's article, a docs
 * site's <main>), so menus and headers aren't read out; else the whole page.
 */
export function readableRoot(doc: Document): HTMLElement | null {
  const body = doc.body
  if (!body) return null
  const total = (body.textContent ?? '').trim().length
  for (const sel of ['main', '[role="main"]', 'article', '#content', '#mw-content-text']) {
    const el = doc.querySelector<HTMLElement>(sel)
    if (el && (el.textContent ?? '').trim().length > Math.min(200, total * 0.3)) return el
  }
  return body
}
