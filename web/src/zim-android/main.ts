/**
 * The script the Android app injects into every ZIM page it shows (ui/compose/zim/ZimReaderScreen.kt):
 * highlights and read aloud marks, with the same code as the web app's ZIM reader (lib/zim/marks.ts),
 * so a highlight made on one is found on the other.
 *
 * Built into app/src/main/assets/zim/feeder-zim.js by `npm run build:android-zim`. Talks to the app
 * through `FeederZim` (a JavaScript interface) and is driven through `window.feederZim`.
 */
import { indexText, rangeFor, type TextIndex } from '../ebook-android/textindex'
import { attachInkCapture, encodeInk, inkMarkAt, paintInk, parseInk, type InkData, type PageInk } from '../lib/pageink'
import { locateRange, textOf } from '../lib/textanchor'
import type { SketchColor } from '../lib/sketch'
import { anchorOf } from '../lib/textanchor'
import { rangeAt, firstVisibleText, highlightAt, injectMarkStyles, keepInView, paintHighlights, readableRoot, scrollToRange, selectionAnchor, sketchAt, type PageMark } from '../lib/zim/marks'

interface Bridge {
  onSelection(json: string): void
  onHighlightTap(id: string): void
  onTtsText(text: string, offset: number): void
  /** A handwritten note was finished: {text, start, prefix, ink}. */
  onInkNote(json: string): void
  /** Strokes were erased from a note: what's left (ink JSON), or "" to delete it. */
  onInkErase(id: string, ink: string): void
  /** A [✎] marker was tapped (a note written with other settings). */
  onInkMark(id: string): void
}

type ZimWindow = Window & { feederZim?: unknown; FeederZim?: Bridge; CSS: typeof CSS; Highlight: typeof Highlight }
const w = window as unknown as ZimWindow

if (!w.feederZim && document.body) {
  const bridge: Bridge = w.FeederZim ?? (new Proxy({}, { get: (_, name) => (...args: unknown[]) => console.log('FeederZim', String(name), ...args) }) as Bridge)
  let painted = new Map<string, Range>()
  let scrollWanted: string | null = null
  let tts: TextIndex | null = null
  let following = true
  let lastMark: Range | null = null

  injectMarkStyles(document)

  // Handwriting on the page (lib/pageink.ts); the app tells the pen color, eraser and Draw on page
  let pen: { drawAll: boolean; color: SketchColor; erasing: boolean } = { drawAll: false, color: 'pink', erasing: false }
  let inkShown = new Map<string, InkData>()
  let lastMarks: PageMark[] = []
  const paintNotes = () => {
    const body = document.body
    const full = textOf(body)
    const inks: PageInk[] = []
    for (const m of lastMarks) {
      const data = parseInk((m as PageMark & { ink?: string }).ink)
      if (!data) continue
      const at = locateRange(full, m)
      inks.push({ id: m.id, data, range: at ? rangeAt(body, at[0], at[1]) : null })
    }
    const shown = paintInk(document, body, inks)
    inkShown = new Map(inks.filter((k) => shown.has(k.id)).map((k) => [k.id, k.data]))
  }
  attachInkCapture(document, {
    root: document.body,
    mode: () => (pen.drawAll ? 'all' : 'pen'),
    color: () => pen.color,
    erasing: () => pen.erasing,
    shown: () => inkShown,
    onNote: (range, data) => {
      const a = anchorOf(document.body, range)
      if (a) bridge.onInkNote(JSON.stringify({ ...a, ink: encodeInk(data) }))
    },
    onErase: (id, data) => bridge.onInkErase(id, data ? encodeInk(data) : ''),
  })
  // The page reflows (rotation, zoom): ink only shows where the layout still matches
  let lastW = document.body.getBoundingClientRect().width
  new ResizeObserver(() => {
    const w = document.body.getBoundingClientRect().width
    if (Math.abs(w - lastW) < 1) return
    lastW = w
    paintNotes()
  }).observe(document.body)

  let selTimer = 0
  let lastSent = ''
  document.addEventListener('selectionchange', () => {
    window.clearTimeout(selTimer)
    selTimer = window.setTimeout(() => {
      const a = selectionAnchor(document)
      const json = a ? JSON.stringify(a) : 'null'
      if (json === lastSent) return
      lastSent = json
      bridge.onSelection(json)
    }, 350)
  })
  document.addEventListener(
    'click',
    (e) => {
      if (document.getSelection()?.isCollapsed === false) return
      if ((e.target as Element | null)?.closest?.('a[href]')) return
      const mark = inkMarkAt(e.target)
      if (mark) {
        bridge.onInkMark(mark)
        return
      }
      if ((e as PointerEvent).pointerType === 'pen' || pen.drawAll) return
      // A tap on a margin sketch opens it (sketches are highlights with a drawing)
      const sketch = sketchAt(e.target)
      if (sketch) {
        bridge.onHighlightTap(sketch)
        return
      }
      const id = highlightAt(document, e.clientX, e.clientY, painted)
      if (id) bridge.onHighlightTap(id)
    },
    true,
  )
  // Scrolling the page yourself stops read aloud from scrolling it, until "Go to spoken text"
  document.addEventListener('touchmove', () => (following = false), { passive: true })

  w.feederZim = {
    setHighlights(json: string) {
      lastMarks = JSON.parse(json) as PageMark[]
      painted = paintHighlights(document, lastMarks.filter((m) => !(m as PageMark & { ink?: string }).ink))
      paintNotes()
      if (scrollWanted && painted.has(scrollWanted)) {
        scrollToRange(document, painted.get(scrollWanted)!)
        scrollWanted = null
      }
    },
    scrollToHighlight(id: string) {
      const r = painted.get(id)
      if (r) scrollToRange(document, r)
      else scrollWanted = id
    },
    setPen(json: string) {
      pen = { ...pen, ...(JSON.parse(json) as Partial<typeof pen>) }
    },
    clearSelection() {
      document.getSelection()?.removeAllRanges()
      lastSent = 'null'
    },
    /** The page's text for read aloud, and where the text on screen starts in it. */
    ttsText() {
      tts = indexText(readableRoot(document) ?? document.body)
      following = true
      const top = firstVisibleText(document)
      // The first spoken text at or after it (it may be in something read aloud skips)
      const seg = top ? tts.segments.find((s) => s.node === top.node || !!(top.node.compareDocumentPosition(s.node) & Node.DOCUMENT_POSITION_FOLLOWING)) : null
      bridge.onTtsText(tts.text, seg ? seg.start : 0)
    },
    /** Marks the sentence and word being spoken and keeps them on screen. */
    ttsMark(start: number, end: number, wordStart: number, wordEnd: number) {
      if (!tts) tts = indexText(readableRoot(document) ?? document.body)
      const sentence = rangeFor(tts, document, start, end)
      const word = wordEnd > wordStart ? rangeFor(tts, document, wordStart, wordEnd) : null
      if (w.CSS?.highlights && w.Highlight) {
        if (sentence) w.CSS.highlights.set('ra-sentence', new w.Highlight(sentence))
        if (word) w.CSS.highlights.set('ra-word', new w.Highlight(word))
        else w.CSS.highlights.delete('ra-word')
      }
      lastMark = word ?? sentence
      if (lastMark && following) keepInView(document, lastMark)
    },
    ttsClear() {
      w.CSS?.highlights?.delete('ra-sentence')
      w.CSS?.highlights?.delete('ra-word')
      lastMark = null
    },
    ttsFollow() {
      following = true
      if (lastMark) scrollToRange(document, lastMark)
    },
  }
}
