import { useEffect, useRef, useState } from 'react'
import { encodeSketch, parseSketch, SKETCH_W, strokePath, type Sketch, type SketchColor, type SketchStroke } from '../lib/sketch'
import { HIGHLIGHT_COLORS } from '../lib/highlights'
import { Icon, IconButton } from './Icon'

/** Base stroke width in 1/1000 of the width; a pen's pressure makes it thinner or thicker. */
const BASE_W = 3.2

/**
 * Full-height sheet for drawing a margin sketch: the passage on top, a paper canvas, and a pen bar
 * (pen, eraser, the four colors, undo). Pens, fingers and the mouse all draw; a pen's eraser end or
 * barrel button erases. Coordinates are kept in 1/1000 of the canvas width (see lib/sketch.ts).
 */
export function SketchEditor(p: {
  /** The passage the sketch is tied to. */
  quote: string
  /** The sketch being edited (JSON), or null for a new one. */
  initial: string | null
  initialColor: SketchColor
  onSave: (sketch: string, color: SketchColor) => void
  onDelete?: () => void
  onClose: () => void
}) {
  const start = parseSketch(p.initial)
  const [strokes, setStrokes] = useState<SketchStroke[]>(start?.s ?? [])
  const [color, setColor] = useState<SketchColor>(p.initialColor)
  const [tool, setTool] = useState<'pen' | 'eraser'>('pen')
  const [live, setLiveState] = useState<SketchStroke | null>(null)
  const liveRef = useRef<SketchStroke | null>(null)
  const setLive = (st: SketchStroke | null) => {
    liveRef.current = st
    setLiveState(st)
  }
  const boxRef = useRef<HTMLDivElement>(null)
  const drawing = useRef<{ id: number; erase: boolean } | null>(null)
  // Height of the paper in sketch units: room below the strokes, at least the box's own height
  const [boxH, setBoxH] = useState(500)

  useEffect(() => {
    const el = boxRef.current
    if (!el) return
    const update = () => setBoxH(Math.max(200, (el.clientHeight / Math.max(1, el.clientWidth)) * SKETCH_W))
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') p.onClose()
      if ((e.metaKey || e.ctrlKey) && e.key === 'z') {
        e.preventDefault()
        setStrokes((s) => s.slice(0, -1))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [p])

  const toUnits = (e: { clientX: number; clientY: number }) => {
    const r = boxRef.current!.getBoundingClientRect()
    const k = SKETCH_W / r.width
    return [Math.round((e.clientX - r.left) * k * 10) / 10, Math.round((e.clientY - r.top + boxRef.current!.scrollTop) * k * 10) / 10] as const
  }

  /** Removes the strokes passing near a point (the eraser takes whole strokes). */
  const eraseAt = (x: number, y: number) => {
    setStrokes((s) =>
      s.filter((st) => {
        for (let i = 0; i < st.p.length; i += 2) if (Math.hypot(st.p[i] - x, st.p[i + 1] - y) < 14 + st.w) return false
        return true
      }),
    )
  }

  const onDown = (e: React.PointerEvent) => {
    if (e.button !== 0 && e.button !== 5) return
    e.preventDefault()
    try {
      ;(e.currentTarget as Element).setPointerCapture(e.pointerId)
    } catch {
      /* not a live pointer */
    }
    // A pen's eraser end (button 5) or barrel button (buttons & 2 with a pen) erases
    const erase = tool === 'eraser' || e.button === 5 || (e.pointerType === 'pen' && (e.buttons & 2) !== 0)
    drawing.current = { id: e.pointerId, erase }
    const [x, y] = toUnits(e)
    if (erase) eraseAt(x, y)
    else setLive({ c: color, w: widthOf(e), p: [x, y] })
  }

  const onMove = (e: React.PointerEvent) => {
    const d = drawing.current
    if (!d || d.id !== e.pointerId) return
    const coalesced = e.nativeEvent.getCoalescedEvents?.() ?? []
    const events = coalesced.length ? coalesced : [e.nativeEvent]
    if (d.erase) {
      for (const ev of events) eraseAt(...toUnits(ev))
      return
    }
    {
      const st = liveRef.current
      if (!st) return
      const p2 = [...st.p]
      for (const ev of events) {
        const [x, y] = toUnits(ev)
        const lx = p2[p2.length - 2]
        const ly = p2[p2.length - 1]
        if (Math.hypot(x - lx, y - ly) >= 1.5) p2.push(x, y)
      }
      // Pressure: keep a running average, one width per stroke
      const w = e.pointerType === 'pen' ? st.w * 0.85 + widthOf(e) * 0.15 : st.w
      setLive({ ...st, p: p2, w })
    }
  }

  const onUp = (e: React.PointerEvent) => {
    const d = drawing.current
    if (!d || d.id !== e.pointerId) return
    drawing.current = null
    const st = liveRef.current
    if (st) setStrokes((s) => [...s, st])
    setLive(null)
  }

  const all = live ? [...strokes, live] : strokes
  const maxY = all.reduce((m, st) => {
    for (let i = 1; i < st.p.length; i += 2) m = Math.max(m, st.p[i])
    return m
  }, 0)
  const paperH = Math.max(boxH, maxY + 240)

  const save = () => {
    if (!strokes.length) {
      if (p.onDelete) p.onDelete()
      else p.onClose()
      return
    }
    const sk: Sketch = { h: maxY, s: strokes }
    p.onSave(encodeSketch(sk), strokes[strokes.length - 1]?.c ?? color)
  }

  return (
    <div className="sketch-editor" role="dialog" aria-modal="true" aria-label="Sketch">
      <div className="sketch-sheet">
        <div className="sketch-top">
          <IconButton icon="close" label="Cancel (Esc)" onClick={p.onClose} />
          <h2>Sketch</h2>
          {p.onDelete ? (
            <button type="button" className="hl-btn danger sketch-del" onClick={p.onDelete}>
              <Icon name="delete" size={20} /> Delete
            </button>
          ) : null}
          <button type="button" className="hl-btn primary sketch-save" onClick={save} disabled={!strokes.length && !p.onDelete}>
            <Icon name="check" size={20} /> Save
          </button>
        </div>
        <blockquote className="sketch-quote">{p.quote}</blockquote>
        <div className="sketch-paper" ref={boxRef} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}>
          <svg viewBox={`0 0 ${SKETCH_W} ${paperH}`} width="100%" style={{ display: 'block', height: 'auto' }}>
            {all.map((st, i) => (
              <path key={i} d={strokePath(st.p)} stroke={`var(--feeder-ink-${st.c})`} strokeWidth={st.w} fill="none" strokeLinecap="round" strokeLinejoin="round" />
            ))}
          </svg>
          {!all.length ? <div className="sketch-hint">Draw here with a pen, finger or mouse</div> : null}
        </div>
        <div className="sketch-pen" role="toolbar" aria-label="Pen">
          <IconButton icon="draw" label="Pen" active={tool === 'pen'} onClick={() => setTool('pen')} />
          <IconButton icon="eraser" label="Eraser" active={tool === 'eraser'} onClick={() => setTool('eraser')} />
          <span className="sketch-vr" />
          {HIGHLIGHT_COLORS.map((c) => (
            <button
              key={c.id}
              type="button"
              className={`sketch-swatch${color === c.id ? ' on' : ''}`}
              style={{ background: `var(--feeder-ink-${c.id})` }}
              title={c.label}
              aria-label={c.label}
              aria-pressed={color === c.id}
              onClick={() => {
                setColor(c.id)
                setTool('pen')
              }}
            />
          ))}
          <span className="sketch-vr" />
          <IconButton icon="undo" label="Undo (⌘Z)" disabled={!strokes.length} onClick={() => setStrokes((s) => s.slice(0, -1))} />
        </div>
      </div>
    </div>
  )
}

function widthOf(e: { pointerType: string; pressure: number }) {
  if (e.pointerType !== 'pen' || !e.pressure) return BASE_W
  return BASE_W * (0.45 + e.pressure * 1.1)
}
