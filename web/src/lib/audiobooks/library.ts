/**
 * The Audiobooks tab's library. Books live in Dropbox's /Audiobooks folder (in the app folder):
 * a file there is a book, and a folder there is a book whose audio files play in name order
 * ("2" before "10", files before subfolders, as the Voice audiobook player does). Books stream
 * from Dropbox. Books can also be on this device only: files or a folder picked here are kept
 * in the browser's private storage (OPFS) and never uploaded unless asked; a Dropbox book can be
 * downloaded there too, to play offline.
 *
 * A one-file book's id is the file's Dropbox content hash; a folder's is a hash of its files'
 * hashes in play order. Positions (kind "audiopos") sync by that id, the same on every device.
 */
import { AUDIOBOOKS_FOLDER, contentHash, deleteFile, downloadRange, downloadStream, getDropbox, listTree, temporaryLink, uploadFile, type DropboxFile, type Progress } from '../dropbox'
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
    // A book first added from this device keeps the title read from its files
    if (moved) put.push({ ...old, dropboxPath: d.dropboxPath, tracks, title: old.described ? old.title : d.title })
  }
  const remove: string[] = []
  for (const b of have) {
    if (seen.has(b.id) || !b.dropboxPath) continue
    // Gone from Dropbox: forget it unless this device has its files, which stay as a local book
    if (onDevice(b)) put.push({ ...b, dropboxPath: null })
    else remove.push(b.id)
  }
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
    tags = await readTags(t.name, await trackReader(t))
  } catch (e) {
    console.warn('Could not read tags', t.name, e)
  }
  let duration = tags?.duration ?? null
  if (!duration) duration = await measure(await trackUrl(t)).catch(() => null)
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
  if (!first && b.tracks[0]) first = await trackReader(b.tracks[0]).then((r) => readTags(b.tracks[0].name, r)).catch(() => null)
  let cover: string | null = null
  if (first?.cover) cover = await smallCover(new Blob([first.cover.data.slice().buffer as ArrayBuffer], { type: first.cover.type }))
  const picture = localCovers.get(b.id)
  if (!cover && picture) cover = await smallCover(picture)
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

const safe = (s: string) => s.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150) || 'Audiobook'

/** Uploads a book on this device to Dropbox: one file as is, several into a folder named after the book. */
export async function uploadAudiobook(id: string, onProgress?: Progress): Promise<void> {
  const b = await db.audiobooks.get(id)
  if (!b) return
  const files: { path: string; file: File }[] = []
  for (const t of b.tracks) {
    const file = await localTrackFile(t)
    if (!file) throw new Error(`${t.name} is not on this device`)
    const rel = t.path.startsWith(LOCAL) ? t.path.slice(LOCAL.length) : t.name
    const parts = rel.split('/').map(safe)
    files.push({ path: b.tracks.length === 1 && !b.folder ? `${AUDIOBOOKS_FOLDER}/${safe(t.name)}` : `${AUDIOBOOKS_FOLDER}/${safe(b.title)}/${parts.join('/')}`, file })
  }
  const total = files.reduce((s, f) => s + f.file.size, 0)
  let before = 0
  for (const f of files) {
    await uploadFile(f.path, f.file, (done) => onProgress?.(before + done, total))
    before += f.file.size
  }
  await refreshAudiobooks()
}

/**
 * Deletes the book (its file, or its folder) from Dropbox for every device. When this device has
 * all its files, the book stays here, as a book only on this device.
 */
export async function deleteAudiobookFromDropbox(id: string): Promise<void> {
  const b = await db.audiobooks.get(id)
  if (!b?.dropboxPath) return
  await deleteFile(b.dropboxPath)
  if (onDevice(b)) await db.audiobooks.update(id, { dropboxPath: null })
  else await db.audiobooks.delete(id)
  await refreshAudiobooks()
}

// ---- On this device ----

/** Paths of tracks only on this device start with this, followed by the path in what was picked. */
export const LOCAL = 'local:'

/** Whether every file of the book is on this device. */
export const onDevice = (b: Pick<Audiobook, 'tracks'>) => b.tracks.length > 0 && b.tracks.every((t) => t.local)

export function localSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory
}

async function abDir(): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory()
  return root.getDirectoryHandle('audiobooks', { create: true })
}

/** A file is kept once, by content, whichever books share it. */
const localName = (t: Pick<AudioTrack, 'hash' | 'name'>) => `${t.hash}.${extOf(t.name) || 'audio'}`

/** This device's copy of a track, or null. */
export async function localTrackFile(t: AudioTrack): Promise<File | null> {
  if (!t.local) return null
  try {
    return await (await (await abDir()).getFileHandle(localName(t))).getFile()
  } catch {
    return null
  }
}

async function writeLocal(name: string, data: Blob | ReadableStream<Uint8Array>, onBytes?: (n: number) => void, signal?: AbortSignal) {
  navigator.storage.persist?.().catch(() => undefined)
  const d = await abDir()
  const tmp = `${name}.part`
  const h = await d.getFileHandle(tmp, { create: true })
  try {
    const w = await h.createWritable()
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, c) {
        onBytes?.(chunk.length)
        c.enqueue(chunk)
      },
    })
    await (data instanceof Blob ? data.stream() : data).pipeThrough(counter, { signal }).pipeTo(w, { signal })
    const moved = h as FileSystemFileHandle & { move?: (name: string) => Promise<void> }
    if (moved.move) await moved.move(name)
    else {
      const out = await (await d.getFileHandle(name, { create: true })).createWritable()
      await (await h.getFile()).stream().pipeTo(out)
      await d.removeEntry(tmp)
    }
  } catch (e) {
    await d.removeEntry(tmp).catch(() => undefined)
    throw e
  }
}

const objectUrls = new Map<string, string>()

/** Where the player loads a track from: this device's copy, else a Dropbox streaming link. */
export async function trackUrl(t: AudioTrack, fresh = false): Promise<string> {
  const file = await localTrackFile(t)
  if (file) {
    const key = localName(t)
    let url = objectUrls.get(key)
    if (!url) objectUrls.set(key, (url = URL.createObjectURL(file)))
    return url
  }
  if (t.path.startsWith(LOCAL)) throw new Error(`${t.name} is no longer on this device`)
  return temporaryLink(t.path, fresh)
}

async function trackReader(t: AudioTrack): Promise<RangeReader> {
  const file = await localTrackFile(t)
  if (file) return { size: file.size, read: async (offset, length) => new Uint8Array(await file.slice(offset, offset + length).arrayBuffer()) }
  return dropboxReader(t.path, t.size)
}

/** Pictures found next to local books' files, for their covers. */
const localCovers = new Map<string, Blob>()

/** A file picked on this device, with its path inside the picked folder (just its name for loose files). */
export interface Picked {
  rel: string
  file: File
}

/** Files from a folder picker (input webkitdirectory), with the picked folder's own name cut off. */
export function pickedFromFolder(files: File[]): { name: string; files: Picked[] } {
  const rels = files.map((f) => (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name)
  const name = rels[0]?.includes('/') ? rels[0].split('/')[0] : ''
  return { name, files: files.map((file, k) => ({ file, rel: name && rels[k].startsWith(`${name}/`) ? rels[k].slice(name.length + 1) : rels[k] })) }
}

/**
 * How picked files make books. Loose files, or a folder of files only: one book named [title]
 * (a folder of several .m4b files, each a whole book, makes one book each). A folder with
 * subfolders of audio: the same rule as Dropbox's /Audiobooks, each file and subfolder there is
 * one book.
 */
export function groupPicked(picked: Picked[], title: string): { title: string; folder: boolean; files: Picked[]; cover: Picked | null }[] {
  const audio = picked.filter((p) => isAudioName(p.file.name))
  const images = picked.filter((p) => IMAGE.test(p.file.name))
  const nested = audio.some((p) => p.rel.includes('/'))
  const allM4b = audio.length > 1 && audio.every((p) => extOf(p.file.name) === 'm4b')
  const coverOf = (list: Picked[], prefix: string) => {
    const top = list.filter((x) => x.rel.startsWith(prefix) && !x.rel.slice(prefix.length).includes('/'))
    return top.find((x) => /^(cover|folder|front)\./i.test(x.file.name)) ?? top[0] ?? null
  }
  if (!nested && !allM4b) {
    if (!audio.length) return []
    const sorted = [...audio].sort((a, b) => comparePaths(a.rel, b.rel))
    return [{ title: audio.length === 1 ? nameWithoutExt(audio[0].file.name) : title, folder: audio.length > 1, files: sorted, cover: coverOf(images, '') }]
  }
  const out: { title: string; folder: boolean; files: Picked[]; cover: Picked | null }[] = []
  const folders = new Map<string, Picked[]>()
  for (const p of audio) {
    const cut = p.rel.indexOf('/')
    if (cut < 0) out.push({ title: nameWithoutExt(p.file.name), folder: false, files: [p], cover: null })
    else {
      const name = p.rel.slice(0, cut)
      folders.set(name, [...(folders.get(name) ?? []), p])
    }
  }
  for (const [name, list] of folders) {
    const inner = list.map((p) => ({ ...p, rel: p.rel.slice(name.length + 1) })).sort((a, b) => comparePaths(a.rel, b.rel))
    const pics = images.filter((x) => x.rel.startsWith(`${name}/`)).map((x) => ({ ...x, rel: x.rel.slice(name.length + 1) }))
    out.push({ title: name.replace(/_/g, ' ').trim() || name, folder: true, files: inner, cover: coverOf(pics, '') ?? pics[0] ?? null })
  }
  return out
}

/**
 * Keeps picked files on this device as books (nothing goes to Dropbox). A book with the same
 * files as one in Dropbox becomes the same book, so its place syncs with other devices.
 * [onProgress] gets bytes done of all the bytes. Returns how many books were added.
 */
export async function addLocal(picked: Picked[], title: string, onProgress?: Progress, signal?: AbortSignal): Promise<number> {
  const groups = groupPicked(picked, title)
  if (!groups.length) throw new Error('Pick audio files: M4B, M4A, MP3, OGG, Opus or FLAC')
  const total = groups.reduce((s, g) => s + g.files.reduce((n, p) => n + p.file.size, 0), 0) * 2
  let done = 0
  const bytes = (n: number) => onProgress?.((done += n), total)
  for (const g of groups) {
    const tracks: AudioTrack[] = []
    for (const p of g.files) {
      const hash = await contentHash(p.file)
      bytes(p.file.size)
      tracks.push({ path: `${LOCAL}${p.rel}`, name: p.file.name, hash, size: p.file.size, duration: null, title: null, marks: [], scanned: false, local: true })
    }
    const id = g.folder ? await folderBookId(tracks.map((t) => t.hash)) : tracks[0].hash
    const d = await abDir()
    for (const [k, t] of tracks.entries()) {
      const exists = await d
        .getFileHandle(localName(t))
        .then((h) => h.getFile())
        .then((f) => f.size === t.size)
        .catch(() => false)
      if (!exists) await writeLocal(localName(t), g.files[k].file, bytes, signal)
      else bytes(t.size)
    }
    if (g.cover) localCovers.set(id, g.cover.file)
    const old = await db.audiobooks.get(id)
    if (old) {
      const byHash = new Map(tracks.map((t) => [t.hash, t]))
      await db.audiobooks.update(id, { tracks: old.tracks.map((t) => (byHash.has(t.hash) ? { ...t, local: true } : t)) })
      if (!old.cover && g.cover) await db.audiobooks.update(id, { cover: await smallCover(g.cover.file) })
    } else {
      await db.audiobooks.put({
        id,
        title: g.title,
        author: '',
        cover: null,
        dropboxPath: null,
        folder: g.folder,
        tracks,
        size: tracks.reduce((s, t) => s + t.size, 0),
        described: false,
        addedAt: Date.now(),
        lastOpenedAt: 0,
      })
      const b = await db.audiobooks.get(id)
      if (b) await describe(b, null).catch((e) => console.warn('Could not describe audiobook', g.title, e))
    }
  }
  return groups.length
}

/** Downloads a Dropbox book's files to this device, to play offline. */
export async function downloadAudiobook(id: string, onProgress?: Progress, signal?: AbortSignal): Promise<void> {
  const b = await db.audiobooks.get(id)
  if (!b) return
  let done = 0
  const total = b.size
  for (const t of b.tracks) {
    if (await localTrackFile(t)) {
      onProgress?.((done += t.size), total)
      continue
    }
    const { body } = await downloadStream(t.path, signal)
    await writeLocal(localName(t), body, (n) => onProgress?.((done += n), total), signal)
    await db.transaction('rw', db.audiobooks, async () => {
      const cur = await db.audiobooks.get(id)
      if (cur) await db.audiobooks.update(id, { tracks: cur.tracks.map((x) => (x.hash === t.hash ? { ...x, local: true } : x)) })
    })
  }
}

/**
 * Frees this device's copies. A book that is in Dropbox stays (it streams from there); one only
 * on this device is gone. Files another book still uses stay.
 */
export async function removeFromDevice(id: string): Promise<void> {
  const b = await db.audiobooks.get(id)
  if (!b) return
  const others = (await db.audiobooks.toArray()).filter((x) => x.id !== id)
  const shared = new Set(others.flatMap((x) => x.tracks.filter((t) => t.local).map((t) => t.hash)))
  const d = await abDir()
  for (const t of b.tracks) {
    if (!t.local || shared.has(t.hash)) continue
    await d.removeEntry(localName(t)).catch(() => undefined)
    const url = objectUrls.get(localName(t))
    if (url) URL.revokeObjectURL(url)
    objectUrls.delete(localName(t))
  }
  if (b.dropboxPath) await db.audiobooks.update(id, { tracks: b.tracks.map((t) => ({ ...t, local: false })) })
  else await db.audiobooks.delete(id)
}

/** Saves where a book is (and who saved it). */
export async function saveAudioPos(id: string, i: number, hash: string, ms: number, f: number): Promise<void> {
  const pos: AudioPos = { id, i, h: hash, ms: Math.max(0, Math.round(ms)), f: Math.round(f * 10000) / 10000, ts: Date.now(), ...thisDevice() }
  await db.audioPos.put(pos)
}
