import { describe, expect, it } from 'vitest'
import type { Item } from '../db'
import { createItemReuser } from '../itemreuse'

// A fresh read from IndexedDB: new objects, arrays and strings every time
function read(id: string, p: Partial<Item> = {}): Item {
  return { id, feedUrl: 'f', guid: id, title: 't' + id, link: null, contentHtml: ['<p>', 'long text ', id, '</p>'].join(''), snippet: '', imageUrl: null, author: null, sortTime: 1, firstSeen: 1, wordCount: 10, read: 0, readTime: null, readSynced: 1, saved: 0, offlineImages: ['a.jpg'], ...p }
}

describe('createItemReuser', () => {
  it('keeps the same objects for articles that did not change', () => {
    const reuse = createItemReuser()
    const first = reuse([read('1'), read('2')])
    const second = reuse([read('1'), read('2', { read: 1 })])
    expect(second[0]).toBe(first[0])
    expect(second[1]).not.toBe(first[1])
    expect(second[1].read).toBe(1)
  })

  it('gives back the last list when nothing changed', () => {
    const reuse = createItemReuser()
    const first = reuse([read('1'), read('2')])
    expect(reuse([read('1'), read('2')])).toBe(first)
  })

  it('makes a new list when articles are added, removed or reordered', () => {
    const reuse = createItemReuser()
    const first = reuse([read('1'), read('2')])
    const added = reuse([read('1'), read('2'), read('3')])
    expect(added).not.toBe(first)
    expect(added[0]).toBe(first[0])
    const removed = reuse([read('2')])
    expect(removed).toEqual([first[1]])
    expect(removed[0]).toBe(first[1])
    const back = reuse([read('3'), read('2')])
    expect(back[1]).toBe(first[1])
  })

  it('notices a changed list field or a field that came or went', () => {
    const reuse = createItemReuser()
    const first = reuse([read('1')])
    expect(reuse([read('1', { offlineImages: ['a.jpg', 'b.jpg'] })])[0]).not.toBe(first[0])
    const withFull = reuse([read('1', { fullHtml: '<p>full</p>' })])
    expect(withFull[0].fullHtml).toBe('<p>full</p>')
    const without = reuse([read('1')])
    expect(without[0].fullHtml).toBeUndefined()
  })
})
