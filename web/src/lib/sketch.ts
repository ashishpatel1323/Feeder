/**
 * Margin sketches: hand-drawn notes tied to a passage, shown as a strip right after the paragraph
 * the passage is in. A sketch is a highlight with a `sketch` field (synced with it), so devices
 * without sketches still show the passage as a plain highlight.
 *
 * No app imports: the Android ebook and ZIM page scripts use this file too.
 *
 * The `sketch` field is JSON: { h, s: [{ c, w, p: [x, y, x, y, …] }] }. Coordinates are in units of
 * 1/1000 of the drawing's width, so a sketch drawn on a phone fits a desktop margin and back;
 * `h` is the height in the same units and `w` the stroke width.
 */

export type SketchColor = 'yellow' | 'green' | 'blue' | 'pink'

export interface SketchStroke {
  c: SketchColor
  /** Stroke width, in 1/1000 of the width. */
  w: number
  /** x, y, x, y, … */
  p: number[]
}

export interface Sketch {
  h: number
  s: SketchStroke[]
}

export const SKETCH_W = 1000
const COLORS: SketchColor[] = ['yellow', 'green', 'blue', 'pink']

/** Ink colors on the paper strip: [light theme, dark theme]. The highlight swatches are too pale for strokes. */
export const SKETCH_INK: Record<SketchColor, [string, string]> = {
  yellow: ['#b26a00', '#ffc857'],
  green: ['#2e7d32', '#7ed492'],
  blue: ['#1a56c4', '#8ab4ff'],
  pink: ['#c2185b', '#f48fb1'],
}

export function parseSketch(raw: string | null | undefined): Sketch | null {
  if (!raw) return null
  try {
    const v = JSON.parse(raw) as Partial<Sketch>
    if (!v || !Array.isArray(v.s)) return null
    const s = v.s
      .filter((st): st is SketchStroke => !!st && Array.isArray(st.p) && st.p.length >= 2)
      .map((st) => ({
        c: COLORS.includes(st.c) ? st.c : 'blue',
        w: typeof st.w === 'number' && st.w > 0 ? st.w : 3,
        p: st.p.map((n) => (typeof n === 'number' && Number.isFinite(n) ? n : 0)),
      }))
    if (!s.length) return null
    return { h: typeof v.h === 'number' && v.h > 0 ? v.h : heightOf(s), s }
  } catch {
    return null
  }
}

/** Lowest point of the strokes, plus room for the stroke width. */
function heightOf(s: SketchStroke[]): number {
  let max = 0
  for (const st of s) for (let i = 1; i < st.p.length; i += 2) max = Math.max(max, st.p[i] + st.w)
  return max
}

/** The JSON for the `sketch` field, cropped to the strokes (with a little room) and rounded. */
export function encodeSketch(sk: Sketch): string {
  const pad = 24
  let minY = Infinity
  for (const st of sk.s) for (let i = 1; i < st.p.length; i += 2) minY = Math.min(minY, st.p[i] - st.w)
  const shift = Number.isFinite(minY) ? Math.max(0, minY - pad) : 0
  const s = sk.s.map((st) => ({ c: st.c, w: Math.round(st.w * 10) / 10, p: st.p.map((n, i) => Math.round(i % 2 ? n - shift : n)) }))
  return JSON.stringify({ h: Math.max(80, Math.round(heightOf(s) + pad)), s })
}

/** An SVG path through the points, smoothed with quadratic curves between midpoints. */
export function strokePath(p: number[]): string {
  if (p.length < 4) {
    const [x, y] = p
    return `M${x} ${y}l0.01 0`
  }
  let d = `M${p[0]} ${p[1]}`
  for (let i = 2; i < p.length - 2; i += 2) {
    const mx = (p[i] + p[i + 2]) / 2
    const my = (p[i + 1] + p[i + 3]) / 2
    d += `Q${p[i]} ${p[i + 1]} ${mx} ${my}`
  }
  d += `L${p[p.length - 2]} ${p[p.length - 1]}`
  return d
}

/** The sketch as SVG markup; strokes take their color from the --ink-* variables (see SKETCH_CSS). */
export function sketchSvg(sk: Sketch): string {
  const paths = sk.s.map((st) => `<path d="${strokePath(st.p)}" stroke="var(--feeder-ink-${st.c})" stroke-width="${st.w}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('')
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${SKETCH_W} ${sk.h}" width="100%" preserveAspectRatio="xMinYMin meet" aria-hidden="true">${paths}</svg>`
}

/** Look of the strips; for pages that don't load the app's styles (ZIM pages, ebook sections). */
export function sketchCss(dark: boolean): string {
  const ink = COLORS.map((c) => `--feeder-ink-${c}: ${SKETCH_INK[c][dark ? 1 : 0]};`).join(' ')
  return `
    .feeder-sketch { ${ink} display: block; margin: 0.6em 0 0.2em; padding: 6px 8px; border-radius: 12px;
      background: ${dark ? '#2a2b24' : '#fffdf6'}; border-left: 3px solid var(--feeder-sketch-edge, #c2185b);
      cursor: pointer; text-indent: 0; line-height: 0; max-width: 40em; box-sizing: border-box; }
    .feeder-sketch svg { display: block; width: 100%; height: auto; }
  `
}

/** Blocks a strip goes at the end of. A strip is added inside the block (as its last child) so the
 * positions of everything after it, like EPUB CFIs of later paragraphs, don't change. */
const BLOCK = /^(P|LI|BLOCKQUOTE|H[1-6]|PRE|DD|DT|TD|TH|FIGCAPTION|CAPTION|SECTION|ARTICLE|DIV|BODY)$/

/** The strip element for a sketch highlight: a span shown as a block (a div can't go inside a <p>). */
export function sketchStrip(doc: Document, id: string, color: SketchColor, sk: Sketch): HTMLElement {
  // XHTML pages (EPUB chapters) need the namespace, or the strip isn't an HTML element
  const el = doc.createElementNS('http://www.w3.org/1999/xhtml', 'span') as HTMLElement
  el.className = 'feeder-sketch'
  el.dataset.sketch = id
  el.setAttribute('role', 'button')
  el.setAttribute('aria-label', 'Sketch')
  el.setAttribute('contenteditable', 'false')
  el.style.setProperty('--feeder-sketch-edge', `var(--feeder-ink-${color})`)
  el.innerHTML = sketchSvg(sk)
  return el
}

/** Adds the strip at the end of the block that holds `node`, below `root`. */
export function placeStrip(root: Node, node: Node, strip: HTMLElement) {
  let el: Element | null = node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement
  while (el && el !== root && !BLOCK.test(el.tagName.toUpperCase())) el = el.parentElement
  // Inline-only content straight in the root: add it after the node's top-level ancestor
  if (!el || el === root) {
    let top: Node = node
    while (top.parentNode && top.parentNode !== root) top = top.parentNode
    if (top.parentNode === root) {
      top.parentNode.insertBefore(strip, top.nextSibling)
      return
    }
    ;(root as ParentNode).append(strip)
    return
  }
  el.append(strip)
}

/** Removes the strips added to a page before. */
export function removeStrips(root: ParentNode) {
  root.querySelectorAll('.feeder-sketch').forEach((s) => s.remove())
}

/** Whether a page shows light text on a dark background (to pick the strip's paper and inks). */
export function isDarkPage(doc: Document): boolean {
  const view = doc.defaultView
  if (!view || !doc.body) return false
  for (const el of [doc.body, doc.documentElement]) {
    const m = view.getComputedStyle(el).backgroundColor.match(/\d+(\.\d+)?/g)
    if (!m || (m.length === 4 && Number(m[3]) === 0)) continue
    const [r, g, b] = m.map(Number)
    return 0.299 * r + 0.587 * g + 0.114 * b < 110
  }
  return false
}

/** Adds (or updates) the strip styles in a page that doesn't load the app's styles. */
export function injectSketchStyles(doc: Document, dark = isDarkPage(doc)) {
  let style = doc.getElementById('feeder-sketch-style')
  if (!style) {
    style = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style')
    style.id = 'feeder-sketch-style'
    ;(doc.head ?? doc.documentElement).append(style)
  }
  const css = sketchCss(dark)
  if (style.textContent !== css) style.textContent = css
}
