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
import { getSettings } from './settings'
import { articleVideoId, youtubeVideoId } from './youtube'

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

/** Offline mode (Settings / list header), mirrored here for code outside React. */
let forced = false
const forcedSubs = new Set<() => void>()
export function setForcedOffline(v: boolean) {
  if (forced === v) return
  forced = v
  forcedSubs.forEach((f) => f())
}
void getSettings()
  .then((s) => setForcedOffline(s.offlineMode))
  .catch(() => undefined)

/** True when there is a connection and Offline mode is off. */
export function isOnline(): boolean {
  return !forced && (typeof navigator === 'undefined' || navigator.onLine)
}

/** The real connection only, ignoring Offline mode. */
export function useNetwork(): boolean {
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

/** False when offline or in Offline mode: then nothing is fetched. */
export function useOnline(): boolean {
  const network = useNetwork()
  const [f, setF] = useState(forced)
  useEffect(() => {
    const sub = () => setF(forced)
    forcedSubs.add(sub)
    sub()
    return () => void forcedSubs.delete(sub)
  }, [])
  return network && !f
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
  if (!isOnline()) {
    job.set({ ...IDLE, message: 'Turn off Offline mode (and be online) to download' })
    return
  }
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
    const vid = articleVideoId(item)
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
const subsKey = (videoId: string, lang: string) => `/offline-video/${videoId}.${lang}.vtt`

export interface VideoJob {
  /** mac: yt-dlp on the Mac; transfer: copying into this browser; paused: stopped by Pause. */
  phase: 'mac' | 'transfer' | 'paused' | 'error'
  percent: number
  error?: string
  /** The article, so the Downloads page can show unfinished downloads after a reload. */
  itemId?: string
}

const JOBS_KEY = 'feeder.videoJobs'

/** Unfinished downloads from before a reload: running ones come back paused. */
function loadVideoJobs(): Record<string, VideoJob> {
  try {
    const raw = JSON.parse(localStorage.getItem(JOBS_KEY) ?? '{}') as Record<string, VideoJob>
    const out: Record<string, VideoJob> = {}
    for (const [id, j] of Object.entries(raw)) {
      if (!j?.itemId) continue
      out[id] = { ...j, phase: j.phase === 'error' ? 'error' : 'paused' }
    }
    return out
  } catch {
    return {}
  }
}

const videoJobs = store<Record<string, VideoJob>>(typeof localStorage === 'undefined' ? {} : loadVideoJobs())
const videoAborts = new Map<string, AbortController>()
const pausing = new Set<string>()
export const useVideoJobs = () => useStore(videoJobs)

function setVideoJob(videoId: string, v: VideoJob | null) {
  const old = videoJobs.get()[videoId]
  const next = { ...videoJobs.get() }
  if (v) next[videoId] = { ...v, itemId: v.itemId ?? old?.itemId }
  else delete next[videoId]
  videoJobs.set(next)
  // Progress ticks every second; only write when a download starts, stops or changes state
  if (old?.phase !== v?.phase) {
    try {
      localStorage.setItem(JOBS_KEY, JSON.stringify(next))
    } catch {
      // private window or full storage: the list just won't survive a reload
    }
  }
}

export const ytdlUrl = (action: string, videoId?: string) => {
  const q = new URLSearchParams()
  if (videoId) q.set('id', videoId)
  const t = getAccessToken()
  if (t) q.set('t', t)
  return `/api/ytdl/${action}?${q}`
}

let ytdlAvailable: Promise<boolean> | null = null
/** True when this page comes from the local server with yt-dlp (not Vercel). */
export function videoSavingAvailable(): Promise<boolean> {
  // Only a success is remembered: a failed check (server busy, offline) is tried again next time
  ytdlAvailable ??= fetch(ytdlUrl('health'))
    .then(async (r) => r.ok && (await r.json()).ok === true)
    .catch(() => false)
    .then((ok) => {
      if (!ok) ytdlAvailable = null
      return ok
    })
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
  const current = videoJobs.get()[videoId]
  if (current && current.phase !== 'error' && current.phase !== 'paused') return
  const ctl = new AbortController()
  videoAborts.set(videoId, ctl)
  const signal = ctl.signal
  setVideoJob(videoId, { phase: 'mac', percent: current?.phase === 'paused' ? current.percent : 0, itemId })
  try {
    if (!isOnline()) throw new Error('Turn off Offline mode to save videos')
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
    await db.items.update(itemId, { offlineVideo: blob.size, offlineVideoAt: Date.now() })
    setVideoJob(videoId, null)
    // Subtitles separately: a video without them is still worth keeping
    void saveSubtitles(itemId, videoId).catch(() => undefined)
  } catch (e) {
    if (pausing.delete(videoId)) {
      // pauseVideo already shows it as paused
    } else if (signal.aborted) setVideoJob(videoId, null)
    else setVideoJob(videoId, { phase: 'error', percent: 0, error: e instanceof Error ? e.message : String(e) })
  } finally {
    videoAborts.delete(videoId)
  }
}

/** Stops a download but keeps what yt-dlp saved so far on the Mac; saveVideo resumes it. */
export function pauseVideo(videoId: string) {
  const j = videoJobs.get()[videoId]
  if (!j || j.phase === 'paused' || j.phase === 'error') return
  pausing.add(videoId)
  videoAborts.get(videoId)?.abort()
  if (j.phase === 'mac') void fetch(ytdlUrl('pause', videoId), { method: 'POST' }).catch(() => undefined)
  setVideoJob(videoId, { ...j, phase: 'paused' })
}

/** Stops a download (running, paused or failed) and deletes what it saved. */
export function cancelVideo(videoId: string) {
  videoAborts.get(videoId)?.abort()
  void fetch(ytdlUrl('cancel', videoId), { method: 'POST' }).catch(() => undefined)
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
  if (hasCaches()) {
    const videos = await caches.open(VIDEO_CACHE)
    await videos.delete(videoKey(videoId))
    for (const lang of (await db.items.get(itemId))?.offlineSubs ?? []) await videos.delete(subsKey(videoId, lang))
  }
  await db.items.update(itemId, { offlineVideo: undefined, offlineSubs: undefined })
}

const subsRunning = store<Record<string, boolean>>({})
export const useSubsRunning = () => useStore(subsRunning)

/** Fetches subtitles (the video's own, else YouTube's auto captions) with yt-dlp and keeps them. */
export async function saveSubtitles(itemId: string, videoId: string): Promise<string[]> {
  if (subsRunning.get()[videoId]) return []
  subsRunning.set({ ...subsRunning.get(), [videoId]: true })
  try {
    if (!isOnline() || !(await videoSavingAvailable())) throw new Error('Getting subtitles needs Feeder on your Mac and a connection')
    const res = await fetch(ytdlUrl('subs', videoId), { method: 'POST' })
    const body = (await res.json()) as { tracks?: { lang: string; vtt: string }[]; error?: string }
    if (!res.ok || !body.tracks) throw new Error(body.error || `HTTP ${res.status}`)
    const videos = await caches.open(VIDEO_CACHE)
    for (const t of body.tracks) await videos.put(subsKey(videoId, t.lang), new Response(t.vtt, { headers: { 'content-type': 'text/vtt' } }))
    const langs = body.tracks.map((t) => t.lang)
    await db.items.update(itemId, { offlineSubs: langs })
    return langs
  } finally {
    const next = { ...subsRunning.get() }
    delete next[videoId]
    subsRunning.set(next)
  }
}

/** blob: URLs of the saved subtitle tracks. The caller revokes them. */
/**
 * Plays the saved copy in IINA: the local server writes the video (and its subtitles, named
 * so IINA loads them) to a temp folder on this Mac and opens it. Needs the local server.
 */
export async function openSavedInIina(videoId: string, langs: string[]): Promise<void> {
  if (!hasCaches()) throw new Error('No saved copy in this browser')
  const videos = await caches.open(VIDEO_CACHE)
  const video = await videos.match(videoKey(videoId))
  if (!video) throw new Error('The offline copy is gone. Save it again.')
  const post = async (body: Blob, lang?: string) => {
    const url = ytdlUrl('iina', videoId) + (lang ? `&lang=${encodeURIComponent(lang)}` : '')
    const res = await fetch(url, { method: 'POST', body })
    if (!res.ok) throw new Error(((await res.json().catch(() => null)) as { error?: string } | null)?.error || `IINA failed (${res.status})`)
  }
  for (const lang of langs) {
    const sub = await videos.match(subsKey(videoId, lang))
    if (sub) await post(await sub.blob(), lang)
  }
  // The video goes last: the server opens IINA once it has it
  await post(await video.blob())
}

export async function savedSubtitleUrls(videoId: string, langs: string[]): Promise<{ lang: string; url: string }[]> {
  if (!hasCaches() || !langs.length) return []
  const videos = await caches.open(VIDEO_CACHE)
  const out: { lang: string; url: string }[] = []
  for (const lang of langs) {
    const res = await videos.match(subsKey(videoId, lang))
    if (res) out.push({ lang, url: URL.createObjectURL(await res.blob()) })
  }
  return out
}

export async function removeAllVideos(): Promise<void> {
  if (hasCaches()) await caches.delete(VIDEO_CACHE)
  const marked = await db.items.filter((i) => !!i.offlineVideo).primaryKeys()
  await db.items.bulkUpdate(marked.map((key) => ({ key, changes: { offlineVideo: undefined, offlineSubs: undefined } })))
}

// ---------------------------------------------------------------------------------------------
// Deleting an article deletes its downloads (Delete, Delete read, a deletion from another
// device, or old articles dropped by refresh): every path goes through db.items deletes.

/** Removes an article's saved images and video from Cache Storage. */
export async function deleteOfflineFiles(item: Pick<Item, 'offlineImages' | 'offlineVideo' | 'offlineSubs' | 'link' | 'contentHtml'>): Promise<void> {
  if (!hasCaches()) return
  if (item.offlineImages?.length) {
    const images = await caches.open(IMAGE_CACHE)
    await Promise.all(item.offlineImages.map((u) => images.delete(u)))
  }
  const vid = articleVideoId(item)
  if (vid && item.offlineVideo) {
    const videos = await caches.open(VIDEO_CACHE)
    await videos.delete(videoKey(vid))
    for (const lang of item.offlineSubs ?? []) await videos.delete(subsKey(vid, lang))
  }
}

db.items.hook('deleting', (_key, item) => {
  if (item && (item.offlineImages?.length || item.offlineVideo)) {
    // After the transaction: Cache Storage is not part of IndexedDB
    setTimeout(() => void deleteOfflineFiles(item).catch(() => undefined), 0)
  }
})
