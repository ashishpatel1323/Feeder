import { describe, expect, it } from 'vitest'
import type { HistoryEvent } from '../db'
import { chapterAt, countByDay, countByHour, dayStart, graphOf, hourIndex, MAX_SESSION_MS, minutesByHour, minutesText, progressFrom, sessions, streakOf } from '../historyStats'

const NOW = new Date(2026, 10, 27, 21, 0).getTime()
const at = (back: number, h: number, m = 0) => {
  const d = new Date(dayStart(NOW, back))
  d.setHours(h, m)
  return d.getTime()
}
let n = 0
const ev = (a: HistoryEvent['a'], t: number, more: Partial<HistoryEvent> = {}): HistoryEvent => ({ id: `e${n++}`, item: 'audiobook:b', c: 'audiobook', i: 'b', a, at: t, synced: 0, ...more })

describe('history graphs', () => {
  it('pairs each play with the event after it', () => {
    const s = sessions([ev('pause', at(0, 20, 30)), ev('play', at(0, 20)), ev('play', at(0, 21)), ev('pause', at(0, 21, 10))], NOW)
    expect(s.map(([a, b]) => (b - a) / 60_000)).toEqual([30, 10])
  })

  it('counts a play still going up to now, and caps one with no pause', () => {
    expect(sessions([ev('play', at(0, 20, 45))], NOW)[0][1]).toBe(NOW)
    const long = sessions([ev('play', at(1, 8)), ev('pause', at(0, 8))], NOW)[0]
    expect(long[1] - long[0]).toBe(MAX_SESSION_MS)
  })

  it('adds minutes per day, splits over midnight, and works out the streak', () => {
    const g = graphOf([ev('play', at(1, 23, 30)), ev('pause', at(0, 0, 20)), ev('open', at(3, 9)), ev('close', at(3, 9, 15))], NOW)
    expect(g.minutes.slice(-4)).toEqual([15, 0, 30, 20])
    expect(g.week).toBe(65)
    expect(g.streak).toBe(2)
    expect(streakOf([1, 0, 2, 3, 0])).toBe(2)
  })

  it('follows progress from before the window and estimates days left', () => {
    const g = graphOf([ev('pause', at(20, 9), { f: 0.2 }), ev('pause', at(5, 9), { f: 0.3 }), ev('pause', at(0, 9), { f: 0.48, p: 'p. 48 of 100' })], NOW)
    expect(g.progress.map((p) => p.f)).toEqual([0.2, 0.3, 0.48])
    expect(g.f).toBe(0.48)
    expect(g.daysLeft).toBe(26)
    expect(g.page).toEqual({ p: 48, n: 100 })
  })

  it('has no pace without progress', () => {
    const g = graphOf([ev('open', at(0, 9)), ev('close', at(0, 10))], NOW)
    expect(g.f).toBeNull()
    expect(g.daysLeft).toBeNull()
    expect(g.minutesPerDay).toBe(4)
  })

  it('counts pages per day and finds the chapter', () => {
    expect(countByDay([at(0, 9), at(0, 10), at(2, 9), at(30, 9)], NOW).slice(-3)).toEqual([1, 0, 2])
    expect(chapterAt([{ bookMs: 0, lengthMs: 100 }, { bookMs: 100, lengthMs: 100 }], 200, 0.75)).toEqual({ k: 1, into: 0.5 })
    expect(minutesText(130)).toBe('2 h 10 min')
  })
})

describe('today by hour', () => {
  it('splits minutes into today’s hours and leaves later hours empty', () => {
    const spans = sessions([ev('play', at(1, 23, 40)), ev('pause', at(0, 0, 30)), ev('play', at(0, 15, 50)), ev('pause', at(0, 16, 20))], NOW)
    const { hours, minutes } = minutesByHour(spans, NOW)
    expect(hours.length).toBe(24)
    expect(minutes[0]).toBe(30)
    expect(minutes[15]).toBe(10)
    expect(minutes[16]).toBe(20)
    expect(minutes[22]).toBe(0)
    expect(hourIndex(NOW)).toBe(21)
  })

  it('counts pages per hour and follows progress from midnight', () => {
    expect(countByHour([at(0, 9, 5), at(0, 9, 50), at(1, 9)], NOW)[9]).toBe(2)
    const p = progressFrom([ev('pause', at(2, 9), { f: 0.5 }), ev('pause', at(0, 10), { f: 0.6 })], dayStart(NOW))
    expect(p.map((x) => x.f)).toEqual([0.5, 0.6])
    expect(p[0].at).toBe(dayStart(NOW))
  })
})
