/**
 * The small backend for Feeder Web. It runs as a Cloudflare Pages Function in production
 * (functions/api/[[path]].ts) and as Vite middleware during development (vite.config.ts).
 *
 * It stores nothing. Two jobs:
 *  - /api/fetch?url=...  fetches a feed, article page or image page the browser can't
 *    fetch itself because of CORS.
 *  - /api/sync/...       forwards to the Feeder sync server (adds the fixed basic-auth login
 *    the Android app uses). Everything sent through here is already encrypted in the browser.
 */

export interface Env {
  /** When set, every request must carry it in the X-Access-Token header. */
  ACCESS_TOKEN?: string
  /** Defaults to Feeder's public sync server. */
  SYNC_SERVER?: string
  /** Development only: allow fetching localhost/private addresses. */
  ALLOW_PRIVATE_HOSTS?: string
}

let allowPrivate = false

export const DEFAULT_SYNC_SERVER = 'https://feeder-sync.nononsenseapps.com'

// Same fixed login as the Android app (sync/Retrofit.kt). It only keeps scanners away;
// the real protection is the sync code plus end-to-end encryption.
const SYNC_BASIC_AUTH = 'Basic ' + btoa('feeder_user:feeder_secret_1234')

const USER_AGENT = 'Mozilla/5.0 (compatible; FeederWeb/0.1; +https://github.com/spacecowboy/Feeder)'

const MAX_FETCH_BYTES = 15 * 1024 * 1024

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

/** True for hosts that must never be fetched through the proxy (local or private networks). */
export function isBlockedHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) {
    return true
  }
  if (host.includes(':')) {
    // IPv6 literal: block loopback, link-local, unique-local and v4-mapped
    return host === '::1' || host === '::' || /^(fe80|fc|fd|::ffff:)/.test(host)
  }
  const m = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/)
  if (!m) return false
  const [a, b] = [Number(m[1]), Number(m[2])]
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    a >= 224
  )
}

export function checkTarget(raw: string | null): URL | string {
  if (!raw) return 'Missing url'
  let url: URL
  try {
    url = new URL(raw)
  } catch {
    return 'Invalid url'
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return 'Only http and https are allowed'
  if (url.username || url.password) return 'Credentials in url are not allowed'
  if (!allowPrivate && isBlockedHost(url.hostname)) return 'Private addresses are not allowed'
  return url
}

/**
 * Raster images keep their type so <img src="/api/fetch?..."> works. Everything else (HTML,
 * SVG, XML, scripts) is served as plain text so a browser never renders or runs it here.
 */
export function safeContentType(original: string | null): string {
  const type = (original ?? '').split(';')[0].trim().toLowerCase()
  if (/^image\/(png|jpe?g|gif|webp|avif|bmp|x-icon|vnd\.microsoft\.icon)$/.test(type)) return type
  return 'text/plain; charset=x-user-defined'
}

async function proxyFetch(request: Request): Promise<Response> {
  const target = checkTarget(new URL(request.url).searchParams.get('url'))
  if (typeof target === 'string') return json(400, { error: target })

  let current = target
  let upstream: Response | null = null
  // Follow redirects by hand so every hop goes through the private-address check
  for (let hop = 0; hop < 6; hop++) {
    upstream = await fetch(current.toString(), {
      redirect: 'manual',
      headers: {
        'user-agent': USER_AGENT,
        accept:
          'application/rss+xml, application/atom+xml, application/feed+json, application/json, application/xml, text/xml, text/html;q=0.9, */*;q=0.8',
        'accept-language': request.headers.get('accept-language') ?? 'en',
      },
    })
    const location = upstream.headers.get('location')
    if (upstream.status >= 300 && upstream.status < 400 && location) {
      const next = checkTarget(new URL(location, current).toString())
      if (typeof next === 'string') return json(400, { error: next })
      current = next
      continue
    }
    break
  }
  if (!upstream) return json(502, { error: 'No response' })

  const length = Number(upstream.headers.get('content-length') ?? '0')
  if (length > MAX_FETCH_BYTES) return json(413, { error: 'Too large' })

  const headers = new Headers({
    'content-type': safeContentType(upstream.headers.get('content-type')),
    // The real type, for the app to read (it decodes the body itself)
    'x-original-content-type': upstream.headers.get('content-type') ?? '',
    'x-final-url': current.toString(),
    'cache-control': 'private, max-age=300',
    // Fetched content must never run as this site, even if someone opens the URL directly
    'x-content-type-options': 'nosniff',
    'content-security-policy': "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'",
  })
  return new Response(upstream.body, { status: upstream.status, headers })
}

async function proxySync(request: Request, env: Env, path: string): Promise<Response> {
  if (!/^[a-z]+(\/\d+)?$/.test(path)) return json(404, { error: 'Unknown sync path' })
  const base = (request.headers.get('x-sync-server') || env.SYNC_SERVER || DEFAULT_SYNC_SERVER).replace(/\/+$/, '')
  const server = checkTarget(base)
  if (typeof server === 'string') return json(400, { error: server })

  const incoming = new URL(request.url)
  const target = new URL(`/api/v1/${path}`, server)
  incoming.searchParams.delete('__path')
  incoming.searchParams.delete('t')
  target.search = incoming.search

  const headers = new Headers({ authorization: SYNC_BASIC_AUTH, accept: 'application/json' })
  for (const name of ['content-type', 'x-feeder-id', 'x-feeder-device-id', 'if-match']) {
    const value = request.headers.get(name)
    if (value) headers.set(name, value)
  }
  const hasBody = request.method === 'POST' || request.method === 'PUT'
  const upstream = await fetch(target.toString(), {
    method: request.method,
    headers,
    body: hasBody ? await request.text() : undefined,
  })
  const out = new Headers({ 'cache-control': 'no-store' })
  for (const name of ['content-type', 'etag']) {
    const value = upstream.headers.get(name)
    if (value) out.set(name, value)
  }
  return new Response(upstream.body, { status: upstream.status, headers: out })
}

export async function handle(request: Request, env: Env): Promise<Response> {
  allowPrivate = env.ALLOW_PRIVATE_HOSTS === '1'
  const url = new URL(request.url)
  // Vercel rewrites /api/* to one function and passes the original path in __path.
  const path = url.searchParams.get('__path') ?? url.pathname.replace(/^\/api\/?/, '')

  if (path === 'health') return json(200, { ok: true, tokenRequired: Boolean(env.ACCESS_TOKEN) })

  const token = request.headers.get('x-access-token') ?? url.searchParams.get('t')
  if (env.ACCESS_TOKEN && token !== env.ACCESS_TOKEN) {
    return json(401, { error: 'Access token required' })
  }

  try {
    if (path === 'fetch' && request.method === 'GET') return await proxyFetch(request)
    if (path.startsWith('sync/')) return await proxySync(request, env, path.slice('sync/'.length))
  } catch (e) {
    return json(502, { error: e instanceof Error ? e.message : String(e) })
  }
  return json(404, { error: 'Not found' })
}
