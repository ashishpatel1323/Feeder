/**
 * The Audiobooks tab's library. Books live in Dropbox's /Audiobooks folder (in the app folder):
 * a file there is a book, and a folder there is a book whose audio files play in name order
 * ("2" before "10", files before subfolders, as the Voice audiobook player does). Books stream
 * from Dropbox; nothing is copied to this device.
 *
 * A one-file book's id is the file's Dropbox content hash; a folder's is a hash of its files'
 * hashes in play order. Positions (kind "audiopos") sync by that id, the same on every device.
 */
import { AUDIOBOOKS_FOLDER, downloadRange, downloadStream, getDropbox, listTree, temporaryLink, uploadFile, type DropboxFile, type Progress } from '../dropbox'
import { db, type AudioPos, type AudioTrack, type Audiobook } from '../db'
import { thisDevice } from '../device'
import { readTags, type FileTags, type RangeReader } from './tags'
import { nameWithoutExt } from './timeline'

export const AUDIO_EXTENSIONS = ['m4b', 'm4a', 'mp4', 'aac', 'mp3', 'ogg', 'oga', 'opus', 'flac', 'wav', 'webm']
export const ACCEPT_AUDIO = AUDIO_EXTENSIONS.map((e) => `.${e}`).join(',') + ',audio/*'
const IMAGE = /\.(jpe?g|png|webp)$/i

export const extOf = (name: string) => name.toLowerCase().match(/\.([a-z0-9]+)$/)?.[1] ?? ''
export const isAudioName = (name: string) => AUDIO_EXTENSIONS.includes(extOf(name))

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

/**
 * Play order of files inside one book, by their paths relative to the book's folder: natural
 * order, and at each level the files come before the subfolders.
 */
export function comparePaths(a: string, b: string): number {
  const pa = a.split('/')
  const pb = b.split('/')
  for (let k = 0; k < Math.min(pa.length, pb.length); k++) {
    const aFile = k === pa.length - 1
    const bFile = k === pb.length - 1
    if (aFile !== bFile) return aFile ? -1 : 1
    const c = collator.compare(pa[k], pb[k])
    if (c) return c
  }
  return pa.length - pb.length
}

async function sha256Hex(text: string): Promise<string> {
  const out = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
  return Array.from(out, (x) => x.toString(16).padStart(2, '0')).join('')
}

/** A folder book's id: the same files in the same order give the same id, whatever the folder is called. */
export const folderBookId = (hashes: string[]) => sha256Hex(`audiobook\n${hashes.join('\n')}`)

export interface BookDraft {
  id: string
  title: string
  dropboxPath: string
  folder: boolean
  tracks: AudioTrack[]
  size: number
  /** A picture in the folder (cover.jpg…), for the cover when the files have none */
  coverPath: string | null
  addedAt: number
}

const newTrack = (f: DropboxFile): AudioTrack => ({ path: f.path, name: f.name, hash: f.hash, size: f.size, duration: null, title: null, marks: [], scanned: false })

/** Books in a listing of /Audiobooks (every file, subfolders included). */
export async function booksInListing(files: DropboxFile[], root = AUDIOBOOKS_FOLDER): Promise<BookDraft[]> {
  const prefix = root.toLowerCase() + '/'
  const singles: DropboxFile[] = []
  const folders = new Map<string, { name: string; files: { rel: string; f: DropboxFile }[]; images: { rel: string; f: DropboxFile }[] }>()
  for (const f of files) {
    if (!f.path.toLowerCase().startsWith(prefix)) continue
    const rel = f.path.slice(prefix.length)
    const cut = rel.indexOf('/')
    if (cut < 0) {
      if (isAudioName(f.name) && f.hash) singles.push(f)
      continue
    }
    const name = rel.slice(0, cut)
    const key = name.toLowerCase()
    const entry = folders.get(key) ?? { name, files: [], images: [] }
    folders.set(key, entry)
    const inner = rel.slice(cut + 1)
    if (isAudioName(f.name) && f.hash) entry.files.push({ rel: inner, f })
    else if (IMAGE.test(f.name)) entry.images.push({ rel: inner, f })
  }
  const out: BookDraft[] = singles.map((f) => ({
    id: f.hash,
    title: nameWithoutExt(f.name),
    dropboxPath: f.path,
    folder: false,
    tracks: [newTrack(f)],
    size: f.size,
    coverPath: null,
    addedAt: Date.parse(f.modified) || Date.now(),
  }))
  for (const { name, files: list, images } of folders.values()) {
    if (!list.length) continue
    list.sort((a, b) => comparePaths(a.rel, b.rel))
    const tracks = list.map((x) => newTrack(x.f))
    // Prefer cover.jpg / folder.jpg at the top, else any picture at the top, else any picture
    const top = images.filter((x) => !x.rel.includes('/'))
    const named = top.find((x) => /^(cover|folder|front)\./i.test(x.f.name))
    const cover = named ?? top[0] ?? images[0] ?? null
    out.push({
      id: await folderBookId(tracks.map((t) => t.hash)),
      title: name.replace(/_/g, ' ').trim() || name,
      dropboxPath: `${list[0].f.path.slice(0, prefix.length)}${name}`,
      folder: true,
      tracks,
      size: tracks.reduce((s, t) => s + t.size, 0),
      coverPath: cover?.f.path ?? null,
      addedAt: Math.max(...list.map((x) => Date.parse(x.f.modified) || 0)) || Date.now(),
    })
  }
  return out
}

/** How the library changes after a listing: new books, moved ones, and ones no longer there. */
export function mergeBooks(have: Audiobook[], drafts: BookDraft[]): { put: Audiobook[]; remove: string[] } {
  const byId = new Map(have.map((b) => [b.id, b]))
  const seen = new Set<string>()
  const put: Audiobook[] = []
  for (const d of drafts) {
    if (seen.has(d.id)) continue
    seen.add(d.id)
    const old = byId.get(d.id)
    if (!old) {
      put.push({ id: d.id, title: d.title, author: '', cover: null, dropboxPath: d.dropboxPath, folder: d.folder, tracks: d.tracks, size: d.size, described: false, addedAt: d.addedAt, lastOpenedAt: 0 })
      continue
    }
    // Same files: keep what was read from them, follow renames and moves
    const known = new Map(old.tracks.map((t) => [t.hash, t]))
    const tracks = d.tracks.map((t) => ({ ...(known.get(t.hash) ?? t), path: t.path, name: t.name }))
    const moved = old.dropboxPath !== d.dropboxPath || tracks.some((t, i) => t.path !== old.tracks[i]?.path)
    if (moved) put.push({ ...old, dropboxPath: d.dropboxPath, tracks, title: old.described ? old.title : d.title })
  }
  const remove = have.filter((b) => !seen.has(b.id)).map((b) => b.id)
  return { put, remove }
}

let refreshing: Promise<void> | null = null
const coverPaths = new Map<string, string>()

/** Reads Dropbox's /Audiobooks again. */
export function refreshAudiobooks(): Promise<void> {
  refreshing ??= (async () => {
    if (!(await getDropbox())) return
    const drafts = await booksInListing(await listTree(AUDIOBOOKS_FOLDER))
    for (const d of drafts) if (d.coverPath) coverPaths.set(d.id, d.coverPath)
    const { put, remove } = mergeBooks(await db.audiobooks.toArray(), drafts)
    await db.transaction('rw', db.audiobooks, async () => {
      await db.audiobooks.bulkPut(put)
      await db.audiobooks.bulkDelete(remove)
    })
    // Covers for the books that have none yet, a few at a time
    void describeSome()
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

/** A reader over a Dropbox file that fetches only the ranges asked for (with a small cache). */
export function dropboxReader(path: string, size: number): RangeReader {
  const cache = new Map<number, Promise<Uint8Array>>()
  const BLOCK = 64 * 1024
  return {
    size,
    async read(offset, length) {
      length = Math.max(0, Math.min(length, size - offset))
      if (length > BLOCK * 4) return downloadRange(path, offset, length)
      const first = Math.floor(offset / BLOCK)
      const last = Math.floor((offset + length - 1) / BLOCK)
      const parts: Uint8Array[] = []
      for (let k = first; k <= last; k++) {
        if (!cache.has(k)) cache.set(k, downloadRange(path, k * BLOCK, Math.min(BLOCK, size - k * BLOCK)))
        parts.push(await cache.get(k)!)
      }
      const all = new Uint8Array(parts.reduce((s, p) => s + p.length, 0))
      let at = 0
      for (const p of parts) {
        all.set(p, at)
        at += p.length
      }
      return all.subarray(offset - first * BLOCK, offset - first * BLOCK + length)
    },
  }
}

/** A cover picture scaled down to 360 px, as a JPEG data URL. */
async function smallCover(blob: Blob): Promise<string | null> {
  const bmp = await createImageBitmap(blob).catch(() => null)
  if (!bmp) return null
  const side = Math.min(360, Math.max(bmp.width, bmp.height))
  const scale = side / Math.max(bmp.width, bmp.height)
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bmp.width * scale))
  canvas.height = Math.max(1, Math.round(bmp.height * scale))
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height)
  bmp.close()
  return canvas.toDataURL('image/jpeg', 0.82)
}

/** Measures a file's length by loading only its header in an audio element. */
function measure(url: string): Promise<number | null> {
  return new Promise((resolve) => {
    const a = new Audio()
    a.preload = 'metadata'
    const done = (v: number | null) => {
      clearTimeout(timer)
      a.removeAttribute('src')
      a.load()
      resolve(v)
    }
    const timer = setTimeout(() => done(null), 30_000)
    a.addEventListener('loadedmetadata', () => done(Number.isFinite(a.duration) && a.duration > 0 ? a.duration : null))
    a.addEventListener('error', () => done(null))
    a.src = url
  })
}

async function scanTrack(t: AudioTrack): Promise<{ track: AudioTrack; tags: FileTags | null }> {
  let tags: FileTags | null = null
  try {
    tags = await readTags(t.name, dropboxReader(t.path, t.size))
  } catch (e) {
    console.warn('Could not read tags', t.name, e)
  }
  let duration = tags?.duration ?? null
  if (!duration) duration = await measure(await temporaryLink(t.path)).catch(() => null)
  return { track: { ...t, duration, title: tags?.title ?? null, marks: tags?.chapters ?? [], scanned: true }, tags }
}

const scanning = new Map<string, Promise<Audiobook | null>>()

/**
 * Reads the files' lengths, chapters and tags (once per book on this device), and the title,
 * author and cover. [onProgress] gets files done and files in all.
 */
export function scanBook(id: string, onProgress?: Progress): Promise<Audiobook | null> {
  const running = scanning.get(id)
  if (running) return running
  const job = (async () => {
    let b = await db.audiobooks.get(id)
    if (!b) return null
    const todo = b.tracks.map((t, i) => ({ t, i })).filter((x) => !x.t.scanned)
    let done = b.tracks.length - todo.length
    onProgress?.(done, b.tracks.length)
    let firstTags: FileTags | null = null
    // A few at a time: each is a couple of small requests
    const queue = [...todo]
    const workers = Array.from({ length: Math.min(4, queue.length) }, async () => {
      for (let x = queue.shift(); x; x = queue.shift()) {
        const { track, tags } = await scanTrack(x.t)
        if (x.i === 0) firstTags = tags
        // Read and write in one transaction: the workers finish at the same time
        const gone = await db.transaction('rw', db.audiobooks, async () => {
          const cur = await db.audiobooks.get(id)
          if (!cur) return true
          const tracks = cur.tracks.map((t) => (t.hash === track.hash && t.path === track.path ? { ...track, path: t.path, name: t.name } : t))
          await db.audiobooks.update(id, { tracks })
          return false
        })
        if (gone) return
        onProgress?.(++done, b!.tracks.length)
      }
    })
    await Promise.all(workers)
    const after = await db.audiobooks.get(id)
    if (after && !after.described) await describe(after, firstTags)
    return (await db.audiobooks.get(id)) ?? null
  })().finally(() => scanning.delete(id))
  scanning.set(id, job)
  return job
}

/** Title, author and cover from the first file's tags or the folder's picture. */
async function describe(b: Audiobook, first: FileTags | null) {
  if (!first && b.tracks[0]) first = await readTags(b.tracks[0].name, dropboxReader(b.tracks[0].path, b.tracks[0].size)).catch(() => null)
  let cover: string | null = null
  if (first?.cover) cover = await smallCover(new Blob([first.cover.data.slice().buffer as ArrayBuffer], { type: first.cover.type }))
  const coverPath = coverPaths.get(b.id)
  if (!cover && coverPath) {
    try {
      const { body } = await downloadStream(coverPath)
      cover = await smallCover(await new Response(body).blob())
    } catch {
      // no cover
    }
  }
  // A folder of chapters names the book by the album; one file by its title
  const title = (b.folder ? first?.album || null : first?.title || first?.album || null) || b.title
  await db.audiobooks.update(b.id, { title, author: first?.artist || b.author, cover: cover ?? b.cover, described: true })
}

/** Covers and titles for books not described yet, one at a time, in the background. */
async function describeSome() {
  for (const b of (await db.audiobooks.filter((b) => !b.described).toArray()).slice(0, 12)) {
    try {
      await describe(b, null)
    } catch (e) {
      console.warn('Could not describe audiobook', b.title, e)
    }
  }
}

/** Uploads files from this device as a book: one file as is, several into a folder named [title]. */
export async function uploadAudiobook(files: File[], title: string, onProgress?: Progress): Promise<void> {
  const list = files.filter((f) => isAudioName(f.name)).sort((a, b) => comparePaths(a.name, b.name))
  if (!list.length) throw new Error('Pick audio files: M4B, M4A, MP3, OGG, Opus or FLAC')
  const safe = (s: string) => s.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150) || 'Audiobook'
  const total = list.reduce((s, f) => s + f.size, 0)
  let before = 0
  for (const f of list) {
    const path = list.length === 1 ? `${AUDIOBOOKS_FOLDER}/${safe(f.name)}` : `${AUDIOBOOKS_FOLDER}/${safe(title)}/${safe(f.name)}`
    await uploadFile(path, f, (done) => onProgress?.(before + done, total))
    before += f.size
  }
  await refreshAudiobooks()
}

/** Saves where a book is (and who saved it). */
export async function saveAudioPos(id: string, i: number, hash: string, ms: number, f: number): Promise<void> {
  const pos: AudioPos = { id, i, h: hash, ms: Math.max(0, Math.round(ms)), f: Math.round(f * 10000) / 10000, ts: Date.now(), ...thisDevice() }
  await db.audioPos.put(pos)
}
