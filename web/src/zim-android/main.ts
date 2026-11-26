/**
 * The script the Android app injects into every ZIM page it shows (ui/compose/zim/ZimReaderScreen.kt):
 * highlights and read aloud marks, with the same code as the web app's ZIM reader (lib/zim/marks.ts),
 * so a highlight made on one is found on the other.
 *
 * Built into app/src/main/assets/zim/feeder-zim.js by `npm run build:android-zim`. Talks to the app
 * through `FeederZim` (a JavaScript interface) and is driven through `window.feederZim`.
 */
import { indexText, rangeFor, type TextIndex } from '../ebook-android/textindex'
import { firstVisibleText, highlightAt, injectMarkStyles, keepInView, paintHighlights, readableRoot, scrollToRange, selectionAnchor, sketchAt, type PageMark } from '../lib/zim/marks'

interface Bridge {
  onSelection(json: string): void
  onHighlightTap(id: string): void
  onTtsText(text: string, offset: number): void
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
      painted = paintHighlights(document, JSON.parse(json) as PageMark[])
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
