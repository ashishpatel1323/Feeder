/**
 * The ebook reader page the Android app shows in a WebView (ui/compose/ebooks/EbookReaderScreen.kt).
 * Same engine (foliate-js), page styles and script blocking as the web app's Ebooks tab, so
 * positions (CFIs) and highlights mean the same on both.
 *
 * Built into app/src/main/assets/ebook/ by `npm run build:android-reader`. The app serves it at
 * https://ebook.feeder/ and the book file at https://ebook.feeder/book/<file name>.
 *
 * Talks to the app through `FeederBook` (a JavaScript interface) and is driven through
 * `window.feeder` (evaluateJavascript).
 */
// PDF.js finds its worker, fonts and character maps next to this page (assets/ebook/pdfjs)
;(globalThis as { FEEDER_PDFJS_BASE?: string }).FEEDER_PDFJS_BASE = './pdfjs/'
import '../vendor/foliate-js/view.js'
import type { FoliateView, Location, TocItem } from '../vendor/foliate-js/view.js'
import { Overlayer } from '../vendor/foliate-js/overlayer.js'
import { authorString, bookCss, injectCsp, langString, type ReaderPrefs } from '../lib/ebooks/reader'
import { indexText, rangeFor, type TextIndex } from './textindex'
import { atEnd, atStart, drawFixedHighlights, markFixedPage, placeBookSketches, readableContent, redrawFixedHighlights, replaceBookSketches, nextSection, pageNumbers, prevSection, reveal, setStyles } from '../lib/ebooks/nav'

interface Bridge {
  onOpened(json: string): void
  onRelocate(json: string): void
  onSelection(json: string): void
  onHighlightTap(cfi: string): void
  onTtsText(text: string, offset: number, section: number): void
  onSectionTexts(json: string): void
  onSectionLoaded(): void
  onTap(zone: string): void
  onError(message: string): void
}

const bridge: Bridge =
  (window as unknown as { FeederBook?: Bridge }).FeederBook ??
  // In a desktop browser (for trying it out): log instead
  (new Proxy({}, { get: (_, name) => (...args: unknown[]) => console.log('FeederBook', String(name), ...args) }) as Bridge)

const COLORS: Record<string, string> = { yellow: '#ffe066', green: '#b7ebc8', blue: '#bcdcff', pink: '#f6c3e0' }

let view: FoliateView | null = null
let prefs: ReaderPrefs = { fontSize: 100, lineHeight: 1.5, theme: 'light', flow: 'paginated', publisherFont: true }
let appDark = false
let highlights = new Map<string, string>()
/** Margin sketches: strips after their paragraph in reflowable books (as highlights on PDF pages). */
type SketchMark = { id: string; cfi: string; color: string; sketch: string }
let sketches: SketchMark[] = []

type HighlightJson = { id?: string; cfi: string; color: string; sketch?: string }
/** Splits the app's list: plain highlights by CFI, and sketches. */
function readHighlights(json: string) {
  const all = JSON.parse(json) as HighlightJson[]
  const fixed = !!view?.isFixedLayout
  sketches = all.filter((h) => h.sketch).map((h) => ({ id: h.id ?? h.cfi, cfi: h.cfi, color: h.color, sketch: h.sketch! }))
  return new Map(all.filter((h) => !h.sketch || fixed).map((h) => [h.cfi, h.color]))
}
let tts: { index: TextIndex; doc: Document } | null = null

function showMessage(text: string) {
  const el = document.getElementById('msg')!
  el.textContent = text
  el.style.display = 'flex'
}

function applyPrefs() {
  if (!view?.renderer) return
  view.renderer.setAttribute('flow', prefs.flow)
  setStyles(view, bookCss(prefs, appDark))
  const bg = { light: '#ffffff', sepia: '#f4ecd8', dark: '#111412' }[prefs.theme === 'auto' ? (appDark ? 'dark' : 'light') : prefs.theme]
  document.documentElement.style.setProperty('--bg', bg)
}

async function open(url: string, name: string, cfi: string | null, prefsJson: string, dark: boolean, highlightsJson: string) {
  try {
    prefs = { ...prefs, ...(JSON.parse(prefsJson) as Partial<ReaderPrefs>) }
    appDark = dark
    highlights = readHighlights(highlightsJson)
    const res = await fetch(url)
    if (!res.ok) throw new Error(`The book file could not be read (${res.status})`)
    const file = new File([await res.blob()], name)
    const { makeBook } = await import('../vendor/foliate-js/view.js')
    const book = await makeBook(file)
    book.transformTarget?.addEventListener('data', (e) => {
      const d = (e as CustomEvent<{ type: string; data: unknown }>).detail
      if (/html|xml/.test(d.type) && !/svg|ncx|opf|oebps-package/.test(d.type)) {
        d.data = Promise.resolve(d.data).then((x) => (typeof x === 'string' ? injectCsp(x) : x))
      }
    })
    view = document.createElement('foliate-view') as FoliateView
    document.body.append(view)
    await view.open(book)
    view.renderer.setAttribute('max-inline-size', '720px')
    view.renderer.setAttribute('gap', '7%')
    applyPrefs()
    listen(view)
    await view.init({ lastLocation: cfi, showTextStart: !cfi })

    let cover: string | null = null
    try {
      const blob = await book.getCover?.()
      if (blob) cover = await smallCover(blob)
    } catch {
      // no cover
    }
    bridge.onOpened(
      JSON.stringify({
        title: langString(book.metadata?.title),
        author: authorString(book.metadata?.author),
        cover,
        toc: flatToc(book.toc ?? []),
      }),
    )
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    showMessage(msg)
    bridge.onError(msg)
  }
}

function flatToc(items: TocItem[], depth = 0): { label: string; href: string; depth: number }[] {
  return items.flatMap((t) => [{ label: (t.label ?? '').trim() || 'Untitled', href: t.href, depth }, ...flatToc(t.subitems ?? [], depth + 1)])
}

async function smallCover(blob: Blob): Promise<string | null> {
  const bmp = await createImageBitmap(blob).catch(() => null)
  if (!bmp) return null
  const h = Math.min(360, bmp.height)
  const w = Math.max(1, Math.round((bmp.width * h) / bmp.height))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, w, h)
  bmp.close()
  return canvas.toDataURL('image/jpeg', 0.82)
}

function listen(v: FoliateView) {
  let lastFixedIndex = -1
  v.addEventListener('relocate', (e) => {
    const l = (e as CustomEvent<Location>).detail
    // Fixed-layout pages (PDF) may already be loaded: tell the app a new page shows, for read aloud
    const index = l.section?.current ?? -1
    if (v.isFixedLayout && index !== lastFixedIndex) {
      lastFixedIndex = index
      tts = null
      bridge.onSectionLoaded()
    }
    bridge.onRelocate(
      JSON.stringify({
        cfi: l.cfi,
        fraction: Number.isFinite(l.fraction) ? l.fraction : 0,
        // Page x of y, the same on every device (see pageNumbers)
        ...(pageNumbers(l, v.isFixedLayout) ?? {}),
        chapter: l.tocItem?.label?.trim() ?? '',
        atStart: atStart(v),
        atEnd: atEnd(v),
      }),
    )
  })
  v.addEventListener('create-overlay', () => {
    for (const [cfi, color] of highlights) void v.addAnnotation({ value: cfi, color })
  })
  v.addEventListener('draw-annotation', (e) => {
    const { draw, annotation } = (e as CustomEvent<{ draw: (f: unknown, o: unknown) => void; annotation: { color?: string } }>).detail
    draw(Overlayer.highlight, { color: COLORS[annotation.color ?? 'yellow'] ?? COLORS.yellow })
  })
  v.addEventListener('show-annotation', (e) => bridge.onHighlightTap((e as CustomEvent<{ value: string }>).detail.value))
  v.addEventListener('load', (e) => {
    const { doc, index } = (e as CustomEvent<{ doc: Document; index: number }>).detail
    markFixedPage(v, doc)
    drawFixedHighlights(v, doc, index, [...highlights].map(([cfi, color]) => ({ cfi, color })))
    placeBookSketches(v, doc, index, sketches)
    let selTimer = 0
    doc.addEventListener('selectionchange', () => {
      window.clearTimeout(selTimer)
      selTimer = window.setTimeout(() => {
        const sel = doc.getSelection()
        if (!sel || sel.isCollapsed || !sel.rangeCount) return bridge.onSelection('null')
        const range = sel.getRangeAt(0)
        const text = range.toString().trim()
        if (!text) return bridge.onSelection('null')
        bridge.onSelection(JSON.stringify({ cfi: v.getCFI(index, range), text }))
      }, 350)
    })
    doc.addEventListener('click', (c) => {
      // A tap on a margin sketch opens it (the app finds it by its CFI, like a highlight)
      const strip = (c.target as Element | null)?.closest?.('.feeder-sketch') as HTMLElement | null
      const sk = strip?.dataset.sketch ? sketches.find((x) => x.id === strip.dataset.sketch) : undefined
      if (sk) return bridge.onHighlightTap(sk.cfi)
      if (doc.getSelection()?.isCollapsed === false) return
      if ((c.target as Element | null)?.closest?.('a[href]')) return
// The page's iframe is as wide as the whole chapter: measure the tap on the screen instead
      const frame = doc.defaultView?.frameElement?.getBoundingClientRect()
      const x = (frame?.left ?? 0) + c.clientX
      const box = v.getBoundingClientRect()
      const frac = box.width ? (x - box.left) / box.width : 0.5
      if (frac < 0.25) void v.goLeft()
      else if (frac > 0.75) void v.goRight()
      else bridge.onTap('center')
    })
    tts = null
    bridge.onSectionLoaded()
  })
}

/** Text of the shown chapter for read aloud, and where the page on screen starts in it. */
function ttsText() {
  const c = readableContent(view)
  if (!view || !c?.doc.body) return
  const index = indexText(c.doc.body)
  tts = { index, doc: c.doc }
  const r = view.lastLocation?.range
  let offset = 0
  if (r) {
    const seg = index.segments.find((s) => s.node === r.startContainer)
    if (seg) offset = seg.start + Math.min(r.startOffset, seg.end - seg.start)
  }
  bridge.onTtsText(index.text, offset, view.lastLocation?.section?.current ?? -1)
}

/** Indexes the shown chapter for marking spoken text, when its audio was prepared ahead. */
function ttsIndex() {
  const c = readableContent(view)
  if (!view || !c?.doc.body) return
  tts = { index: indexText(c.doc.body), doc: c.doc }
}

/**
 * Text of up to `count` chapters after section `after` (the same text ttsText gives once a
 * chapter shows), so the app can speak them ahead of time. Skips sections with nothing to read.
 */
async function sectionTexts(after: number, count: number) {
  const out: { index: number; label: string; text: string }[] = []
  let end = false
  try {
    const sections = view?.book?.sections
    if (!view || view.isFixedLayout || !sections) return
    for (let i = after + 1; i < sections.length && out.length < count; i++) {
      const s = sections[i]
      if (s.linear === 'no' || !s.createDocument) continue
      const doc = await s.createDocument()
      if (!doc?.body) continue
      const text = indexText(doc.body).text
      if (!/[\p{L}\p{N}]/u.test(text) || text.trim().length < 40) continue
      const label = (view.getProgressOf(i)?.tocItem?.label ?? '').trim()
      out.push({ index: i, label, text })
    }
    // Nothing more to read after these: the app knows which chapter ends the book
    end = out.length < count
  } catch (e) {
    console.warn('Chapter text for read aloud failed', e)
  } finally {
    bridge.onSectionTexts(JSON.stringify({ texts: out, last: end ? (out[out.length - 1]?.index ?? after) : -1 }))
  }
}

type HighlightWindow = Window & { CSS: typeof CSS; Highlight: typeof Highlight }

/** Marks the sentence and word being spoken, turning the page when it moves off screen. */
function ttsMark(start: number, end: number, wordStart: number, wordEnd: number) {
  if (!tts || !view) return
  const w = tts.doc.defaultView as HighlightWindow | null
  if (!w?.CSS?.highlights) return
  const sentence = rangeFor(tts.index, tts.doc, start, end)
  if (sentence) w.CSS.highlights.set('ra-sentence', new w.Highlight(sentence))
  const word = wordEnd > wordStart ? rangeFor(tts.index, tts.doc, wordStart, wordEnd) : null
  if (word) w.CSS.highlights.set('ra-word', new w.Highlight(word))
  else w.CSS.highlights.delete('ra-word')
  const anchor = word ?? sentence
  const shown = view.lastLocation?.range
  if (!anchor) return
  try {
    if (shown && anchor.compareBoundaryPoints(Range.START_TO_START, shown) >= 0 && anchor.compareBoundaryPoints(Range.START_TO_END, shown) <= 0) return
  } catch {
    // a range from another chapter
  }
  void reveal(view, anchor)
}

function ttsClear() {
  const w = tts?.doc.defaultView as HighlightWindow | null | undefined
  w?.CSS?.highlights?.delete('ra-sentence')
  w?.CSS?.highlights?.delete('ra-word')
}

;(window as unknown as { feeder: unknown }).feeder = {
  open,
  setPrefs(json: string, dark: boolean) {
    prefs = { ...prefs, ...(JSON.parse(json) as Partial<ReaderPrefs>) }
    appDark = dark
    applyPrefs()
  },
  setHighlights(json: string) {
    const before = JSON.stringify(sketches)
    const next = readHighlights(json)
    if (JSON.stringify(sketches) !== before) replaceBookSketches(view, sketches)
    if (view) {
      for (const [cfi, color] of next) if (highlights.get(cfi) !== color) void view.addAnnotation({ value: cfi, color })
      for (const cfi of highlights.keys()) if (!next.has(cfi)) void view.deleteAnnotation({ value: cfi })
    }
    highlights = next
    redrawFixedHighlights(view, [...next].map(([cfi, color]) => ({ cfi, color })))
  },
  goTo: (target: string) => void view?.goTo(target),
  goToFraction: (f: number) => void view?.goToFraction(f),
  next: () => void view?.goRight(),
  prev: () => void view?.goLeft(),
  nextSection: () => void nextSection(view),
  prevSection: () => void prevSection(view),
  clearSelection: () => view?.deselect(),
  ttsText,
  ttsIndex,
  sectionTexts: (after: number, count: number) => void sectionTexts(after, count),
  goToSection: (index: number) => void view?.goTo(index),
  ttsMark,
  ttsClear,
}
