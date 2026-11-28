/**
 * Handwriting on the page, the way Kobo's stylus notes work: ink stays exactly where it was
 * written, but only while the text is laid out the same way. Each note remembers the layout of
 * the paragraph it was written on (its width, font, size, line height and text length: the
 * "signature"). When the layout matches, the ink is drawn back at the same place in that
 * paragraph; when it doesn't (other text size, font or screen), a small [✎] marker shows where
 * the note is, and the note can be seen as a picture or the settings switched back.
 *
 * A note is a highlight with an `ink` field (JSON, InkData), anchored to the words it covers,
 * so it syncs like one and older app versions show those words highlighted.
 *
 * DOM only, no app imports: the web app (articles, ZIM pages, ebooks) and the Android app's
 * ZIM and ebook page scripts all use this file.
 */
import { caretAt } from './zim/marks'
import { SKETCH_INK, isDarkPage, type SketchColor } from './sketch'
import { textOf } from './textanchor'

export interface InkStroke {
  c: SketchColor
  /** Width in CSS px. */
  w: number
  /** x, y, x, y, … in CSS px from the note's top left corner. */
  p: number[]
}

export interface InkData {
  v: 1
  /** Layout signature of the paragraph it was written on (see layoutSig). */
  sig: string
  /** Where the note's top left corner is from the paragraph's top left corner, CSS px. */
  ox: number
  oy: number
  w: number
  h: number
  s: InkStroke[]
  /** The reader settings it was written with, to switch back to (surface-specific). */
  prefs?: Record<string, unknown>
}

export interface PageInk {
  id: string
  data: InkData
  /** Where its words are on this page now; null when they can't be found. */
  range: Range | null
}

const COLORS: SketchColor[] = ['yellow', 'green', 'blue', 'pink']
const SVG_NS = 'http://www.w3.org/2000/svg'
const HTML_NS = 'http://www.w3.org/1999/xhtml'
const BLOCK = /^(P|LI|BLOCKQUOTE|H[1-6]|PRE|DD|DT|TD|TH|FIGCAPTION|CAPTION|DIV|SECTION|ARTICLE|BODY)$/

export function parseInk(raw: string | null | undefined): InkData | null {
  if (!raw) return null
  try {
    const v = JSON.parse(raw) as Partial<InkData>
    if (!v || typeof v.sig !== 'string' || !Array.isArray(v.s)) return null
    const s = v.s
      .filter((st): st is InkStroke => !!st && Array.isArray(st.p) && st.p.length >= 2)
      .map((st) => ({ c: COLORS.includes(st.c) ? st.c : 'blue', w: typeof st.w === 'number' && st.w > 0 ? st.w : 2, p: st.p.map((n) => (Number.isFinite(n) ? n : 0)) }))
    if (!s.length) return null
    const num = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : 0)
    return { v: 1, sig: v.sig, ox: num(v.ox), oy: num(v.oy), w: Math.max(1, num(v.w)), h: Math.max(1, num(v.h)), s, ...(v.prefs && typeof v.prefs === 'object' ? { prefs: v.prefs } : {}) }
  } catch {
    return null
  }
}

export function encodeInk(d: InkData): string {
  const r = (n: number) => Math.round(n * 10) / 10
  return JSON.stringify({ ...d, ox: r(d.ox), oy: r(d.oy), w: r(d.w), h: r(d.h), s: d.s.map((st) => ({ c: st.c, w: r(st.w), p: st.p.map(r) })) })
}

/** The paragraph-like element holding a node, below `root`. */
export function blockOf(node: Node, root: Element): Element {
  let el: Element | null = node.nodeType === Node.ELEMENT_NODE ? (node as Element) : node.parentElement
  while (el && el !== root && !BLOCK.test(el.tagName.toUpperCase())) el = el.parentElement
  return el && root.contains(el) ? el : root
}

/** What has to be the same for ink to land on the same words: width, font, size, line height and text. */
export function layoutSig(block: Element): string {
  const cs = block.ownerDocument.defaultView!.getComputedStyle(block)
  const first = block.getClientRects()[0] ?? block.getBoundingClientRect()
  const family = cs.fontFamily.split(',')[0].trim().replace(/["']/g, '')
  return [Math.round(first.width), cs.fontSize, cs.lineHeight, family, textOf(block).length].join('|')
}

/** The pressure-aware width of a stroke point, CSS px. */
function widthFor(e: PointerEvent) {
  return e.pointerType === 'pen' && e.pressure > 0 ? 2.2 * (0.5 + e.pressure) : 2.2
}

function pathOf(p: number[], dx = 0, dy = 0): string {
  if (p.length < 4) return `M${p[0] + dx} ${p[1] + dy}l0.01 0`
  let d = `M${p[0] + dx} ${p[1] + dy}`
  for (let i = 2; i < p.length - 2; i += 2) d += `Q${p[i] + dx} ${p[i + 1] + dy} ${(p[i] + p[i + 2]) / 2 + dx} ${(p[i + 1] + p[i + 3]) / 2 + dy}`
  return d + `L${p[p.length - 2] + dx} ${p[p.length - 1] + dy}`
}

/** The note as SVG markup at its own size (for previews and lists). */
export function inkSvg(d: InkData, dark = false): string {
  const paths = d.s.map((st) => `<path d="${pathOf(st.p)}" stroke="${SKETCH_INK[st.c][dark ? 1 : 0]}" stroke-width="${st.w}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('')
  return `<svg xmlns="${SVG_NS}" viewBox="-4 -4 ${d.w + 8} ${d.h + 8}" width="${Math.round(d.w + 8)}" style="max-width:100%;height:auto;max-height:60vh" aria-hidden="true">${paths}</svg>`
}

function injectInkStyles(doc: Document) {
  if (doc.getElementById('feeder-ink-style')) return
  const style = doc.createElementNS(HTML_NS, 'style')
  style.id = 'feeder-ink-style'
  style.textContent = `
    .feeder-ink { position: absolute; overflow: visible; pointer-events: none; z-index: 2; }
    .feeder-ink-mark { cursor: pointer; white-space: nowrap; }
    .feeder-ink-mark::before { content: "[\\270E]"; font-size: 0.85em; padding: 0 0.15em; color: #c2185b; font-weight: 600; }
    .feeder-ink-live { position: fixed; inset: 0; width: 100%; height: 100%; pointer-events: none; z-index: 2147483647; overflow: visible; }
  `
  ;(doc.head ?? doc.documentElement).append(style)
}

/**
 * Draws the notes on the page: ink where the paragraph's layout matches the one it was written
 * with, a [✎] marker at its words where it doesn't. Adds no text, so text offsets stay the same.
 * Returns which notes show as ink.
 */
export function paintInk(doc: Document, root: Element, inks: PageInk[]): Set<string> {
  removeInk(root)
  const shown = new Set<string>()
  if (!inks.length) return shown
  injectInkStyles(doc)
  const dark = isDarkPage(doc)
  // Work out every place first: adding markers splits text nodes (the ranges follow along)
  const plan = inks
    .filter((k) => k.range)
    .map((k) => {
      const block = blockOf(k.range!.startContainer, root)
      return { k, block, match: layoutSig(block) === k.data.sig }
    })
  for (const { k, block, match } of plan) {
    if (match) {
      const view = doc.defaultView!
      if (view.getComputedStyle(block).position === 'static') (block as HTMLElement).style.position = 'relative'
      const svg = doc.createElementNS(SVG_NS, 'svg')
      svg.setAttribute('class', 'feeder-ink')
      svg.setAttribute('data-ink', k.id)
      svg.setAttribute('aria-hidden', 'true')
      svg.setAttribute('style', `left:${k.data.ox}px;top:${k.data.oy}px;width:${k.data.w}px;height:${k.data.h}px`)
      svg.setAttribute('viewBox', `0 0 ${k.data.w} ${k.data.h}`)
      svg.innerHTML = k.data.s.map((st) => `<path d="${pathOf(st.p)}" stroke="${SKETCH_INK[st.c][dark ? 1 : 0]}" stroke-width="${st.w}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('')
      block.append(svg)
      shown.add(k.id)
    } else {
      const mark = doc.createElementNS(HTML_NS, 'span') as HTMLElement
      mark.className = 'feeder-ink-mark'
      mark.setAttribute('data-ink', k.id)
      mark.setAttribute('role', 'button')
      mark.setAttribute('aria-label', 'Handwritten note, written with other reading settings')
      mark.setAttribute('contenteditable', 'false')
      const at = k.range!.cloneRange()
      at.collapse(true)
      at.insertNode(mark)
    }
  }
  return shown
}

export function removeInk(root: ParentNode) {
  root.querySelectorAll('.feeder-ink, .feeder-ink-mark').forEach((n) => n.remove())
}

/** The [✎] marker under a tap, if any. */
export function inkMarkAt(target: EventTarget | null): string | null {
  const el = (target as Element | null)?.closest?.('.feeder-ink-mark') as HTMLElement | null
  return el?.getAttribute('data-ink') ?? null
}

export type InkMode = 'pen' | 'all' | 'off'

export interface InkCapture {
  /** The element whose text notes are anchored in (article body, page body). */
  root: Element
  /** 'pen': only a stylus writes; 'all': fingers and the mouse too (Draw on page); 'off'. */
  mode: () => InkMode
  color: () => SketchColor
  /** The eraser is picked in the app (the pen's eraser end and barrel button always erase). */
  erasing: () => boolean
  /** Settings to remember with a new note (see InkData.prefs). */
  prefs?: () => Record<string, unknown> | undefined
  /** A note was finished: its words and drawing. */
  onNote: (range: Range, data: InkData) => void
  /** Strokes were erased from a shown note: what's left of it (null = nothing, delete it). */
  onErase: (id: string, data: InkData | null) => void
  /** The notes shown as ink now, to erase from. */
  shown: () => Map<string, InkData>
}

/**
 * Lets a pen write on the page. Strokes written within a moment of each other make one note,
 * saved about a second after the pen lifts. Returns a function that stops listening.
 */
export function attachInkCapture(doc: Document, o: InkCapture): () => void {
  injectInkStyles(doc)
  let live: SVGSVGElement | null = null
  let pending: InkStroke[] = []
  let current: { id: number; stroke: InkStroke | null; erase: boolean } | null = null
  let timer = 0
  const view = doc.defaultView!

  const rootPoint = (e: { clientX: number; clientY: number }) => {
    const r = o.root.getBoundingClientRect()
    return [e.clientX - r.left, e.clientY - r.top] as const
  }

  const drawLive = () => {
    if (!live) {
      live = doc.createElementNS(SVG_NS, 'svg') as SVGSVGElement
      live.setAttribute('class', 'feeder-ink-live')
      doc.body.append(live)
    }
    const r = o.root.getBoundingClientRect()
    const dark = isDarkPage(doc)
    const all = current?.stroke ? [...pending, current.stroke] : pending
    live.innerHTML = all.map((st) => `<path d="${pathOf(st.p, r.left, r.top)}" stroke="${SKETCH_INK[st.c][dark ? 1 : 0]}" stroke-width="${st.w}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('')
  }

  const clearLive = () => {
    live?.remove()
    live = null
  }

  const finish = () => {
    view.clearTimeout(timer)
    const strokes = pending
    pending = []
    clearLive()
    if (!strokes.length) return
    let minX = Infinity,
      minY = Infinity,
      maxX = -Infinity,
      maxY = -Infinity
    for (const st of strokes)
      for (let i = 0; i < st.p.length; i += 2) {
        minX = Math.min(minX, st.p[i] - st.w)
        maxX = Math.max(maxX, st.p[i] + st.w)
        minY = Math.min(minY, st.p[i + 1] - st.w)
        maxY = Math.max(maxY, st.p[i + 1] + st.w)
      }
    const rr = o.root.getBoundingClientRect()
    // The words under the note: from its top left to its bottom right corner (kept inside the text)
    const clampX = (x: number) => Math.min(Math.max(x, rr.left + 1), rr.right - 1)
    const a = caretAt(doc, clampX(rr.left + minX + 2), rr.top + minY + 2)
    const b = caretAt(doc, clampX(rr.left + maxX - 2), rr.top + maxY - 2)
    if (!a || !o.root.contains(a.node)) return
    const range = doc.createRange()
    range.setStart(a.node, a.offset)
    if (b && o.root.contains(b.node)) {
      range.setEnd(b.node, b.offset)
      if (range.collapsed) range.setStart(b.node, b.offset), range.setEnd(a.node, a.offset)
    }
    if (range.collapsed || !range.toString().trim()) {
      // A note in the margin: tie it to the nearest words
      const t = a.node.nodeType === Node.TEXT_NODE ? (a.node as Text) : null
      if (t) {
        const s = Math.max(0, Math.min(a.offset, t.length - 1))
        range.setStart(t, s)
        range.setEnd(t, Math.min(t.length, s + 24))
      }
    }
    // Whole words: the note is about words, not the letters the pen started on
    if (range.startContainer.nodeType === Node.TEXT_NODE) {
      const t = range.startContainer as Text
      let i = range.startOffset
      while (i > 0 && !/\s/.test(t.data[i - 1])) i--
      range.setStart(t, i)
    }
    if (range.endContainer.nodeType === Node.TEXT_NODE) {
      const t = range.endContainer as Text
      let i = range.endOffset
      while (i < t.length && !/\s/.test(t.data[i])) i++
      range.setEnd(t, i)
    }
    const block = blockOf(range.startContainer, o.root)
    const first = block.getClientRects()[0] ?? block.getBoundingClientRect()
    const bx = first.left - rr.left
    const by = first.top - rr.top
    const data: InkData = {
      v: 1,
      sig: layoutSig(block),
      ox: minX - bx,
      oy: minY - by,
      w: maxX - minX,
      h: maxY - minY,
      s: strokes.map((st) => ({ ...st, p: st.p.map((n, i) => (i % 2 ? n - minY : n - minX)) })),
      ...(o.prefs?.() ? { prefs: o.prefs() } : {}),
    }
    o.onNote(range, data)
  }

  const eraseAt = (e: { clientX: number; clientY: number }) => {
    // Pending strokes first
    const [x, y] = rootPoint(e)
    const before = pending.length
    pending = pending.filter((st) => !near(st.p, x, y, 10 + st.w))
    if (pending.length !== before) drawLive()
    const shown = o.shown()
    doc.querySelectorAll<SVGSVGElement>('.feeder-ink').forEach((svg) => {
      const id = svg.getAttribute('data-ink')!
      const d = shown.get(id)
      if (!d) return
      const r = svg.getBoundingClientRect()
      const lx = e.clientX - r.left
      const ly = e.clientY - r.top
      if (lx < -12 || ly < -12 || lx > r.width + 12 || ly > r.height + 12) return
      const left = d.s.filter((st) => !near(st.p, lx, ly, 10 + st.w))
      if (left.length !== d.s.length) {
        const next = left.length ? { ...d, s: left } : null
        shown.set(id, next ?? d)
        if (!next) svg.remove()
        else svg.innerHTML = left.map((st) => `<path d="${pathOf(st.p)}" stroke="${SKETCH_INK[st.c][isDarkPage(doc) ? 1 : 0]}" stroke-width="${st.w}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`).join('')
        o.onErase(id, next)
      }
    })
  }

  const down = (e: PointerEvent) => {
    const mode = o.mode()
    if (mode === 'off' || (mode === 'pen' && e.pointerType !== 'pen')) return
    if (e.button !== 0 && e.button !== 5 && e.button !== 2) return
    if (mode === 'all' && e.pointerType === 'mouse' && e.button !== 0) return
    e.preventDefault()
    e.stopPropagation()
    view.clearTimeout(timer)
    doc.getSelection()?.removeAllRanges()
    const erase = o.erasing() || e.button === 5 || (e.buttons & 32) !== 0 || (e.pointerType === 'pen' && (e.buttons & 2) !== 0)
    current = { id: e.pointerId, erase, stroke: null }
    try {
      ;(e.target as Element).setPointerCapture?.(e.pointerId)
    } catch {
      /* not a live pointer */
    }
    if (erase) eraseAt(e)
    else {
      const [x, y] = rootPoint(e)
      current.stroke = { c: o.color(), w: widthFor(e), p: [x, y] }
      drawLive()
    }
  }
  const move = (e: PointerEvent) => {
    if (!current || current.id !== e.pointerId) return
    e.preventDefault()
    e.stopPropagation()
    const list = e.getCoalescedEvents?.() ?? []
    const evs = list.length ? list : [e]
    if (current.erase) return evs.forEach(eraseAt)
    const st = current.stroke!
    for (const ev of evs) {
      const [x, y] = rootPoint(ev)
      if (Math.hypot(x - st.p[st.p.length - 2], y - st.p[st.p.length - 1]) >= 1) st.p.push(x, y)
    }
    if (e.pointerType === 'pen') st.w = st.w * 0.85 + widthFor(e) * 0.15
    drawLive()
  }
  const up = (e: PointerEvent) => {
    if (!current || current.id !== e.pointerId) return
    e.preventDefault()
    e.stopPropagation()
    if (current.stroke) pending.push(current.stroke)
    current = null
    view.clearTimeout(timer)
    timer = view.setTimeout(finish, 1100)
  }
  // Fingers on the page while writing: the note is done (and the page may scroll)
  const touchOther = (e: PointerEvent) => {
    if (o.mode() === 'pen' && e.pointerType !== 'pen' && pending.length && !current) finish()
  }
  const opts = { capture: true, passive: false } as AddEventListenerOptions
  doc.addEventListener('pointerdown', touchOther, true)
  doc.addEventListener('pointerdown', down, opts)
  doc.addEventListener('pointermove', move, opts)
  doc.addEventListener('pointerup', up, opts)
  doc.addEventListener('pointercancel', up, opts)
  // Stops the browser from scrolling or selecting under a pen
  const touchStart = (e: TouchEvent) => {
    if (current) e.preventDefault()
  }
  doc.addEventListener('touchmove', touchStart, { capture: true, passive: false })
  return () => {
    finish()
    doc.removeEventListener('pointerdown', touchOther, true)
    doc.removeEventListener('pointerdown', down, opts)
    doc.removeEventListener('pointermove', move, opts)
    doc.removeEventListener('pointerup', up, opts)
    doc.removeEventListener('pointercancel', up, opts)
    doc.removeEventListener('touchmove', touchStart, { capture: true } as EventListenerOptions)
  }
}

function near(p: number[], x: number, y: number, r: number) {
  for (let i = 0; i < p.length; i += 2) if (Math.hypot(p[i] - x, p[i + 1] - y) < r) return true
  return false
}
