import { describe, expect, it } from 'vitest'
import { dateBucketKey, dateLabel } from '../listquery'

// Thursday 12 Nov 2026, weeks starting Monday
const now = new Date(2026, 10, 12, 15, 0)
const key = (y: number, m: number, d: number, weekStartsOn = 1, at = now) =>
  dateBucketKey(new Date(y, m - 1, d, 9).getTime(), at, weekStartsOn)

describe('Mail-style date groups', () => {
  it('buckets relative to today', () => {
    expect(key(2026, 11, 12)).toBe('2026-11-12:today')
    expect(key(2026, 11, 14)).toBe('2026-11-12:today')
    expect(key(2026, 11, 11)).toBe('2026-11-11:yesterday')
    expect(key(2026, 11, 9)).toBe('2026-11-09:week')
    expect(key(2026, 11, 2)).toBe('2026-11-02:lastweek')
    expect(key(2026, 11, 1)).toBe('2026-11-01:month')
    expect(key(2026, 10, 31)).toBe('2026-10-01:lastmonth')
    expect(key(2026, 9, 15)).toBe('2026-09-01:m')
    expect(key(2025, 12, 15)).toBe('2025-12-01:y')
  })

  it('follows the locale week start', () => {
    expect(key(2026, 11, 8, 0)).toBe('2026-11-08:week')
    expect(key(2026, 11, 8, 1)).toBe('2026-11-02:lastweek')
  })

  it('lets last week win when it spans two months', () => {
    const tuesday = new Date(2026, 10, 3, 10)
    expect(key(2026, 11, 1, 1, tuesday)).toBe('2026-10-26:lastweek')
    expect(key(2026, 10, 25, 1, tuesday)).toBe('2026-10-01:lastmonth')
  })

  it('keys sort newest first as text', () => {
    let prev = ''
    for (let i = 0; i < 800; i++) {
      const k = dateBucketKey(new Date(2026, 10, 12 - i, 9).getTime(), now, 1)
      if (prev) expect(prev >= k).toBe(true)
      prev = k
    }
  })

  it('labels', () => {
    expect(dateLabel('2026-11-12:today')).toMatch(/^Today · /)
    expect(dateLabel('2026-11-02:lastweek')).toBe('Last week')
    expect(dateLabel('2026-11-01:month')).toBe('Earlier this month')
    expect(dateLabel('2026-10-01:lastmonth')).toBe('Last month')
    expect(dateLabel('2025-03-01:y')).toMatch(/2025/)
  })
})
