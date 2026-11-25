/**
 * ZIM library on this device: the files live in the browser's private file system (OPFS), so
 * they stay offline and never leave the device. Pages are served to the reader's iframe at
 * /zim/<book>/<path> by sw.js, which asks an open Feeder window (this module) for the bytes.
 */
import { blobSource, ZimArchive } from './zim'

export interface ZimBook {
  id: string
  file: string
  size: number
  title: string
  description: string
  language: string
  articleCount: number | null
  icon: string | null
  hasFulltext: boolean
  date: string
  flavour: string
}

const DIR = 'zim'
const listeners = new Set<() => void>()
export const onLibraryChange = (fn: () => void) => {
  listeners.add(fn)
  return () => void listeners.delete(fn)
}
const changed = () => listeners.forEach((fn) => fn())

export function zimSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory
}

async function dir(): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory()
  return root.getDirectoryHandle(DIR, { create: true })
}

/** Book ids are file names without ".zim", safe for URLs. */
export const bookIdOf = (file: string) => file.replace(/\.zim$/i, '')
const safeName = (name: string) =>
  name
    .replace(/\.zim$/i, '')
    .replace(/[^\w.-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 120) || 'book'

const archives = new Map<string, Promise<ZimArchive>>()

export function openBook(id: string): Promise<ZimArchive> {
  let p = archives.get(id)
  if (!p) {
    p = (async () => {
      const h = await (await dir()).getFileHandle(`${id}.zim`)
      return ZimArchive.open(blobSource(await h.getFile()))
    })()
    archives.set(id, p)
    p.catch(() => archives.delete(id))
  }
  return p
}

const metaCache = new Map<string, ZimBook>()

async function describe(file: string, size: number): Promise<ZimBook> {
  const id = bookIdOf(file)
  const key = `${file}:${size}`
  const hit = metaCache.get(key)
  if (hit) return hit
  const z = await openBook(id)
  const icon = await z.illustration().catch(() => null)
  const count = await z.metadata('Counter').catch(() => null)
  const articles = count?.match(/text\/html=(\d+)/)?.[1]
  const book: ZimBook = {
    id,
    file,
    size,
    title: (await z.metadata('Title')) || id,
    description: (await z.metadata('Description')) || '',
    language: (await z.metadata('Language')) || '',
    articleCount: articles ? Number(articles) : null,
    icon: icon ? URL.createObjectURL(new Blob([icon as BlobPart], { type: 'image/png' })) : null,
    hasFulltext: await z.hasFulltextIndex().catch(() => false),
    date: (await z.metadata('Date')) || '',
    flavour: (await z.metadata('Flavour')) || '',
  }
  metaCache.set(key, book)
  return book
}

export async function listBooks(): Promise<{ books: ZimBook[]; broken: string[] }> {
  const d = await dir()
  const books: ZimBook[] = []
  const broken: string[] = []
  for await (const [name, h] of d as unknown as AsyncIterable<[string, FileSystemHandle]>) {
    if (h.kind !== 'file' || !name.endsWith('.zim')) continue
    const f = await (h as FileSystemFileHandle).getFile()
    try {
      books.push(await describe(name, f.size))
    } catch {
      broken.push(name)
    }
  }
  books.sort((a, b) => a.title.localeCompare(b.title))
  return { books, broken }
}

export async function deleteBook(file: string) {
  archives.delete(bookIdOf(file))
  await (await dir()).removeEntry(file)
  changed()
}

export async function storageUsage(): Promise<{ used: number; quota: number } | null> {
  const e = await navigator.storage.estimate?.()
  return e ? { used: e.usage ?? 0, quota: e.quota ?? 0 } : null
}

async function uniqueName(base: string): Promise<string> {
  const d = await dir()
  for (let i = 0; ; i++) {
    const name = `${safeName(base)}${i ? `-${i}` : ''}.zim`
    try {
      await d.getFileHandle(name)
    } catch {
      return name
    }
  }
}

export type Progress = (done: number, total: number) => void

/** Writes a stream into the library under a temporary name, renaming it when complete. */
async function save(base: string, stream: ReadableStream<Uint8Array>, total: number, onProgress: Progress, signal?: AbortSignal): Promise<string> {
  // Ask the browser not to clear these files when space runs low
  navigator.storage.persist?.().catch(() => undefined)
  const name = await uniqueName(base)
  const d = await dir()
  const tmp = `${name}.part`
  const h = await d.getFileHandle(tmp, { create: true })
  try {
    const w = await h.createWritable()
    let done = 0
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, c) {
        done += chunk.length
        onProgress(done, total)
        c.enqueue(chunk)
      },
    })
    await stream.pipeThrough(counter, { signal }).pipeTo(w, { signal })
    const file = await h.getFile()
    // Renaming keeps a half-written file from showing up as a broken book
    const moved = h as FileSystemFileHandle & { move?: (name: string) => Promise<void> }
    if (moved.move) await moved.move(name)
    else {
      const out = await (await d.getFileHandle(name, { create: true })).createWritable()
      await file.stream().pipeTo(out)
      await d.removeEntry(tmp)
    }
    await ZimArchive.open(blobSource(await (await d.getFileHandle(name)).getFile()))
    changed()
    return name
  } catch (e) {
    await d.removeEntry(tmp).catch(() => undefined)
    await d.removeEntry(name).catch(() => undefined)
    throw e
  }
}

/** Copies a .zim the user picked into the library. */
export async function importFile(file: File, onProgress: Progress, signal?: AbortSignal): Promise<string> {
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer())
  if (new DataView(head.buffer).getUint32(0, true) !== 72173914) throw new Error(`${file.name} is not a ZIM file`)
  return save(file.name, file.stream(), file.size, onProgress, signal)
}

/** Downloads a .zim straight into the library. Fails (TypeError) where the mirror doesn't allow it. */
export async function downloadUrl(url: string, onProgress: Progress, signal?: AbortSignal): Promise<string> {
  const res = await fetch(url, { signal })
  if (!res.ok || !res.body) throw new Error(`Download failed (${res.status})`)
  const name = decodeURIComponent(new URL(res.url || url).pathname.split('/').pop() || 'book')
  return save(name, res.body, Number(res.headers.get('content-length')) || 0, onProgress, signal)
}

/** One book from the Kiwix online catalog. */
export interface CatalogEntry {
  id: string
  title: string
  summary: string
  language: string
  flavour: string
  category: string
  size: number
  articleCount: number
  url: string
  icon: string | null
  date: string
}

const CATALOG = 'https://library.kiwix.org'

export async function searchCatalog(query: string, lang: string, signal?: AbortSignal): Promise<CatalogEntry[]> {
  const params = new URLSearchParams({ count: '40' })
  if (query.trim()) params.set('q', query.trim())
  if (lang) params.set('lang', lang)
  const res = await fetch(`${CATALOG}/catalog/v2/entries?${params}`, { signal })
  if (!res.ok) throw new Error(`Catalog failed (${res.status})`)
  const doc = new DOMParser().parseFromString(await res.text(), 'application/xml')
  const text = (e: Element, tag: string) => e.getElementsByTagName(tag)[0]?.textContent?.trim() ?? ''
  return [...doc.getElementsByTagName('entry')].flatMap((e) => {
    const links = [...e.getElementsByTagName('link')]
    const acq = links.find((l) => l.getAttribute('type') === 'application/x-zim')
    if (!acq) return []
    const thumb = links.find((l) => l.getAttribute('rel')?.endsWith('thumbnail'))?.getAttribute('href')
    return [
      {
        id: text(e, 'id'),
        title: text(e, 'title'),
        summary: text(e, 'summary'),
        language: text(e, 'language'),
        flavour: text(e, 'flavour'),
        category: text(e, 'category'),
        size: Number(acq.getAttribute('length')) || 0,
        articleCount: Number(text(e, 'articleCount')) || 0,
        // The catalog links a .meta4 mirror list; without it the address redirects to a mirror
        url: (acq.getAttribute('href') ?? '').replace(/\.meta4$/, ''),
        icon: thumb ? new URL(thumb, CATALOG).href : null,
        date: text(e, 'updated').slice(0, 10),
      },
    ]
  })
}

/** The last page read in each book, so it opens where you left off. */
const LAST_KEY = 'feeder.zimLast'
export function lastPath(id: string): string | null {
  try {
    return (JSON.parse(localStorage.getItem(LAST_KEY) || '{}') as Record<string, string>)[id] ?? null
  } catch {
    return null
  }
}
export function rememberPath(id: string, path: string) {
  try {
    const all = JSON.parse(localStorage.getItem(LAST_KEY) || '{}') as Record<string, string>
    all[id] = path
    localStorage.setItem(LAST_KEY, JSON.stringify(all))
  } catch {
    /* private mode */
  }
}

export const zimUrl = (id: string, path: string) => `/zim/${encodeURIComponent(id)}/${path.split('/').map(encodeURIComponent).join('/')}`

/** Splits a /zim/<book>/<path> address. */
export function parseZimUrl(pathname: string): { id: string; path: string } | null {
  const m = pathname.match(/^\/zim\/([^/]+)\/?(.*)$/)
  if (!m) return null
  try {
    return { id: decodeURIComponent(m[1]), path: m[2].split('/').map(decodeURIComponent).join('/') }
  } catch {
    return null
  }
}

interface ZimRequest {
  type: 'zim-fetch'
  path: string
  range: string | null
}

async function answer(req: ZimRequest): Promise<{ status: number; headers: Record<string, string>; body: ArrayBuffer | string }> {
  const at = parseZimUrl(req.path)
  if (!at) return { status: 404, headers: { 'Content-Type': 'text/plain' }, body: 'Not found' }
  let z: ZimArchive
  try {
    z = await openBook(at.id)
  } catch {
    return { status: 404, headers: { 'Content-Type': 'text/plain' }, body: 'This book is not on this device' }
  }
  const path = at.path || (await z.mainPath()) || ''
  const d = await z.findByUrlPath(path)
  if (!d) return { status: 404, headers: { 'Content-Type': 'text/html' }, body: notFoundPage(path) }
  const r = await z.resolve(d)
  // A redirect to another path: send the browser there, so relative links resolve from it
  const target = z.urlPath(r)
  if (!at.path || target !== path) return { status: 302, headers: { Location: zimUrl(at.id, target) }, body: '' }
  const data = await z.blobData(r)
  const mime = z.mime(r)
  const headers: Record<string, string> = { 'Content-Type': mime, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-cache' }
  const m = req.range?.match(/bytes=(\d*)-(\d*)/)
  if (m && (m[1] || m[2])) {
    const size = data.length
    const start = m[1] ? Number(m[1]) : Math.max(0, size - Number(m[2]))
    const end = m[1] && m[2] ? Math.min(size - 1, Number(m[2])) : size - 1
    if (start >= size) return { status: 416, headers: { 'Content-Range': `bytes */${size}` }, body: '' }
    headers['Content-Range'] = `bytes ${start}-${end}/${size}`
    return { status: 206, headers, body: data.slice(start, end + 1).buffer }
  }
  return { status: 200, headers, body: data.slice().buffer }
}

const notFoundPage = (path: string) =>
  `<!doctype html><meta charset="utf-8"><body style="font:16px system-ui;padding:2rem;color:#666">This page is not in the book: ${path.replace(/[<>&]/g, '')}</body>`

/** Answers the service worker's requests for /zim/ pages. Called once at startup. */
export function serveZimToServiceWorker() {
  if (!('serviceWorker' in navigator) || !zimSupported()) return
  navigator.serviceWorker.addEventListener('message', (e: MessageEvent) => {
    const req = e.data as ZimRequest
    if (req?.type !== 'zim-fetch' || !e.ports[0]) return
    const port = e.ports[0]
    answer(req)
      .catch((err) => ({ status: 500, headers: { 'Content-Type': 'text/plain' }, body: String(err) }))
      .then((res) => port.postMessage(res, typeof res.body === 'string' ? [] : [res.body]))
  })
}
