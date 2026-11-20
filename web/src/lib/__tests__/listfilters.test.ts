import 'fake-indexeddb/auto'
import { describe, expect, it } from 'vitest'
import type { Item } from '../db'
import { applyFilters, showCounts } from '../listquery'
import { normalizeSettings } from '../settings'

const now = Date.now()
const item = (id: string, read: number, saved: number, sortTime = now): Item =>
  ({ id, feedUrl: 'f', guid: id, title: id, link: null, contentHtml: '', snippet: '', imageUrl: null, author: null, sortTime, firstSeen: sortTime, wordCount: 100, read, readTime: null, readSynced: 0, saved }) as Item

const items = [item('unread', 0, 0), item('read', 1, 0), item('savedRead', 1, 1), item('savedUnread', 0, 1), item('old', 0, 0, now - 3 * 86_400_000)]
const ids = (list: Item[]) => list.map((i) => i.id).sort()

describe('Show tabs and Today chip', () => {
  it('Unread shows only unread items', () => {
    expect(ids(applyFilters(items, { show: 'unread', today: false }))).toEqual(['old', 'savedUnread', 'unread'])
  })
  it('All shows everything', () => {
    expect(applyFilters(items, { show: 'all', today: false })).toHaveLength(5)
  })
  it('Saved shows saved items, read or unread', () => {
    expect(ids(applyFilters(items, { show: 'saved', today: false }))).toEqual(['savedRead', 'savedUnread'])
  })
  it('Today narrows any tab to items from today', () => {
    expect(ids(applyFilters(items, { show: 'unread', today: true }))).toEqual(['savedUnread', 'unread'])
  })
  it('keeps the open article even when it no longer matches', () => {
    expect(ids(applyFilters(items, { show: 'unread', today: true }, 'read'))).toContain('read')
  })
  it('counts unread and saved', () => {
    expect(showCounts(items)).toEqual({ unread: 3, saved: 2, downloaded: 0 })
  })
})

describe('settings migration', () => {
  it('uses Unread and no Today by default', () => {
    const s = normalizeSettings(undefined)
    expect([s.show, s.today]).toEqual(['unread', false])
  })
  it('maps the old filter chips to Show', () => {
    expect(normalizeSettings({ filters: ['unread'] }).show).toBe('unread')
    expect(normalizeSettings({ filters: ['unread', 'read'] }).show).toBe('all')
    expect(normalizeSettings({ filters: ['read'] }).show).toBe('all')
    expect(normalizeSettings({ filters: [] }).show).toBe('all')
    expect(normalizeSettings({ filters: ['unread', 'saved'] }).show).toBe('saved')
    expect(normalizeSettings({ filters: ['short'] }).show).toBe('all')
  })
  it('keeps the Today chip and drops Short reads', () => {
    const s = normalizeSettings({ filters: ['today', 'short'] })
    expect(s.today).toBe(true)
    expect('filters' in s).toBe(false)
  })
  it('moves Group by Status to Date', () => {
    expect(normalizeSettings({ grouping: 'status' }).grouping).toBe('date')
    expect(normalizeSettings({ grouping: 'feed' }).grouping).toBe('feed')
  })
  it('prefers the new show setting once saved', () => {
    expect(normalizeSettings({ filters: ['saved'], show: 'all' }).show).toBe('all')
  })
})
