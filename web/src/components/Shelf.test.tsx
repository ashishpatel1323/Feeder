import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { Shelf, type ShelfItem } from './Shelf'

const item = (key: string, where: ShelfItem['where']): ShelfItem => ({ key, title: `Book ${key}`, cover: null, coverIcon: 'headphones', where, size: 1, onOpen: () => undefined, actions: [] })

describe('Shelf', () => {
  it('shows everything when the In Dropbox filter is on but nothing is only in Dropbox any more', () => {
    // The chips are hidden then, so the old filter must not hide the books on this device
    const html = renderToStaticMarkup(<Shelf items={[item('a', 'device'), item('b', 'both')]} show="cloud" view="list" emptyDevice="none here" />)
    expect(html).toContain('Book a')
    expect(html).toContain('Book b')
  })

  it('still filters while something is only in Dropbox', () => {
    const html = renderToStaticMarkup(<Shelf items={[item('a', 'device'), item('c', 'cloud')]} show="cloud" view="list" emptyDevice="none here" />)
    expect(html).not.toContain('Book a')
    expect(html).toContain('Book c')
  })
})
