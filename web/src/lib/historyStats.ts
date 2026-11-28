/**
 * The small graphs at the top of an item's History (History.tsx): minutes a day over the last two
 * weeks, the streak, and how far through it is over time, worked out only from the plays, pauses,
 * opens and closes already in history.ts. The Android app does the same in
 * model/history/HistoryStats.kt.
 */
import type { HistoryEvent } from './db'

export const DAYS = 14
/** A play or open with no pause or close after it (the app was killed) counts at most this long */
export const MAX_SESSION_MS = 4 * 3600_000

export interface ProgressPoint {
  at: number
  f: number
}

export interface HistoryGraph {
  /** Start of each of the last [DAYS] days, oldest first; the last one is today */
  days: number[]
  /** Minutes on each of those days */
  minutes: number[]
  /** Minutes over the last 7 days, today included */
  week: number
  /** Days in a row with some time, up to today (or yesterday, when nothing yet today) */
  streak: number
  /** How far through it was over the last [DAYS] days, oldest first (empty when not known) */
  progress: ProgressPoint[]
  /** How far through it is now, when known */
  f: number | null
  /** Minutes a day, on average over the [DAYS] days */
  minutesPerDay: number
  /** Gained a day over the [DAYS] days (0–1), when it went forward */
  fPerDay: number | null
  /** Days to the end at that pace */
  daysLeft: number | null
  /** The page and page count from the last "p. 41 of 310" chip (ebooks) */
  page: { p: number; n: number } | null
}

/** The start of the day [t] is in, [back] days before that (local time). */
export function dayStart(t: number, back = 0): number {
  const d = new Date(t)
  d.setHours(0, 0, 0, 0)
  if (back) d.setDate(d.getDate() - back)
  return d.getTime()
}

/** Each play (or open) with the event after it, as [start, end]. */
export function sessions(events: HistoryEvent[], now = Date.now()): [number, number][] {
  const sorted = [...events].sort((a, b) => a.at - b.at)
  const out: [number, number][] = []
  sorted.forEach((e, k) => {
    if (e.a !== 'play' && e.a !== 'open') return
    const next = sorted[k + 1]
    // Still going: count up to now
    const end = next ? next.at : now
    const len = Math.min(Math.max(0, end - e.at), MAX_SESSION_MS)
    if (len > 0) out.push([e.at, e.at + len])
  })
  return out
}

/** Minutes on each of the last [DAYS] days, a session over midnight split between both. */
export function minutesByDay(spans: [number, number][], now = Date.now()): { days: number[]; minutes: number[] } {
  const days = Array.from({ length: DAYS }, (_, k) => dayStart(now, DAYS - 1 - k))
  const ends = days.map((_, k) => (k + 1 < DAYS ? days[k + 1] : dayStart(now, -1)))
  const ms = days.map(() => 0)
  for (const [a, b] of spans) {
    days.forEach((d, k) => {
      const overlap = Math.min(b, ends[k]) - Math.max(a, d)
      if (overlap > 0) ms[k] += overlap
    })
  }
  return { days, minutes: ms.map((m) => Math.round(m / 60_000)) }
}

/** Start of each hour of today (local time), midnight first; 23 or 25 on clock-change days. */
export function hoursOfToday(now = Date.now()): number[] {
  const out: number[] = []
  const end = dayStart(now, -1)
  const d = new Date(dayStart(now))
  while (d.getTime() < end) {
    out.push(d.getTime())
    d.setTime(d.getTime() + 3600_000)
  }
  return out
}

/** Minutes in each hour of today, a session over the hour split between both. */
export function minutesByHour(spans: [number, number][], now = Date.now()): { hours: number[]; minutes: number[] } {
  const hours = hoursOfToday(now)
  const ms = hours.map(() => 0)
  for (const [a, b] of spans) {
    hours.forEach((h, k) => {
      const overlap = Math.min(b, h + 3600_000) - Math.max(a, h)
      if (overlap > 0) ms[k] += overlap
    })
  }
  return { hours, minutes: ms.map((m) => Math.round(m / 60_000)) }
}

/** How many of [times] fall in each hour of today (ZIM pages visited). */
export function countByHour(times: number[], now = Date.now()): number[] {
  const hours = hoursOfToday(now)
  const out = hours.map(() => 0)
  for (const t of times) {
    const k = Math.floor((t - hours[0]) / 3600_000)
    if (k >= 0 && k < out.length) out[k]++
  }
  return out
}

/** The hour of today [now] is in, as an index into [hoursOfToday]. */
export function hourIndex(now = Date.now()): number {
  return Math.max(0, Math.floor((now - dayStart(now)) / 3600_000))
}

/** How far through it was from [from] on: the last known point before it, then each one after. */
export function progressFrom(events: HistoryEvent[], from: number): ProgressPoint[] {
  const known = [...events].sort((a, b) => a.at - b.at).filter((e) => typeof e.f === 'number' && Number.isFinite(e.f))
  const before = known.filter((e) => e.at < from).pop()
  const out: ProgressPoint[] = []
  if (before) out.push({ at: from, f: before.f! })
  for (const e of known) if (e.at >= from) out.push({ at: e.at, f: e.f! })
  return out
}

/** Days in a row with some minutes, ending today (or yesterday when nothing yet today). */
export function streakOf(minutes: number[]): number {
  let k = minutes.length - 1
  if (k >= 0 && !minutes[k]) k--
  let n = 0
  for (; k >= 0 && minutes[k] > 0; k--) n++
  return n
}

const PAGE = /^p\. (\d+) of (\d+)/

export function graphOf(events: HistoryEvent[], now = Date.now()): HistoryGraph {
  const { days, minutes } = minutesByDay(sessions(events, now), now)
  const week = minutes.slice(-7).reduce((s, m) => s + m, 0)
  const from = days[0]
  const sorted = [...events].sort((a, b) => a.at - b.at)
  const known = sorted.filter((e) => typeof e.f === 'number' && Number.isFinite(e.f))
  const progress = progressFrom(events, from)
  const f = known.length ? known[known.length - 1].f! : null
  const gain = progress.length > 1 ? progress[progress.length - 1].f - progress[0].f : 0
  const fPerDay = gain > 0.001 ? gain / DAYS : null
  const daysLeft = fPerDay && f !== null && f < 0.999 ? Math.max(1, Math.ceil((1 - f) / fPerDay - 1e-9)) : null
  let page: HistoryGraph['page'] = null
  for (let k = sorted.length - 1; k >= 0 && !page; k--) {
    const m = PAGE.exec(sorted[k].p ?? '')
    if (m) page = { p: Number(m[1]), n: Number(m[2]) }
  }
  return {
    days,
    minutes,
    week,
    streak: streakOf(minutes),
    progress,
    f,
    minutesPerDay: Math.round(minutes.reduce((s, m) => s + m, 0) / DAYS),
    fPerDay,
    daysLeft,
    page,
  }
}

/** How many of [times] fall on each of the last [DAYS] days (ZIM pages visited). */
export function countByDay(times: number[], now = Date.now()): number[] {
  const days = Array.from({ length: DAYS }, (_, k) => dayStart(now, DAYS - 1 - k))
  const end = dayStart(now, -1)
  const out = days.map(() => 0)
  for (const t of times) {
    if (t < days[0] || t >= end) continue
    let k = DAYS - 1
    while (t < days[k]) k--
    out[k]++
  }
  return out
}

/** "45 min", "2 h 10 min", "3 h". */
export function minutesText(m: number): string {
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  const r = m % 60
  return r ? `${h} h ${r} min` : `${h} h`
}

/** "9 days" / "1 day". */
export function daysText(n: number): string {
  return n === 1 ? '1 day' : `${n} days`
}

/** The chapter at [f] through the book: its index and how far into it. */
export function chapterAt(chapters: { bookMs: number; lengthMs: number }[], bookLengthMs: number, f: number): { k: number; into: number } | null {
  if (!chapters.length || !bookLengthMs) return null
  const at = f * bookLengthMs
  let k = 0
  for (let j = 0; j < chapters.length; j++) if (chapters[j].bookMs <= at) k = j
  const c = chapters[k]
  const len = c.lengthMs || (chapters[k + 1]?.bookMs ?? bookLengthMs) - c.bookMs
  return { k, into: len > 0 ? Math.max(0, Math.min(1, (at - c.bookMs) / len)) : 0 }
}
