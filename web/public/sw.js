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
