/** Calls to this app's own backend (server/handler.ts). */

const TOKEN_KEY = 'feeder-web-access-token'

export function getAccessToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setAccessToken(token: string) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    // ignore
  }
}

export function apiHeaders(extra: Record<string, string> = {}): Headers {
  const h = new Headers(extra)
  const token = getAccessToken()
  if (token) h.set('x-access-token', token)
  return h
}

export class HttpError extends Error {
  constructor(
    public status: number,
    public body: string,
  ) {
    super(`HTTP ${status}${body ? `: ${body.slice(0, 200)}` : ''}`)
  }
}

/** A URL that loads through the backend, usable in <img src> (the token goes in the query). */
export function proxiedUrl(url: string): string {
  const token = getAccessToken()
  return `/api/fetch?url=${encodeURIComponent(url)}${token ? `&t=${encodeURIComponent(token)}` : ''}`
}

/** Fetches any http(s) URL through the backend. */
export async function proxyFetch(url: string, signal?: AbortSignal): Promise<{ text: string; finalUrl: string; contentType: string }> {
  const res = await fetch(`/api/fetch?url=${encodeURIComponent(url)}`, { headers: apiHeaders(), signal })
  const contentType = res.headers.get('x-original-content-type') || res.headers.get('content-type') || ''
  const text = decodeBody(new Uint8Array(await res.arrayBuffer()), contentType)
  if (!res.ok) throw new HttpError(res.status, text)
  return { text, finalUrl: res.headers.get('x-final-url') ?? url, contentType }
}

/** Decodes with the charset from the header, the XML declaration or a <meta charset>, else UTF-8. */
export function decodeBody(bytes: Uint8Array, contentType: string): string {
  const head = new TextDecoder('latin1').decode(bytes.subarray(0, 2048))
  const charset =
    contentType.match(/charset=["']?([\w-]+)/i)?.[1] ??
    head.match(/<\?xml[^>]*encoding=["']([\w-]+)["']/i)?.[1] ??
    head.match(/<meta[^>]+charset=["']?([\w-]+)/i)?.[1] ??
    'utf-8'
  try {
    return new TextDecoder(charset).decode(bytes)
  } catch {
    return new TextDecoder('utf-8').decode(bytes)
  }
}

export async function health(): Promise<{ ok: boolean; tokenRequired: boolean }> {
  const res = await fetch('/api/health')
  return res.json()
}
