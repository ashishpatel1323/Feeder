import { describe, expect, it } from 'vitest'
import { newFeed, placeInOrder, sortFolder } from '../db'

const f = (url: string, title: string) => newFeed(url, { title })

describe('manual feed order', () => {
  it('sorts by position, then the rest alphabetically', () => {
    const feeds = [f('a', 'Alpha'), f('b', 'Beta'), f('c', 'Charlie'), f('d', 'Delta')]
    expect(sortFolder(feeds, ['c', 'a']).map((x) => x.url)).toEqual(['c', 'a', 'b', 'd'])
  })

  it('moves a feed up inside its folder', () => {
    expect(placeInOrder([], ['a', 'b', 'c'], 'c', 'a')).toEqual(['c', 'a', 'b'])
  })

  it('moves a feed to the end', () => {
    expect(placeInOrder(['x', 'a', 'b', 'c'], ['a', 'b', 'c'], 'a', null)).toEqual(['x', 'b', 'c', 'a'])
  })

  it('inserts a feed coming from another folder', () => {
    expect(placeInOrder(['n', 'a', 'b'], ['a', 'b'], 'n', 'b')).toEqual(['a', 'n', 'b'])
  })
})
