/**
 * Where a highlight is in a text: offsets in the text of a DOM tree, and finding them again when
 * the text shifted. No app imports, so the Android ZIM page script can use it too.
 */
import type { Highlight } from './db'

const PREFIX_LEN = 32

/** The text of `root` the way highlights count offsets: every text node, in order. */
export function textOf(root: Node): string {
  let out = ''
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) out += n.nodeValue ?? ''
  return out
}

/** Offset of a (container, offset) point inside `root`'s text, or -1 when it's outside. */
function pointOffset(root: Node, container: Node, offset: number): number {
  if (!root.contains(container)) return -1
  // A point between child elements: move it to the start of the next text
  if (container.nodeType !== Node.TEXT_NODE) {
    const range = document.createRange()
    range.setStart(root, 0)
    range.setEnd(container, offset)
    return range.toString().length
  }
  let total = 0
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n === container) return total + offset
    total += n.nodeValue?.length ?? 0
  }
  return -1
}

/** Where a selection sits in the article body, trimmed of surrounding spaces. Null when it's empty or outside. */
export function anchorOf(root: Node, range: Range): { start: number; text: string; prefix: string } | null {
  let start = pointOffset(root, range.startContainer, range.startOffset)
  let end = pointOffset(root, range.endContainer, range.endOffset)
  if (start < 0 || end <= start) return null
  const full = textOf(root)
  while (start < end && /\s/.test(full[start])) start++
  while (end > start && /\s/.test(full[end - 1])) end--
  if (end <= start) return null
  return { start, text: full.slice(start, end), prefix: full.slice(Math.max(0, start - PREFIX_LEN), start) }
}

/**
 * Finds a highlight in the article text: at its saved offset when the text still matches,
 * otherwise the copy whose preceding text matches best (the feed text and the full text differ).
 */
export function locate(full: string, h: Pick<Highlight, 'text' | 'start' | 'prefix'>): number {
  if (!h.text) return -1
  if (full.startsWith(h.text, h.start)) return h.start
  let best = -1
  let bestScore = -1
  for (let i = full.indexOf(h.text); i >= 0; i = full.indexOf(h.text, i + 1)) {
    let score = 0
    while (score < h.prefix.length && full[i - 1 - score] === h.prefix[h.prefix.length - 1 - score]) score++
    if (score > bestScore) {
      best = i
      bestScore = score
    }
  }
  return best
}

/** Curly quotes, dashes and no-break spaces as plain ones: devices and feeds write them differently. */
const foldChar = (c: string) => (/[\u2018\u2019\u201a\u201b\u02bc\u2032]/.test(c) ? "'" : /[\u201c\u201d\u201e\u2033]/.test(c) ? '"' : /[\u2013\u2014\u2212]/.test(c) ? '-' : c)
const isGap = (c: string) => /[\s\u00a0\u200b]/.test(c)

/** The text without spaces and with plain quotes, and where each of its characters is in `text`. */
function folded(text: string): { s: string; at: number[] } {
  let s = ''
  const at: number[] = []
  for (let i = 0; i < text.length; i++) {
    if (isGap(text[i])) continue
    s += foldChar(text[i])
    at.push(i)
  }
  return { s, at }
}

/**
 * Where a highlight is, as [start, end) in `full`. Exact text first; then ignoring spaces,
 * line breaks and quote styles, so highlights made on the Android app (whose text joins
 * paragraphs and curls quotes differently) are found too.
 */
export function locateRange(full: string, h: Pick<Highlight, 'text' | 'start' | 'prefix'>): [number, number] | null {
  const exact = locate(full, h)
  if (exact >= 0) return [exact, exact + h.text.length]
  const f = folded(full)
  const needle = folded(h.text).s
  if (!needle) return null
  const before = folded(h.prefix).s
  let best = -1
  let bestScore = -1
  for (let i = f.s.indexOf(needle); i >= 0; i = f.s.indexOf(needle, i + 1)) {
    let score = 0
    while (score < before.length && f.s[i - 1 - score] === before[before.length - 1 - score]) score++
    if (score > bestScore) {
      best = i
      bestScore = score
    }
  }
  return best < 0 ? null : [f.at[best], f.at[best + needle.length - 1] + 1]
}

