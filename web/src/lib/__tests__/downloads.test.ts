import { describe, expect, it } from 'vitest'
import { formatDuration, moveInOrder, orderDownloads } from '../downloads'

describe('downloads playlist order', () => {
  it('keeps the saved order and adds new videos at the end, oldest first', () => {
    const at: Record<string, number> = { a: 10, b: 20, c: 30, d: 5, e: 50 }
    expect(orderDownloads(['c', 'a', 'gone'], ['a', 'b', 'c', 'd', 'e'], (id) => at[id])).toEqual(['c', 'a', 'd', 'b', 'e'])
  })

  it('moves a video onto another one, or by a step', () => {
    expect(moveInOrder(['a', 'b', 'c', 'd'], 'a', { targetId: 'c' })).toEqual(['b', 'c', 'a', 'd'])
    expect(moveInOrder(['a', 'b', 'c', 'd'], 'd', { targetId: 'a' })).toEqual(['d', 'a', 'b', 'c'])
    expect(moveInOrder(['a', 'b', 'c'], 'b', { by: -1 })).toEqual(['b', 'a', 'c'])
    expect(moveInOrder(['a', 'b', 'c'], 'c', { by: 1 })).toEqual(['a', 'b', 'c'])
  })

  it('formats lengths like a video player', () => {
    expect(formatDuration(1905)).toBe('31:45')
    expect(formatDuration(62)).toBe('1:02')
    expect(formatDuration(3723)).toBe('1:02:03')
  })
})
