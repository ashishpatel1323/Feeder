import { describe, expect, it } from 'vitest'
import { fullTextErrorText } from '../fetchError'

describe('fullTextErrorText', () => {
  it('never shows the site HTML', () => {
    const t = fullTextErrorText('HTTP 403: <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>')
    expect(t).toBe('This site blocks apps from reading its full text.')
    expect(t).not.toContain('<')
  })
  it('names timeouts and missing pages', () => {
    expect(fullTextErrorText('The operation was aborted due to timeout')).toContain('too long')
    expect(fullTextErrorText('HTTP 404: Not Found')).toContain('no longer')
  })
  it('strips tags from anything else and keeps it short', () => {
    const t = fullTextErrorText(`weird <b>thing</b> ${'x'.repeat(200)}`)
    expect(t).not.toContain('<b>')
    expect(t.length).toBeLessThan(120)
  })
})
