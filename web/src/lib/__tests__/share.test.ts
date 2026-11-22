import { describe, expect, it } from 'vitest'
import { findLink, normalizeLink, siteOf } from '../share'

describe('shared links', () => {
  it('finds the link in shared text', () => {
    expect(findLink('Look at this https://example.com/post?a=1, nice')).toBe('https://example.com/post?a=1')
    expect(findLink('example.com/post')).toBe('https://example.com/post')
    expect(findLink('just words')).toBeNull()
  })
  it('drops tracking parameters and fragments', () => {
    expect(normalizeLink('https://example.com/p?utm_source=x&id=3#top')).toBe('https://example.com/p?id=3')
  })
  it('turns every YouTube link form into one watch URL', () => {
    const w = normalizeLink('https://youtu.be/dQw4w9WgXcQ?si=abc')
    expect(w).toBe(normalizeLink('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10'))
    expect(w).toContain('dQw4w9WgXcQ')
  })
  it('names the site', () => {
    expect(siteOf('https://www.paulgraham.com/best.html')).toBe('paulgraham.com')
  })
})

describe('same keys as Android (SharedLinksTest.kt)', () => {
  it('normalizes the same way', () => {
    expect(normalizeLink('https://youtu.be/dQw4w9WgXcQ?si=abc')).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ')
    expect(normalizeLink('https://example.com')).toBe('https://example.com/')
  })
})
