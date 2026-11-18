import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'

/** Scroll speed per level in CSS pixels per second; level 4 is roughly an unhurried reading pace. */
export const AUTO_SCROLL_SPEEDS = [6, 9, 12, 15, 19, 24, 30, 38, 48, 60]
const LEVEL_KEY = 'feeder.autoScrollLevel'
const DEFAULT_LEVEL = 4

function loadLevel(): number {
  try {
    const v = Number(localStorage.getItem(LEVEL_KEY))
    if (v >= 1 && v <= AUTO_SCROLL_SPEEDS.length) return Math.round(v)
  } catch {
    // storage blocked
  }
  return DEFAULT_LEVEL
}

export interface AutoScroll {
  active: boolean
  playing: boolean
  level: number
  start: () => void
  stop: () => void
  toggle: () => void
  setPlaying: (playing: boolean) => void
  changeLevel: (delta: number) => void
}

/**
 * Hands-free reading: scrolls [ref] slowly at the chosen speed. A wheel, swipe or scroll key
 * takes over for a moment, then auto scroll carries on from wherever the reader left it.
 */
export function useAutoScroll(ref: RefObject<HTMLElement | null>): AutoScroll {
  const [active, setActive] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [level, setLevel] = useState(loadLevel)
  const holdUntil = useRef(0)

  useEffect(() => {
    const el = ref.current
    if (!el || !active) return
    const hold = () => (holdUntil.current = performance.now() + 900)
    const onKey = (e: KeyboardEvent) => {
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'].includes(e.key)) hold()
    }
    const opts: AddEventListenerOptions = { passive: true }
    el.addEventListener('wheel', hold, opts)
    el.addEventListener('touchstart', hold, opts)
    el.addEventListener('touchmove', hold, opts)
    el.addEventListener('pointerdown', hold, opts)
    window.addEventListener('keydown', onKey)
    return () => {
      el.removeEventListener('wheel', hold, opts)
      el.removeEventListener('touchstart', hold, opts)
      el.removeEventListener('touchmove', hold, opts)
      el.removeEventListener('pointerdown', hold, opts)
      window.removeEventListener('keydown', onKey)
    }
  }, [ref, active])

  useEffect(() => {
    if (!active || !playing) return
    const speed = AUTO_SCROLL_SPEEDS[level - 1]
    let frame = 0
    let last = performance.now()
    // Browsers round scrollTop, so slow speeds collect fractions of a pixel first
    let carry = 0
    const tick = (now: number) => {
      const el = ref.current
      const dt = Math.min(0.1, (now - last) / 1000)
      last = now
      if (el && now >= holdUntil.current) {
        carry += speed * dt
        const step = Math.floor(carry)
        if (step >= 1) {
          carry -= step
          const before = el.scrollTop
          el.scrollTop = before + step
          if (el.scrollTop <= before && el.scrollHeight - el.clientHeight - before < 2) {
            // Reached the end
            setPlaying(false)
            return
          }
        }
      } else {
        carry = 0
      }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [active, playing, level, ref])

  // Keep the screen awake while reading hands-free, where the browser allows it
  useEffect(() => {
    if (!active || !('wakeLock' in navigator)) return
    let lock: WakeLockSentinel | null = null
    let cancelled = false
    navigator.wakeLock
      .request('screen')
      .then((l) => {
        if (cancelled) void l.release()
        else lock = l
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
      lock?.release().catch(() => undefined)
    }
  }, [active])

  const start = useCallback(() => {
    setActive(true)
    setPlaying(true)
  }, [])
  const stop = useCallback(() => {
    setActive(false)
    setPlaying(false)
  }, [])
  const changeLevel = useCallback((delta: number) => {
    setLevel((l) => {
      const next = Math.min(AUTO_SCROLL_SPEEDS.length, Math.max(1, l + delta))
      try {
        localStorage.setItem(LEVEL_KEY, String(next))
      } catch {
        // storage blocked
      }
      return next
    })
  }, [])

  return {
    active,
    playing,
    level,
    start,
    stop,
    toggle: () => (active ? stop() : start()),
    setPlaying: (p: boolean) => {
      if (p) holdUntil.current = 0
      setPlaying(p)
    },
    changeLevel,
  }
}
