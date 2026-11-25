// App shell for offline starts, plus images saved by "Download for offline" (src/lib/offline.ts).
// Never caches /api calls. Registered as /sw.js?dev=1 by `npm run dev`: then only images are served.
const CACHE = 'feeder-web-v2'
const IMAGES = 'feeder-offline-images'
const DEV = new URL(self.location.href).searchParams.has('dev')

self.addEventListener('install', (e) => {
  if (!DEV) e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/', '/index.html', '/manifest.webmanifest', '/icon.svg'])))
  self.skipWaiting()
})
self.addEventListener('activate', (e) => {
  // Only old app-shell caches go: offline images and videos, and the voice model, stay
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('feeder-web-') && k !== CACHE).map((k) => caches.delete(k)))),
  )
  self.clients.claim()
})
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return
  const url = new URL(e.request.url)

  // Images: the offline copy first (keyed by the original image URL), else the network
  if (e.request.destination === 'image' && (url.origin !== location.origin || url.pathname === '/api/fetch')) {
    const key = url.pathname === '/api/fetch' && url.origin === location.origin ? url.searchParams.get('url') : e.request.url
    if (!key) return
    e.respondWith(
      caches
        .open(IMAGES)
        .then((c) => c.match(key))
        .then((hit) => hit || fetch(e.request)),
    )
    return
  }

  // ZIM books (offline websites) are read from the device by an open Feeder window
  if (url.origin === location.origin && url.pathname.startsWith('/zim/')) {
    e.respondWith(zimResponse(e.request, url))
    return
  }

  if (DEV || url.origin !== location.origin || url.pathname.startsWith('/api/')) return
  // Network first, cache as fallback (so new deploys show up right away)
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put(e.request, copy))
        }
        return res
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('/index.html'))),
  )
})

/** Asks a Feeder window (src/lib/zim/library.ts) for a /zim/<book>/<path> item. */
async function zimResponse(request, url) {
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  // The reader's own iframe is a /zim/ page: ask the app windows instead
  const apps = windows.filter((c) => !new URL(c.url).pathname.startsWith('/zim/'))
  for (const client of apps) {
    const res = await askWindow(client, { type: 'zim-fetch', path: url.pathname, range: request.headers.get('range') })
    if (!res) continue
    if (res.status === 302) return Response.redirect(new URL(res.headers.Location, url).href, 302)
    return new Response(res.status === 416 || res.status === 302 ? null : res.body, { status: res.status, headers: res.headers })
  }
  return new Response('Open this book from the ZIM tab in Feeder.', { status: 503, headers: { 'Content-Type': 'text/plain' } })
}

function askWindow(client, message) {
  return new Promise((resolve) => {
    const channel = new MessageChannel()
    const timer = setTimeout(() => resolve(null), 15000)
    channel.port1.onmessage = (m) => {
      clearTimeout(timer)
      resolve(m.data)
    }
    client.postMessage(message, [channel.port2])
  })
}
