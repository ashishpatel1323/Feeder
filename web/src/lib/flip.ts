/**
 * Flip mode helpers: the article text a page shows, and turning wheel / trackpad scrolling into
 * whole page turns. The desktop counterpart of the Android fork's Flip mode.
 */

const BLOCKS = 'p, li, h2, h3, h4, h5, h6, blockquote, pre, dd'

/** The article's paragraphs as plain text, in order, without captions, scripts or tables. */
export function paragraphsOf(html: string): string[] {
  if (!html) return []
  if (!/[<&]/.test(html)) return splitPlain(html)
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html')
  doc.querySelectorAll('script, style, noscript, figcaption, table, svg, button').forEach((n) => n.remove())
  const out: string[] = []
  doc.body.querySelectorAll(BLOCKS).forEach((el) => {
    // Nested blocks (a <p> inside a <li> or <blockquote>) are taken once, at the innermost level
    if (el.querySelector(BLOCKS)) return
    const t = (el.textContent ?? '').replace(/\s+/g, ' ').trim()
    if (t) out.push(t)
  })
  if (out.length) return out
  return splitPlain(doc.body.textContent ?? '')
}

function splitPlain(text: string): string[] {
  return text
    .split(/\n\s*\n|\r\n\s*\r\n/)
    .map((t) => t.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
}

/** The first [words] words of [paragraphs], keeping paragraph breaks; the last one ends in "…" when cut. */
export function takeWords(paragraphs: string[], words: number): string[] {
  const out: string[] = []
  let left = words
  for (const p of paragraphs) {
    if (left <= 0) break
    const w = p.split(' ')
    if (w.length <= left) {
      out.push(p)
      left -= w.length
    } else {
      out.push(`${w.slice(0, left).join(' ').replace(/[,;:\s]+$/, '')}…`)
      left = 0
    }
  }
  return out
}

export const countWords = (paragraphs: string[]) => paragraphs.reduce((n, p) => n + p.split(' ').length, 0)

/**
 * Turns a stream of wheel events into page turns: one turn per gesture, so a trackpad swipe or a
 * few mouse wheel notches flip exactly one page, and the swipe's inertia doesn't flip more.
 */
export class WheelFlipper {
  private acc = 0
  private lockedUntil = 0
  constructor(
    private threshold = 60,
    private lockMs = 650,
    private quietMs = 220,
  ) {}

  /** Returns +1 (next page), -1 (previous) or 0 for one wheel event. */
  push(deltaX: number, deltaY: number, now: number): -1 | 0 | 1 {
    const d = Math.abs(deltaX) > Math.abs(deltaY) ? deltaX : deltaY
    if (now < this.lockedUntil) {
      // Still the same gesture (or its inertia): keep waiting until the wheel goes quiet
      this.lockedUntil = Math.max(this.lockedUntil, now + this.quietMs)
      return 0
    }
    this.acc += d
    if (Math.abs(this.acc) < this.threshold) return 0
    const dir = this.acc > 0 ? 1 : -1
    this.acc = 0
    this.lockedUntil = now + this.lockMs
    return dir
  }
}
