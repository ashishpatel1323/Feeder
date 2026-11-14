import { describe, expect, it } from 'vitest'
import { checkTarget, isBlockedHost, safeContentType } from '../../../server/handler'

describe('backend fetch guard', () => {
  it('never serves fetched HTML, SVG or scripts as themselves', () => {
    expect(safeContentType('text/html; charset=utf-8')).toMatch(/^text\/plain/)
    expect(safeContentType('image/svg+xml')).toMatch(/^text\/plain/)
    expect(safeContentType('application/javascript')).toMatch(/^text\/plain/)
    expect(safeContentType('application/rss+xml')).toMatch(/^text\/plain/)
    expect(safeContentType('image/png')).toBe('image/png')
    expect(safeContentType('image/jpeg; foo=bar')).toBe('image/jpeg')
  })

  it('blocks private addresses', () => {
    for (const h of ['localhost', '127.0.0.1', '10.1.2.3', '192.168.1.5', '172.20.0.1', '169.254.1.1', '[::1]', 'printer.local'])
      expect(isBlockedHost(h)).toBe(true)
    expect(isBlockedHost('example.com')).toBe(false)
    expect(typeof checkTarget('file:///etc/passwd')).toBe('string')
    expect(typeof checkTarget('https://example.com/feed')).toBe('object')
  })
})
