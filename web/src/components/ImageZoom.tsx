import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { IconButton } from './Icon'

const MAX = 8

type Tf = { s: number; x: number; y: number }

/**
 * A picture full screen with zoom and pan, like the Android image viewer: pinch, drag, double
 * tap (or double click) to zoom in or out, the mouse wheel, and zoom buttons. Esc closes it.
 */
export function ImageZoom({ src, label, onClose }: { src: string; label: string; onClose: () => void }) {
  const stage = useRef<HTMLDivElement>(null)
  const img = useRef<HTMLImageElement>(null)
  const [tf, setTf] = useState<Tf>({ s: 1, x: 0, y: 0 })
  const tfRef = useRef(tf)
  tfRef.current = tf

  /** Keeps the picture on screen: no panning past its edges, centred when it fits. */
  const clamp = (t: Tf): Tf => {
    const st = stage.current?.getBoundingClientRect()
    const im = img.current
    if (!st || !im?.naturalWidth || t.s <= 1) return { s: 1, x: 0, y: 0 }
    // The picture is fitted into the stage (object-fit: contain): pan within it, not its letterbox
    const fit = Math.min(st.width / im.naturalWidth, st.height / im.naturalHeight)
    const w = im.naturalWidth * fit * t.s
    const h = im.naturalHeight * fit * t.s
    const mx = Math.max(0, (w - st.width) / 2)
    const my = Math.max(0, (h - st.height) / 2)
    return { s: t.s, x: Math.min(mx, Math.max(-mx, t.x)), y: Math.min(my, Math.max(-my, t.y)) }
  }

  /** Zooms by `f` keeping the point (cx, cy) on screen still. */
  const zoomAt = (f: number, cx?: number, cy?: number) => {
    const st = stage.current?.getBoundingClientRect()
    if (!st) return
    const t = tfRef.current
    const s = Math.min(MAX, Math.max(1, t.s * f))
    const r = s / t.s
    const px = (cx ?? st.left + st.width / 2) - (st.left + st.width / 2)
    const py = (cy ?? st.top + st.height / 2) - (st.top + st.height / 2)
    setTf(clamp({ s, x: t.x * r + px * (1 - r), y: t.y * r + py * (1 - r) }))
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
      } else if (e.key === '+' || e.key === '=') zoomAt(1.5)
      else if (e.key === '-') zoomAt(1 / 1.5)
      else if (e.key === '0') setTf({ s: 1, x: 0, y: 0 })
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  })

  // Pinch and drag with pointer events (touch, pen and mouse alike)
  useEffect(() => {
    const el = stage.current
    if (!el) return
    const pts = new Map<number, { x: number; y: number }>()
    let last: { d: number; cx: number; cy: number } | null = null
    let lastTap = 0
    let moved = false
    const gesture = () => {
      const [a, b] = [...pts.values()]
      if (!b) return { d: 0, cx: a.x, cy: a.y }
      return { d: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 }
    }
    const down = (e: PointerEvent) => {
      if ((e.target as Element).closest('button')) return
      el.setPointerCapture(e.pointerId)
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY })
      last = gesture()
      if (pts.size === 1) moved = false
    }
    const move = (e: PointerEvent) => {
      if (!pts.has(e.pointerId) || !last) return
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY })
      const g = gesture()
      const t = tfRef.current
      let s = t.s
      if (pts.size > 1 && last.d > 0) s = Math.min(MAX, Math.max(1, t.s * (g.d / last.d)))
      const st = el.getBoundingClientRect()
      const r = s / t.s
      const px = last.cx - (st.left + st.width / 2)
      const py = last.cy - (st.top + st.height / 2)
      const dx = g.cx - last.cx
      const dy = g.cy - last.cy
      if (Math.abs(dx) + Math.abs(dy) > 3 || r !== 1) moved = true
      setTf(clamp({ s, x: t.x * r + px * (1 - r) + dx, y: t.y * r + py * (1 - r) + dy }))
      last = g
    }
    const up = (e: PointerEvent) => {
      if (!pts.delete(e.pointerId)) return
      last = pts.size ? gesture() : null
      if (pts.size || moved) return
      // Double tap: zoom in where tapped, or back out
      const now = Date.now()
      if (now - lastTap < 300) {
        lastTap = 0
        if (tfRef.current.s > 1) setTf({ s: 1, x: 0, y: 0 })
        else zoomAt(3, e.clientX, e.clientY)
      } else lastTap = now
    }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      zoomAt(Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.002)), e.clientX, e.clientY)
    }
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', up)
    el.addEventListener('wheel', wheel, { passive: false })
    return () => {
      el.removeEventListener('pointerdown', down)
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      el.removeEventListener('pointercancel', up)
      el.removeEventListener('wheel', wheel)
    }
  }, [])

  // On the page itself, so no reader layout (transforms, overflow) can clip it
  return createPortal(
    <div className="image-zoom" role="dialog" aria-modal="true" aria-label={label || 'Image'}>
      <header className="image-zoom-bar">
        <IconButton icon="close" label="Close image" onClick={onClose} />
        <span className="image-zoom-title">{label || 'Image'}</span>
      </header>
      <div ref={stage} className="image-zoom-stage" aria-roledescription="zoomable image" aria-valuetext={`${Math.round(tf.s * 100)}%`}>
        <img
          ref={img}
          src={src}
          alt={label}
          draggable={false}
          style={{ transform: `translate(${tf.x}px, ${tf.y}px) scale(${tf.s})` }}
        />
      </div>
      <footer className="image-zoom-tools">
        <IconButton icon="zoomOut" label="Zoom out" title="Zoom out (−)" onClick={() => zoomAt(0.5)} disabled={tf.s <= 1} />
        <IconButton icon="replay" label="Reset zoom" title="Reset zoom (0)" onClick={() => setTf({ s: 1, x: 0, y: 0 })} disabled={tf.s <= 1} />
        <IconButton icon="zoomIn" label="Zoom in" title="Zoom in (+)" onClick={() => zoomAt(2)} disabled={tf.s >= MAX} />
      </footer>
    </div>,
    document.body,
  )
}
