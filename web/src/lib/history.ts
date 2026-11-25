/**
 * History of long reads and listens: when an audiobook was played or paused (and why: the app,
 * the lock screen, a headset button, headphones disconnected, the sleep timer…), and when an
 * ebook or a ZIM site was opened or closed, each with where it was then and on which device.
 *
 * Every event is one immutable sync record, kind "history" keyed by the event id, so events from
 * all devices add up and never overwrite each other. The Android app writes the same fields
 * (model/history/ItemHistory.kt):
 *
 *   c   'audiobook' | 'ebook' | 'zim'
 *   i   the item's id (audiobook id, ebook id, ZIM book id)
 *   a   'play' | 'pause' | 'open' | 'close'
 *   r   why, when known: app, notification, media, headset, bluetooth, car, watch, sleep, end,
 *       focus, closed, background, system
 *   at  when (ms since epoch)
 *   p   the short position shown as a chip: "Ch. 32 · 01:03", "p. 41 of 310", "37%", an article
 *   s   longer context: the chapter's or the article's title
 *   f   0–1 through the item, when known
 *   x   where to go back to: audiobook "file:ms", ebook CFI, ZIM path
 *   dv, dn  the device's id and name (device.ts)
 */
import { db, type HistoryEvent } from './db'
import { thisDevice } from './device'

export type HistoryKind = 'audiobook' | 'ebook' | 'zim'
export type HistoryAction = 'play' | 'pause' | 'open' | 'close'

export interface HistoryValue {
  c: HistoryKind
  i: string
  a: HistoryAction
  r?: string
  at: number
  p?: string
  s?: string
  f?: number
  x?: string
  dv?: string
  dn?: string
}

/** Events kept on a device (the oldest go first). */
export const MAX_EVENTS = 5000
/** The same action again this soon (a double tap, a quick reopen) is not a new event. */
const REPEAT_MS = 3000

export const historyKey = (c: string, i: string) => `${c}:${i}`

export interface Where {
  p?: string
  s?: string
  f?: number
  x?: string
}

/** Notes an event here; it goes to other devices with the next sync. */
export async function recordHistory(c: HistoryKind, i: string, a: HistoryAction, where: Where = {}, r?: string, at = Date.now()): Promise<HistoryEvent | null> {
  if (!i) return null
  const item = historyKey(c, i)
  const last = await db.history.where('[item+at]').between([item, 0], [item, Infinity]).last()
  if (last && last.a === a && Math.abs(at - last.at) < REPEAT_MS) return null
  const ev: HistoryEvent = {
    id: newEventId(at),
    item,
    c,
    i,
    a,
    at,
    ...(r ? { r } : {}),
    ...cleanWhere(where),
    ...thisDevice(),
    synced: 0,
  }
  await db.history.put(ev)
  void trim()
  return ev
}

function cleanWhere(w: Where): Where {
  const out: Where = {}
  if (w.p) out.p = w.p.slice(0, 80)
  if (w.s) out.s = w.s.slice(0, 200)
  if (typeof w.f === 'number' && Number.isFinite(w.f)) out.f = Math.round(Math.max(0, Math.min(1, w.f)) * 10000) / 10000
  if (w.x) out.x = w.x.slice(0, 2000)
  return out
}

function newEventId(at: number): string {
  const rand = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID().slice(0, 13) : Math.random().toString(36).slice(2, 15)
  return `${at.toString(36)}-${rand}`
}

let trimming = false
async function trim() {
  if (trimming) return
  trimming = true
  try {
    const n = await db.history.count()
    if (n > MAX_EVENTS) {
      const old = await db.history.orderBy('at').limit(n - MAX_EVENTS).primaryKeys()
      await db.history.bulkDelete(old)
    }
  } finally {
    trimming = false
  }
}

/** An item's events, newest first. */
export function historyOf(c: HistoryKind, i: string): Promise<HistoryEvent[]> {
  const item = historyKey(c, i)
  return db.history.where('[item+at]').between([item, 0], [item, Infinity]).reverse().toArray()
}

// ---------------------------------------------------------------------------------------------
// Sync

/** The synced value of an event (same field order as the Android app). */
export function historyValue(e: HistoryEvent): HistoryValue {
  const v = { c: e.c, i: e.i, a: e.a } as HistoryValue
  if (e.r) v.r = e.r
  v.at = e.at
  if (e.p) v.p = e.p
  if (e.s) v.s = e.s
  if (typeof e.f === 'number') v.f = e.f
  if (e.x) v.x = e.x
  if (e.dv && e.dn) {
    v.dv = e.dv
    v.dn = e.dn
  }
  return v
}

const KINDS = new Set(['audiobook', 'ebook', 'zim'])
const ACTIONS = new Set(['play', 'pause', 'open', 'close'])

/** An event from another device, or null when it can't be read. */
export function eventFromValue(id: string, v: unknown, ts: number): HistoryEvent | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Partial<HistoryValue>
  if (typeof o.c !== 'string' || !KINDS.has(o.c) || typeof o.i !== 'string' || !o.i || typeof o.a !== 'string' || !ACTIONS.has(o.a)) return null
  const at = typeof o.at === 'number' && Number.isFinite(o.at) ? o.at : ts
  return {
    id,
    item: historyKey(o.c, o.i),
    c: o.c,
    i: o.i,
    a: o.a,
    at,
    ...(typeof o.r === 'string' && o.r ? { r: o.r } : {}),
    ...cleanWhere({ p: typeof o.p === 'string' ? o.p : undefined, s: typeof o.s === 'string' ? o.s : undefined, f: typeof o.f === 'number' ? o.f : undefined, x: typeof o.x === 'string' ? o.x : undefined }),
    ...(typeof o.dv === 'string' && o.dv && typeof o.dn === 'string' && o.dn ? { dv: o.dv, dn: o.dn } : {}),
    synced: 1,
  }
}

// ---------------------------------------------------------------------------------------------
// Showing it

export function actionText(a: string): string {
  return a === 'play' ? 'Played' : a === 'pause' ? 'Paused' : a === 'open' ? 'Opened' : a === 'close' ? 'Closed' : a
}

const REASONS: Record<string, string> = {
  app: 'In app',
  notification: 'Notification',
  media: 'Media keys',
  headset: 'Headset button',
  bluetooth: 'Headphones disconnected',
  car: 'Android Auto',
  watch: 'Watch',
  sleep: 'Sleep timer',
  end: 'Finished',
  focus: 'Other audio',
  closed: 'Player closed',
  background: 'Left the app',
  system: 'System',
}

/** "Bluetooth", "Sleep timer"…, or '' when there's nothing to say. */
export function reasonText(r: string | undefined): string {
  return r ? (REASONS[r] ?? '') : ''
}

/** "Today", "Yesterday", or "Mon, Nov 23" (with the year when it isn't this year). */
export function dayText(at: number, now = Date.now()): string {
  const d = new Date(at)
  const today = new Date(now)
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((start(today) - start(d)) / 86_400_000)
  if (days === 0) return 'Today'
  if (days === 1) return 'Yesterday'
  return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) })
}

export const timeText = (at: number) => new Date(at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })

/** Events grouped by day, newest first. */
export function byDay(events: HistoryEvent[], now = Date.now()): { day: string; events: HistoryEvent[] }[] {
  const out: { day: string; events: HistoryEvent[] }[] = []
  for (const e of [...events].sort((a, b) => b.at - a.at)) {
    const day = dayText(e.at, now)
    const last = out[out.length - 1]
    if (last?.day === day) last.events.push(e)
    else out.push({ day, events: [e] })
  }
  return out
}

/** "01:03", or "1:02:03" past an hour. */
export function clockText(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  const two = (n: number) => String(n).padStart(2, '0')
  return h ? `${h}:${two(m)}:${two(ss)}` : `${two(m)}:${two(ss)}`
}

/** Where an ebook is: "p. 41 of 310", or "37%". */
export function bookChip(f: number, page?: { p?: number; n?: number } | null): string {
  if (page?.p && page.n) return `p. ${page.p} of ${page.n}`
  return `${Math.round(Math.max(0, Math.min(1, f)) * 100)}%`
}

// ---------------------------------------------------------------------------------------------
// Opened and closed (ebooks, ZIM sites)

/** Away from the app at least this long counts as closing and opening again. */
export const AWAY_MS = 5 * 60_000

/**
 * Notes when a reader is opened and closed. [touch] once the place is known (and again on every
 * page turn; only the first one counts), [stop] when the reader closes. Leaving the app for
 * [AWAY_MS] or more counts as closed when it was left and opened again on coming back.
 */
export function trackOpen(c: HistoryKind, i: string, where: () => Where | null): { touch: () => void; stop: () => void } {
  let open = false
  let hiddenAt = 0
  let hiddenWhere: Where | null = null
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') {
      hiddenAt = Date.now()
      hiddenWhere = where()
      return
    }
    const away = hiddenAt
    hiddenAt = 0
    if (!open || !away || Date.now() - away < AWAY_MS) return
    void recordHistory(c, i, 'close', hiddenWhere ?? {}, 'background', away).then(() => recordHistory(c, i, 'open', where() ?? hiddenWhere ?? {}))
  }
  const onPageHide = () => {
    if (open) void recordHistory(c, i, 'close', where() ?? {})
    open = false
  }
  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('pagehide', onPageHide)
  return {
    touch() {
      if (open) return
      const w = where()
      if (!w) return
      open = true
      void recordHistory(c, i, 'open', w)
    },
    stop() {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', onPageHide)
      if (!open) return
      open = false
      // Closed while away: it was closed when the app was left
      void recordHistory(c, i, 'close', (hiddenAt ? hiddenWhere : where()) ?? {}, hiddenAt ? 'background' : undefined, hiddenAt || Date.now())
    },
  }
}
