/**
 * The two foliate-js renderers don't offer the same methods: reflowable books (paginator) have
 * styles, chapters and scrolling to a range; fixed-layout ones (PDF, comics) only turn pages.
 */
import type { FoliateView, Location } from '../../vendor/foliate-js/view.js'

export const isFixed = (v: FoliateView | null | undefined) => !!v?.isFixedLayout

export function setStyles(v: FoliateView | null | undefined, css: string) {
  v?.renderer?.setStyles?.(css)
}

export function atEnd(v: FoliateView | null | undefined): boolean {
  const r = v?.renderer
  if (!r) return true
  if (typeof r.atEnd === 'boolean') return r.atEnd
  return (v!.lastLocation?.fraction ?? 0) >= 0.999
}

export function atStart(v: FoliateView | null | undefined): boolean {
  const r = v?.renderer
  if (!r) return true
  if (typeof r.atStart === 'boolean') return r.atStart
  return (v!.lastLocation?.fraction ?? 0) <= 0.001
}

export function nextSection(v: FoliateView | null | undefined) {
  const r = v?.renderer
  return r?.nextSection ? r.nextSection() : r?.next()
}

export function prevSection(v: FoliateView | null | undefined) {
  const r = v?.renderer
  return r?.prevSection ? r.prevSection() : r?.prev()
}

/** Shows a range of the current page (reflowable books only; a fixed page is all on screen). */
export function reveal(v: FoliateView | null | undefined, range: Range) {
  return v?.renderer?.scrollToAnchor?.(range)
}

/** Read-aloud marks for fixed-layout pages, which get no reader styles (PDF text is see-through). */
export function markFixedPage(v: FoliateView | null | undefined, doc: Document) {
  if (!isFixed(v) || doc.getElementById('feeder-marks')) return
  const style = doc.createElement('style')
  style.id = 'feeder-marks'
  style.textContent = `
    ::highlight(ra-sentence) { background-color: rgba(31, 111, 74, 0.18); }
    ::highlight(ra-word) { background-color: rgba(31, 111, 74, 0.45); }
    ::highlight(feeder-hl-yellow) { background-color: rgba(255, 212, 59, 0.45); }
    ::highlight(feeder-hl-green) { background-color: rgba(92, 197, 131, 0.4); }
    ::highlight(feeder-hl-blue) { background-color: rgba(90, 165, 240, 0.4); }
    ::highlight(feeder-hl-pink) { background-color: rgba(224, 123, 180, 0.4); }
  `
  ;(doc.head ?? doc.documentElement).append(style)
}

type HighlightWindow = Window & { CSS: typeof CSS; Highlight: typeof Highlight }
const pageIndex = new WeakMap<Document, number>()
const COLORS = ['yellow', 'green', 'blue', 'pink']

/**
 * Draws saved highlights on a fixed-layout page (PDF, comics): foliate-js has no annotation layer
 * there, so they are marked with the page's CSS highlights instead. Pass the page's index when it
 * loads; later calls (highlights changed) use the remembered one.
 */
export function drawFixedHighlights(v: FoliateView | null | undefined, doc: Document, index: number | undefined, highlights: { cfi?: string; color: string }[]) {
  if (!v || !isFixed(v)) return
  if (index !== undefined) pageIndex.set(doc, index)
  const at = pageIndex.get(doc)
  const w = doc.defaultView as HighlightWindow | null
  if (at === undefined || !w?.CSS?.highlights) return
  const byColor = new Map<string, Range[]>(COLORS.map((c) => [c, []]))
  for (const h of highlights) {
    if (!h.cfi) continue
    try {
      const r = v.resolveNavigation(h.cfi)
      if (r?.index !== at || !r.anchor) continue
      const range = r.anchor(doc)
      if (range && 'startContainer' in range) byColor.get(COLORS.includes(h.color) ? h.color : 'yellow')!.push(range)
    } catch {
      // a highlight from another edition of the file
    }
  }
  for (const [color, ranges] of byColor) {
    if (ranges.length) w.CSS.highlights.set(`feeder-hl-${color}`, new w.Highlight(...ranges))
    else w.CSS.highlights.delete(`feeder-hl-${color}`)
  }
}

export function redrawFixedHighlights(v: FoliateView | null | undefined, highlights: { cfi?: string; color: string }[]) {
  if (!v || !isFixed(v)) return
  for (const { doc } of v.renderer.getContents()) if (doc) drawFixedHighlights(v, doc, undefined, highlights)
}

/** The shown page to read aloud: a fixed-layout spread can start with an empty frame. */
export function readableContent(v: FoliateView | null | undefined): { doc: Document } | undefined {
  const all = v?.renderer.getContents() ?? []
  return all.find((c) => c.doc?.body && (c.doc.body.textContent ?? '').trim()) ?? all[0]
}

/**
 * Page numbers the same on every device and text size: a PDF's own pages, else foliate's
 * locations (1500 characters each, like Kindle locations). Null when not known yet.
 */
export function pageNumbers(l: Pick<Location, 'location' | 'section'> | null | undefined, fixedLayout: boolean): { page: number; pages: number } | null {
  const src = fixedLayout ? l?.section : l?.location
  if (!src || !(src.total > 0)) return null
  return { page: Math.min(src.total, Math.max(1, src.current + 1)), pages: src.total }
}
