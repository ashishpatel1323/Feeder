import { proxiedUrl, proxyFetch } from './api'
import { db, type ShelfStatusMark } from './db'

/**
 * Books' three groups (as in the Voice app) and covers picked from the Internet. Same rules as
 * Android's model/shelf/ShelfMarks.kt and BookCovers.kt. Synced as kinds "shelfstatus" ({ s }) and
 * "bookcover" ({ url }), keyed "ebook:<id>", "audiobook:<id>" or "zim:<id>"; newest wins.
 */
export type BookStatus = 'current' | 'new' | 'done'
export const STATUS_ORDER: BookStatus[] = ['current', 'new', 'done']
export const STATUS_LABEL: Record<BookStatus, string> = { current: 'Current', new: 'Not started yet', done: 'Completed' }
/** This far through counts as finished: credits, an index or an epilogue often go unread */
export const DONE_AT = 0.98

export const isStatus = (s: unknown): s is BookStatus => s === 'current' || s === 'new' || s === 'done'
export const markKey = (kind: 'ebook' | 'audiobook' | 'zim', id: string) => `${kind}:${id}`

/** From progress alone: never opened = not started, near the end = completed, else current. */
export function autoStatus(f: number | null | undefined, opened: boolean, canFinish = true): BookStatus {
  if (canFinish && f != null && f >= DONE_AT) return 'done'
  if (opened || (f != null && f > 0)) return 'current'
  return 'new'
}

/** A status picked by hand holds until the book is read or played after it was picked. */
export function effectiveStatus(auto: BookStatus, picked: Pick<ShelfStatusMark, 's' | 'ts'> | undefined, lastActivity: number): BookStatus {
  if (!picked || !isStatus(picked.s)) return auto
  return lastActivity > picked.ts ? auto : picked.s
}

function syncSoon() {
  void import('./sync2').then((m) => m.syncNow()).catch(() => undefined)
}

export async function setStatus(key: string, s: BookStatus, now = Date.now()): Promise<void> {
  const old = await db.shelfStatus.get(key)
  await db.shelfStatus.put({ key, s, ts: Math.max(now, (old?.ts ?? 0) + 1) })
  syncSoon()
}

/** A status from another device, kept when newer. */
export async function applyRemoteStatus(key: string, s: unknown, ts: number): Promise<void> {
  if (!isStatus(s)) return
  const old = await db.shelfStatus.get(key)
  if (old && old.ts >= ts) return
  await db.shelfStatus.put({ key, s, ts })
}

/** One picture a cover search found: [url] the big one, [thumb] for the grid. */
export interface CoverCandidate {
  url: string
  thumb: string
  label: string
}

type Json = Record<string, unknown>
const str = (v: unknown) => (typeof v === 'string' ? v : '')
const join = (...xs: string[]) => xs.filter(Boolean).join(' · ')

export function parseItunes(root: unknown): CoverCandidate[] {
  const results = ((root as Json)?.results as Json[] | undefined) ?? []
  return results.flatMap((r) => {
    const art = str(r.artworkUrl100) || str(r.artworkUrl60)
    if (!art) return []
    const size = (n: number) => art.replace(/\/\d+x\d+(bb)?\.(jpg|png)$/, `/${n}x${n}bb.jpg`)
    return [{ url: size(600), thumb: size(200), label: join(str(r.collectionName) || str(r.trackName), str(r.artistName)) || 'Apple Books' }]
  })
}

export function parseOpenLibrary(root: unknown): CoverCandidate[] {
  const docs = ((root as Json)?.docs as Json[] | undefined) ?? []
  return docs.flatMap((d) => {
    if (typeof d.cover_i !== 'number') return []
    const who = Array.isArray(d.author_name) ? str(d.author_name[0]) : ''
    return [{ url: `https://covers.openlibrary.org/b/id/${d.cover_i}-L.jpg`, thumb: `https://covers.openlibrary.org/b/id/${d.cover_i}-M.jpg`, label: join(str(d.title), who) || 'Open Library' }]
  })
}

export function parseGoogleBooks(root: unknown): CoverCandidate[] {
  const items = ((root as Json)?.items as Json[] | undefined) ?? []
  return items.flatMap((it) => {
    const info = it.volumeInfo as Json | undefined
    const links = info?.imageLinks as Json | undefined
    const thumb = (str(links?.thumbnail) || str(links?.smallThumbnail)).replace('http://', 'https://').replace('&edge=curl', '')
    if (!thumb) return []
    const who = Array.isArray(info?.authors) ? str((info!.authors as unknown[])[0]) : ''
    return [{ url: thumb.replace(/zoom=\d/, 'zoom=0'), thumb, label: join(str(info?.title), who) || 'Google Books' }]
  })
}

/**
 * Covers for [query] (title and author) from Apple Books, Open Library and Google Books.
 * [onPartial] gets the list so far each time a source answers, so a slow one doesn't hold up the rest.
 */
export async function searchCovers(query: string, audiobook: boolean, onPartial?: (found: CoverCandidate[]) => void): Promise<CoverCandidate[]> {
  const q = encodeURIComponent(query.trim())
  if (!q) return []
  const sources: [string, (j: unknown) => CoverCandidate[]][] = [
    [`https://itunes.apple.com/search?term=${q}&media=${audiobook ? 'audiobook' : 'ebook'}&limit=15`, parseItunes],
    ...(audiobook ? [[`https://itunes.apple.com/search?term=${q}&media=ebook&limit=10`, parseItunes] as [string, (j: unknown) => CoverCandidate[]]] : []),
    [`https://openlibrary.org/search.json?q=${q}&fields=cover_i,title,author_name&limit=15`, parseOpenLibrary],
    [`https://www.googleapis.com/books/v1/volumes?q=${q}&maxResults=15&printType=books`, parseGoogleBooks],
  ]
  const slots: CoverCandidate[][] = sources.map(() => [])
  const merged = () => {
    const seen = new Set<string>()
    return slots.flat().filter((c) => !seen.has(c.url) && !!seen.add(c.url))
  }
  await Promise.all(
    sources.map(async ([url, parse], n) => {
      try {
        slots[n] = parse(JSON.parse((await proxyFetch(url)).text))
      } catch {
        return
      }
      onPartial?.(merged())
    }),
  )
  return merged()
}

/** A picture at [url], scaled to 360 px on the long side, as a JPEG data URL. */
async function coverData(url: string): Promise<string | null> {
  const res = await fetch(proxiedUrl(url))
  if (!res.ok) return null
  const bmp = await createImageBitmap(await res.blob()).catch(() => null)
  if (!bmp) return null
  const scale = Math.min(1, 360 / Math.max(bmp.width, bmp.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bmp.width * scale))
  canvas.height = Math.max(1, Math.round(bmp.height * scale))
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height)
  bmp.close()
  return canvas.toDataURL('image/jpeg', 0.85)
}

/** Puts the picture at [url] on the book [key], on every device. False when it couldn't be loaded. */
export async function setCover(key: string, url: string, now = Date.now()): Promise<boolean> {
  const data = await coverData(url).catch(() => null)
  if (!data) return false
  const old = await db.bookCovers.get(key)
  await db.bookCovers.put({ key, url, ts: Math.max(now, (old?.ts ?? 0) + 1), data })
  syncSoon()
  return true
}

/** Fetches this device's copy of a cover another device picked (after sync2 stored its mark). */
export async function fillCoverData(key: string, url: string): Promise<void> {
  const data = await coverData(url).catch(() => null)
  if (!data) return
  const mark = await db.bookCovers.get(key)
  if (mark?.url === url) await db.bookCovers.update(key, { data })
}
