import { describe, expect, it } from 'vitest'
import { byteLimit, checkTarget, handle, isBlockedHost, proxyCacheControl, safeContentType } from '../../../server/handler'

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

describe('backend routing', () => {
  it('takes the path from __path when Vercel rewrote the request', async () => {
    const health = await handle(new Request('https://x.vercel.app/api/proxy?__path=health'), { ACCESS_TOKEN: 'k' })
    expect(await health.json()).toEqual({ ok: true, tokenRequired: true })
    const locked = await handle(new Request('https://x.vercel.app/api/proxy?__path=sync/devices'), { ACCESS_TOKEN: 'k' })
    expect(locked.status).toBe(401)
    const plain = await handle(new Request('https://x.pages.dev/api/health'), {})
    expect(await plain.json()).toEqual({ ok: true, tokenRequired: false })
  })
})

describe('byteLimit', () => {
  it('fails a body that grows past the limit even without content-length', async () => {
    const big = new Response(new Uint8Array(20)).body!.pipeThrough(byteLimit(10))
    await expect(new Response(big).arrayBuffer()).rejects.toThrow()
    const ok = new Response(new Uint8Array(5)).body!.pipeThrough(byteLimit(10))
    expect((await new Response(ok).arrayBuffer()).byteLength).toBe(5)
  })
})

describe('proxyCacheControl', () => {
  it('keeps pictures for <img> a week, and a missing one a day', () => {
    expect(proxyCacheControl(200, 'image/png', 'image')).toBe('private, max-age=604800')
    expect(proxyCacheControl(404, 'text/plain; charset=x-user-defined', 'image')).toBe('private, max-age=86400')
  })
  it('keeps feeds and pages the app fetches itself 5 minutes', () => {
    expect(proxyCacheControl(200, 'text/plain; charset=x-user-defined', 'empty')).toBe('private, max-age=300')
    expect(proxyCacheControl(404, 'text/plain; charset=x-user-defined', null)).toBe('private, max-age=300')
    expect(proxyCacheControl(200, 'image/png', null)).toBe('private, max-age=300')
  })
})
