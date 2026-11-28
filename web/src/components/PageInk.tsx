import { useEffect, useRef, useState } from 'react'
import type { Highlight } from '../lib/db'
import { HIGHLIGHT_COLORS } from '../lib/highlights'
import { attachInkCapture, flushInk, inkSvg, paintInk, parseInk, type InkData, type InkMode, type PageInk } from '../lib/pageink'
import type { SketchColor } from '../lib/sketch'
import { Icon, IconButton } from './Icon'

/** Pen color and eraser, shared by every page with handwriting; the color is remembered. */
export function usePenState() {
  const [color, setColorState] = useState<SketchColor>(() => (localStorage.getItem('feeder.inkColor') as SketchColor | null) ?? 'pink')
  const [erasing, setErasing] = useState(false)
  const [drawAll, setDrawAll] = useState(false)
  const setColor = (c: SketchColor) => {
    localStorage.setItem('feeder.inkColor', c)
    setColorState(c)
    setErasing(false)
  }
  return { color, setColor, erasing, setErasing, drawAll, setDrawAll }
}
export type PenState = ReturnType<typeof usePenState>

/**
 * Handwriting on one page (a document and the element holding its text): a stylus always
 * writes; with Draw on page on, fingers and the mouse do too. Draws the saved notes back as ink
 * where the layout matches, [✎] markers where it doesn't (lib/pageink.ts).
 */
export function usePageInk(p: {
  doc: Document | null
  root: Element | null
  pen: PenState
  /** This page's handwritten notes. */
  notes: Highlight[]
  /** Where a note's words are on this page. */
  rangeOf: (h: Highlight) => Range | null
  onNote: (range: Range, data: InkData) => void
  onErase: (id: string, data: InkData | null) => void
  prefs?: () => Record<string, unknown> | undefined
  /** Changes when the page's text is replaced (paint again). */
  version: unknown
}) {
  const shownRef = useRef(new Map<string, InkData>())
  const latest = useRef(p)
  latest.current = p

  // Writing
  useEffect(() => {
    const { doc, root } = p
    if (!doc || !root) return
    return attachInkCapture(doc, {
      root,
      mode: (): InkMode => (latest.current.pen.drawAll ? 'all' : 'pen'),
      color: () => latest.current.pen.color,
      erasing: () => latest.current.pen.erasing,
      prefs: () => latest.current.prefs?.(),
      onNote: (r, d) => latest.current.onNote(r, d),
      onErase: (id, d) => latest.current.onErase(id, d),
      shown: () => shownRef.current,
    })
  }, [p.doc, p.root])

  // Drawing the notes, again when they change, when the text is replaced or the layout changes
  const key = p.notes.map((h) => `${h.id}:${h.ink?.length}:${h.modifiedAt ?? 0}`).join('|')
  useEffect(() => {
    const { doc, root } = p
    if (!doc || !root) return
    const paint = () => {
      const notes = latest.current.notes
      const inks: PageInk[] = []
      for (const h of notes) {
        const data = parseInk(h.ink)
        if (data) inks.push({ id: h.id, data, range: latest.current.rangeOf(h) })
      }
      const shown = paintInk(doc, root, inks)
      shownRef.current = new Map(inks.filter((k) => shown.has(k.id)).map((k) => [k.id, k.data]))
    }
    // A timer, not a frame: frames don't run in background tabs
    let frame = window.setTimeout(paint, 0)
    void doc.fonts?.ready.then(() => {
      window.clearTimeout(frame)
      frame = window.setTimeout(paint, 0)
    })
    // Text size, font or width changed: the layout moved (height alone changes with the text size)
    let last = root.getBoundingClientRect()
    const ro = new ResizeObserver(() => {
      const now = root.getBoundingClientRect()
      if (Math.abs(now.width - last.width) < 1 && Math.abs(now.height - last.height) < 1) return
      last = now
      window.clearTimeout(frame)
      frame = window.setTimeout(paint, 50)
    })
    ro.observe(root)
    return () => {
      window.clearTimeout(frame)
      ro.disconnect()
    }
  }, [p.doc, p.root, key, p.version])
}

/** The floating pen bar, shown while Draw on page is on. */
export function PenBar({ pen, onDone }: { pen: PenState; onDone: () => void }) {
  return (
    <div className="pen-bar" role="toolbar" aria-label="Pen">
      <IconButton icon="draw" label="Pen" active={!pen.erasing} onClick={() => pen.setErasing(false)} />
      <IconButton icon="eraser" label="Eraser" active={pen.erasing} onClick={() => pen.setErasing(true)} />
      <span className="sketch-vr" />
      {HIGHLIGHT_COLORS.map((c) => (
        <button
          key={c.id}
          type="button"
          className={`sketch-swatch${pen.color === c.id && !pen.erasing ? ' on' : ''}`}
          style={{ background: `var(--feeder-ink-${c.id})` }}
          title={c.label}
          aria-label={c.label}
          aria-pressed={pen.color === c.id}
          onClick={() => pen.setColor(c.id)}
        />
      ))}
      <span className="sketch-vr" />
      <button type="button" className="hl-btn primary pen-done" onClick={onDone}>
        <Icon name="check" size={18} /> Done
      </button>
    </div>
  )
}

/**
 * A note written with other reading settings: shown as it was written, with its words, and a
 * button to switch back to those settings when this page can.
 */
export function InkPreview(p: { note: Highlight; canSwitch: boolean; onSwitch: () => void; onDelete: () => void; onClose: () => void }) {
  const data = parseInk(p.note.ink)
  const dark = document.documentElement.dataset.theme === 'dark' || (document.documentElement.dataset.theme !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && p.onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [p])
  return (
    <div className="sketch-editor" role="dialog" aria-modal="true" aria-label="Handwritten note" onClick={(e) => e.target === e.currentTarget && p.onClose()}>
      <div className="sketch-sheet ink-preview">
        <div className="sketch-top">
          <IconButton icon="close" label="Close (Esc)" onClick={p.onClose} />
          <h2>Handwritten note</h2>
          <button type="button" className="hl-btn danger sketch-del" onClick={p.onDelete}>
            <Icon name="delete" size={20} /> Delete
          </button>
        </div>
        <p className="ink-why">Written with other reading settings (text size, font or window width), so it isn't drawn on the page now.</p>
        <blockquote className="sketch-quote">{p.note.text}</blockquote>
        {data ? <div className="ink-preview-art" dangerouslySetInnerHTML={{ __html: inkSvg(data, dark) }} /> : null}
        {p.canSwitch ? (
          <button type="button" className="hl-btn primary ink-switch" onClick={p.onSwitch}>
            <Icon name="textSize" size={20} /> Switch to these settings
          </button>
        ) : null}
      </div>
    </div>
  )
}

/**
 * While Draw on page is on, a finger or the mouse writes instead of scrolling, so keys move the
 * page (the web's stand-in for the phone's volume keys): Page Down, Space and ↓ go about a screen
 * down, Page Up, Shift+Space and ↑ a screen up. `doc` is a page frame's document (ZIM), whose
 * keys don't reach the window.
 */
export function usePageKeysWhileDrawing(on: boolean, scrollBy: (dir: 1 | -1) => void, doc?: Document | null) {
  const latest = useRef(scrollBy)
  latest.current = scrollBy
  useEffect(() => {
    if (!on) return
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return
      const t = e.target as HTMLElement | null
      if (t && (/INPUT|TEXTAREA|SELECT/.test(t.tagName) || t.isContentEditable)) return
      // Space and arrows keep working on focused controls (the pen bar, menus)
      if (t?.closest?.('button, a[href], [role="button"], [role="menuitem"], [role="tab"], [role="slider"]')) return
      if (document.querySelector('.modal-backdrop')) return
      const dir = e.key === 'PageDown' || e.key === 'ArrowDown' || (e.key === ' ' && !e.shiftKey) ? 1 : e.key === 'PageUp' || e.key === 'ArrowUp' || (e.key === ' ' && e.shiftKey) ? -1 : 0
      if (!dir) return
      e.preventDefault()
      // A note still being written is saved where it is before the page moves
      flushInk(document)
      if (doc) flushInk(doc)
      latest.current(dir)
    }
    const targets: (Window | Document)[] = [window]
    if (doc) targets.push(doc)
    targets.forEach((t) => t.addEventListener('keydown', onKey as EventListener, true))
    return () => targets.forEach((t) => t.removeEventListener('keydown', onKey as EventListener, true))
  }, [on, doc])
}
