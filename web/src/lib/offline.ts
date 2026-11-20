/**
 * Offline reading: a one-time "Download for offline" job (Settings) that saves the full
 * text and images of unread articles, and YouTube videos saved one by one with yt-dlp
 * on the local server (server/ytdl.ts).
 *
 * Articles already live in IndexedDB, and read marks made offline wait in the sync queue,
 * so this only adds what the network would otherwise fetch while reading: full text
 * (Item.fullHtml), images (Cache Storage, served by sw.js) and videos (Cache Storage).
 */
import { useEffect, useState } from 'react'
import { apiHeaders, getAccessToken } from './api'
import { db, opensInBrowser, type Item } from './db'
import { loadFullText } from './extras'
import { youtubeVideoId } from './youtube'

export const IMAGE_CACHE = 'feeder-offline-images'
export const VIDEO_CACHE = 'feeder-offline-videos'
const MAX_IMAGES_PER_ARTICLE = 30
const MAX_IMAGE_BYTES = 8 * 1024 * 1024
const CONCURRENCY = 4
/** Offline copies of read, unstarred articles are dropped this long after reading. */
const KEEP_READ_MS = 7 * 86_400_000

const hasCaches = () => typeof caches !== 'undefined'

// ---------------------------------------------------------------------------------------------
// Online / offline

export function useOnline(): boolean {
  const [online, setOnline] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine))
  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])
  return online
}

// ---------------------------------------------------------------------------------------------
// A tiny store, so a running job survives closing Settings or changing articles

function store<T>(initial: T) {
  let value = initial
  const subs = new Set<(v: T) => void>()
  return {
    get: () => value,
    set(v: T) {
      value = v
      subs.forEach((f) => f(v))
    },
    subscribe(f: (v: T) => void) {
      subs.add(f)
      return () => void subs.delete(f)
    },
  }
}

function useStore<T>(s: ReturnType<typeof store<T>>): T {
  const [v, setV] = useState(s.get())
  useEffect(() => s.subscribe(setV), [s])
  return v
}

// ---------------------------------------------------------------------------------------------
// Download unread articles

export interface OfflineJob {
  running: boolean
  done: number
  total: number
  failed: number
  images: number
  message: string
}

const IDLE: OfflineJob = { running: false, done: 0, total: 0, failed: 0, images: 0, message: '' }
const job = store<OfflineJob>(IDLE)
let abort: AbortController | null = null

export const useOfflineJob = () => useStore(job)

export interface DownloadOptions {
  /** Newest unread articles to save; 0 = all of them. */
  limit: number
  images: boolean
}

/** Image URLs in an article: inline images (already resolved by sanitize) and the thumbnail. */
export function articleImageUrls(item: Pick<Item, 'fullHtml' | 'contentHtml' | 'imageUrl' | 'link'>): string[] {
  const urls = new Set<string>()
  if (item.imageUrl) urls.add(item.imageUrl)
  for (const html of [item.fullHtml, item.contentHtml]) {
    if (!html) continue
    const doc = new DOMParser().parseFromString(html, 'text/html')
    doc.querySelectorAll('img[src]').forEach((img) => {
      const src = img.getAttribute('src') ?? ''
      try {
        const abs = new URL(src, item.link ?? undefined).toString()
        if (/^https?:/i.test(abs)) urls.add(abs)
      } catch {
        // skip
      }
    })
  }
  return [...urls].slice(0, MAX_IMAGES_PER_ARTICLE)
}

/** Saves one image into the offline cache through the backend (no CORS trouble). */
async function cacheImage(cache: Cache, url: string, signal: AbortSignal): Promise<boolean> {
  if (await cache.match(url)) return true
  try {
    const res = await fetch(`/api/fetch?url=${encodeURIComponent(url)}`, { headers: apiHeaders(), signal })
    if (!res.ok) return false
    const type = res.headers.get('x-original-content-type') || res.headers.get('content-type') || ''
    if (!type.startsWith('image/')) return false
    const blob = await res.blob()
    if (blob.size > MAX_IMAGE_BYTES) return false
    await cache.put(url, new Response(blob, { headers: { 'content-type': type } }))
    return true
  } catch {
    return false
  }
}

/** Fetches the app's own files so the service worker has them for offline starts. */
async function cacheAppShell(): Promise<void> {
  try {
    const html = await (await fetch('/', { cache: 'no-cache' })).text()
    const doc = new DOMParser().parseFromString(html, 'text/html')
    const files = [
      ...[...doc.querySelectorAll('script[src]')].map((s) => s.getAttribute('src')),
      ...[...doc.querySelectorAll('link[href]')].map((l) => l.getAttribute('href')),
      '/manifest.webmanifest',
      '/icon.svg',
    ].filter((u): u is string => !!u && u.startsWith('/'))
    await Promise.all(files.map((f) => fetch(f).catch(() => undefined)))
  } catch {
    // offline already, or no service worker: nothing to do
  }
}

/** The unread articles the job would save, newest first. */
export async function unreadForOffline(limit: number): Promise<Item[]> {
  const unread = await db.items.where('read').equals(0).toArray()
  unread.sort((a, b) => b.sortTime - a.sortTime)
  return limit > 0 ? unread.slice(0, limit) : unread
}

export async function downloadForOffline(opts: DownloadOptions): Promise<void> {
  if (job.get().running) return
  abort = new AbortController()
  const signal = abort.signal
  job.set({ ...IDLE, running: true, message: 'Finding unread articles…' })
  try {
    // Ask the browser not to evict the saved copies when space runs low
    await navigator.storage?.persist?.().catch(() => false)
    await cacheAppShell()
    const items = await unreadForOffline(opts.limit)
    const feeds = new Map((await db.feeds.toArray()).map((f) => [f.url, f]))
    const cache = opts.images && hasCaches() ? await caches.open(IMAGE_CACHE) : null
    let done = 0
    let failed = 0
    let images = 0
    const update = (message: string) => job.set({ running: true, done, total: items.length, failed, images, message })
    update('Saving articles…')

    let next = 0
    const worker = async () => {
      while (next < items.length && !signal.aborted) {
        let item = items[next++]
        const isVideo = !!youtubeVideoId(item.link)
        // Full text for everything with a page (YouTube pages have none); feed text stays as the fallback
        if (!isVideo && item.link && !item.fullHtml && !opensInBrowser(feeds.get(item.feedUrl))) {
          try {
            const html = await loadFullText(item)
            item = { ...item, fullHtml: html }
          } catch {
            failed++
          }
        }
        const saved: string[] = []
        if (cache) {
          const urls = isVideo ? (item.imageUrl ? [item.imageUrl] : []) : articleImageUrls(item)
          for (const url of urls) {
            if (signal.aborted) break
            if (await cacheImage(cache, url, signal)) {
              saved.push(url)
              images++
            }
          }
        }
        if (signal.aborted) break
        await db.items.update(item.id, { offlineAt: Date.now(), offlineImages: saved })
        done++
        update('Saving articles…')
      }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, worker))
    job.set({
      running: false,
      done,
      total: items.length,
      failed,
      images,
      message: signal.aborted ? `Stopped after ${done} of ${items.length}` : `Saved ${done} articles and ${images} images for offline reading`,
    })
  } catch (e) {
    job.set({ ...job.get(), running: false, message: `Download failed: ${e instanceof Error ? e.message : e}` })
  } finally {
    abort = null
  }
}

export function stopOfflineDownload() {
  abort?.abort()
}

/** Removes every saved image and the "saved offline" marks. Full text stays (it is small). */
export async function removeOfflineCopies(): Promise<void> {
  if (hasCaches()) await caches.delete(IMAGE_CACHE)
  const marked = await db.items.filter((i) => !!i.offlineAt).primaryKeys()
  await db.items.bulkUpdate(marked.map((key) => ({ key, changes: { offlineAt: undefined, offlineImages: undefined } })))
  job.set(IDLE)
}

/**
 * Drops images and videos of articles read more than a week ago (starred ones stay).
 * Runs at start-up; cheap when there is nothing to do.
 */
export async function cleanupOffline(now = Date.now()): Promise<number> {
  const old = await db.items
    .filter((i) => (!!i.offlineAt || !!i.offlineVideo) && i.read === 1 && !i.saved && (i.readTime ?? 0) < now - KEEP_READ_MS)
    .toArray()
  if (!old.length) return 0
  const images = hasCaches() ? await caches.open(IMAGE_CACHE) : null
  for (const item of old) {
    if (images) for (const url of item.offlineImages ?? []) await images.delete(url)
    const vid = youtubeVideoId(item.link)
    if (vid && item.offlineVideo) await removeVideo(item.id, vid)
  }
  await db.items.bulkUpdate(old.map((i) => ({ key: i.id, changes: { offlineAt: undefined, offlineImages: undefined, offlineVideo: undefined } })))
  return old.length
}

/** How much this site stores in the browser, in bytes. */
export async function storageUsed(): Promise<number | null> {
  try {
    return (await navigator.storage?.estimate?.())?.usage ?? null
  } catch {
    return null
  }
}

export function formatBytes(n: number): string {
  if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`
}

/** Without srcset/<source> the browser uses the plain src, which is the one saved offline. */
export function preferSavedImages(html: string): string {
  if (!html.includes('srcset')) return html
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html')
  doc.querySelectorAll('picture source').forEach((s) => s.remove())
  doc.querySelectorAll('img[srcset]').forEach((img) => {
    img.removeAttribute('srcset')
    img.removeAttribute('sizes')
  })
  return doc.body.innerHTML
}

// ---------------------------------------------------------------------------------------------
// YouTube videos (yt-dlp on the local server)

const videoKey = (videoId: string) => `/offline-video/${videoId}`

export interface VideoJob {
  phase: 'mac' | 'transfer' | 'error'
  percent: number
  error?: string
}

const videoJobs = store<Record<string, VideoJob>>({})
const videoAborts = new Map<string, AbortController>()
export const useVideoJobs = () => useStore(videoJobs)

function setVideoJob(videoId: string, v: VideoJob | null) {
  const next = { ...videoJobs.get() }
  if (v) next[videoId] = v
  else delete next[videoId]
  videoJobs.set(next)
}

const ytdlUrl = (action: string, videoId?: string) => {
  const q = new URLSearchParams()
  if (videoId) q.set('id', videoId)
  const t = getAccessToken()
  if (t) q.set('t', t)
  return `/api/ytdl/${action}?${q}`
}

let ytdlAvailable: Promise<boolean> | null = null
/** True when this page comes from the local server with yt-dlp (not Vercel). */
export function videoSavingAvailable(): Promise<boolean> {
  ytdlAvailable ??= fetch(ytdlUrl('health'))
    .then(async (r) => r.ok && (await r.json()).ok === true)
    .catch(() => false)
  return ytdlAvailable
}

export function useVideoSavingAvailable(): boolean {
  const [ok, setOk] = useState(false)
  useEffect(() => {
    let live = true
    videoSavingAvailable().then((v) => live && setOk(v))
    return () => {
      live = false
    }
  }, [])
  return ok
}

const sleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(t)
      reject(new DOMException('Stopped', 'AbortError'))
    })
  })

/** Downloads the video on the Mac with yt-dlp, then keeps the MP4 in this browser. */
export async function saveVideo(itemId: string, videoId: string): Promise<void> {
  if (videoJobs.get()[videoId] && videoJobs.get()[videoId].phase !== 'error') return
  const ctl = new AbortController()
  videoAborts.set(videoId, ctl)
  const signal = ctl.signal
  setVideoJob(videoId, { phase: 'mac', percent: 0 })
  try {
    if (!(await videoSavingAvailable())) throw new Error('Saving videos needs Feeder running on your Mac (localhost) with yt-dlp')
    let st = await (await fetch(ytdlUrl('start', videoId), { method: 'POST', signal })).json()
    while (st.state === 'running') {
      setVideoJob(videoId, { phase: 'mac', percent: st.percent })
      await sleep(1000, signal)
      st = await (await fetch(ytdlUrl('status', videoId), { signal })).json()
    }
    if (st.state !== 'done') throw new Error(st.error || 'The download did not finish')

    const res = await fetch(ytdlUrl('file', videoId), { signal })
    if (!res.ok || !res.body) throw new Error(`Could not get the video (HTTP ${res.status})`)
    const total = Number(res.headers.get('content-length')) || st.size || 0
    const reader = res.body.getReader()
    const chunks: Uint8Array[] = []
    let got = 0
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(value)
      got += value.length
      if (total) setVideoJob(videoId, { phase: 'transfer', percent: Math.round((got / total) * 100) })
    }
    const blob = new Blob(chunks as BlobPart[], { type: 'video/mp4' })
    const cache = await caches.open(VIDEO_CACHE)
    await cache.put(videoKey(videoId), new Response(blob, { headers: { 'content-type': 'video/mp4', 'content-length': String(blob.size) } }))
    await navigator.storage?.persist?.().catch(() => false)
    await db.items.update(itemId, { offlineVideo: blob.size })
    setVideoJob(videoId, null)
  } catch (e) {
    if (signal.aborted) setVideoJob(videoId, null)
    else setVideoJob(videoId, { phase: 'error', percent: 0, error: e instanceof Error ? e.message : String(e) })
  } finally {
    videoAborts.delete(videoId)
  }
}

export function cancelVideo(videoId: string) {
  videoAborts.get(videoId)?.abort()
  setVideoJob(videoId, null)
}

export function clearVideoError(videoId: string) {
  if (videoJobs.get()[videoId]?.phase === 'error') setVideoJob(videoId, null)
}

/** A blob: URL for the saved video, or null. The caller revokes it. */
export async function savedVideoUrl(videoId: string): Promise<string | null> {
  if (!hasCaches()) return null
  const res = await (await caches.open(VIDEO_CACHE)).match(videoKey(videoId))
  return res ? URL.createObjectURL(await res.blob()) : null
}

export async function removeVideo(itemId: string, videoId: string): Promise<void> {
  if (hasCaches()) await (await caches.open(VIDEO_CACHE)).delete(videoKey(videoId))
  await db.items.update(itemId, { offlineVideo: undefined })
}

export async function removeAllVideos(): Promise<void> {
  if (hasCaches()) await caches.delete(VIDEO_CACHE)
  const marked = await db.items.filter((i) => !!i.offlineVideo).primaryKeys()
  await db.items.bulkUpdate(marked.map((key) => ({ key, changes: { offlineVideo: undefined } })))
}
