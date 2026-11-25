/**
 * The Ebooks tab's library. Dropbox (/Ebooks in the app folder) is where books live for every
 * device; this browser keeps copies of the ones opened here in its private file system (OPFS), so
 * they open offline. A book's id is its Dropbox content hash, the same on every device, which is
 * what reading positions (kind "bookpos") and highlights are synced by.
 */
import { contentHash, deleteFile, downloadStream, EBOOKS_FOLDER, getDropbox, listFolder, uploadFile, type DropboxFile, type Progress } from '../dropbox'
import { db, type Book } from '../db'
import { thisDevice } from '../device'
import { loadFoliate } from './foliate'
import { authorString, langString } from './reader'

export { authorString, langString }

/** Formats foliate-js reads (PDF through PDF.js). */
export const BOOK_EXTENSIONS = ['epub', 'mobi', 'azw3', 'azw', 'kf8', 'fb2', 'fbz', 'cbz', 'pdf']
export const ACCEPT = BOOK_EXTENSIONS.map((e) => `.${e}`).join(',') + ',.fb2.zip'

export const BOOK_ITEM_PREFIX = 'ebook:\n'
/** Highlights of a book are stored like article highlights, under this item id. */
export const bookItemId = (id: string) => `${BOOK_ITEM_PREFIX}${id}`
export const bookIdOfItem = (itemId: string) => (itemId.startsWith(BOOK_ITEM_PREFIX) ? itemId.slice(BOOK_ITEM_PREFIX.length) : null)

export function extOf(name: string): string {
  const n = name.toLowerCase()
  if (n.endsWith('.fb2.zip')) return 'fbz'
  return n.match(/\.([a-z0-9]+)$/)?.[1] ?? ''
}
export const isBookName = (name: string) => BOOK_EXTENSIONS.includes(extOf(name))

/** "The_Time-Machine (2004).epub" → "The Time-Machine (2004)" */
export const titleFromName = (name: string) =>
  name
    .replace(/\.fb2\.zip$/i, '')
    .replace(/\.[a-z0-9]+$/i, '')
    .replace(/_/g, ' ')
    .trim() || 'Untitled'

export function ebooksSupported(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory
}

async function dir(): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory()
  return root.getDirectoryHandle('ebooks', { create: true })
}

const fileName = (b: Pick<Book, 'id' | 'ext'>) => `${b.id}.${b.ext || 'epub'}`

async function localFile(b: Book): Promise<File | null> {
  try {
    const h = await (await dir()).getFileHandle(fileName(b))
    const f = await h.getFile()
    // foliate-js picks the format by name for FB2 and comics
    return new File([f], `${b.title}.${b.ext}`, { type: f.type })
  } catch {
    return null
  }
}

async function writeLocal(b: Pick<Book, 'id' | 'ext'>, data: Blob | ReadableStream<Uint8Array>, total: number, onProgress?: Progress, signal?: AbortSignal) {
  navigator.storage.persist?.().catch(() => undefined)
  const d = await dir()
  const name = fileName(b)
  const tmp = `${name}.part`
  const h = await d.getFileHandle(tmp, { create: true })
  try {
    const w = await h.createWritable()
    const stream = data instanceof Blob ? data.stream() : data
    let done = 0
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, c) {
        done += chunk.length
        onProgress?.(done, total)
        c.enqueue(chunk)
      },
    })
    await stream.pipeThrough(counter, { signal }).pipeTo(w, { signal })
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

/** Title, author and a small cover, read with foliate-js. */
export async function describeFile(file: File): Promise<{ title: string; author: string; cover: string | null }> {
  const { makeBook } = await loadFoliate()
  const book = await makeBook(file)
  const title = langString(book.metadata?.title) || titleFromName(file.name)
  const author = authorString(book.metadata?.author)
  let cover: string | null = null
  try {
    const blob = await book.getCover?.()
    if (blob) cover = await smallCover(blob)
  } catch {
    // no cover
  }
  return { title, author, cover }
}

/** A cover scaled down to 240 px tall, as a JPEG data URL (small enough for the database). */
async function smallCover(blob: Blob): Promise<string | null> {
  const bmp = await createImageBitmap(blob).catch(() => null)
  if (!bmp) return null
  const h = Math.min(240, bmp.height)
  const w = Math.max(1, Math.round((bmp.width * h) / bmp.height))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, w, h)
  bmp.close()
  return canvas.toDataURL('image/jpeg', 0.8)
}

/** Books a listing of Dropbox's /Ebooks folder adds, updates or no longer has. */
export function mergeDropbox(books: Book[], files: DropboxFile[], now = Date.now()): { put: Book[]; remove: string[] } {
  const byId = new Map(books.map((b) => [b.id, b]))
  const seen = new Set<string>()
  const put: Book[] = []
  for (const f of files) {
    if (!isBookName(f.name) || !f.hash || seen.has(f.hash)) continue
    seen.add(f.hash)
    const b = byId.get(f.hash)
    if (!b) {
      put.push({ id: f.hash, title: titleFromName(f.name), author: '', ext: extOf(f.name), size: f.size, cover: null, dropboxPath: f.path, local: false, described: false, addedAt: Date.parse(f.modified) || now, lastOpenedAt: 0 })
    } else if (b.dropboxPath !== f.path) {
      put.push({ ...b, dropboxPath: f.path, title: b.described ? b.title : titleFromName(f.name) })
    }
  }
  const remove: string[] = []
  for (const b of books) {
    if (!b.dropboxPath || seen.has(b.id)) continue
    // Gone from Dropbox: forget it unless this device has a copy, which stays as a local book
    if (b.local) put.push({ ...b, dropboxPath: null })
    else remove.push(b.id)
  }
  return { put, remove }
}

let refreshing: Promise<void> | null = null

/** Reads the Dropbox folder again; uploads books only this device has. */
export function refreshFromDropbox(): Promise<void> {
  refreshing ??= (async () => {
    if (!(await getDropbox())) return
    const files = await listFolder(EBOOKS_FOLDER)
    const { put, remove } = mergeDropbox(await db.books.toArray(), files)
    await db.transaction('rw', db.books, async () => {
      await db.books.bulkPut(put)
      await db.books.bulkDelete(remove)
    })
    await uploadLocalOnly()
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

const uploading = new Set<string>()

/** Books added here before Dropbox was signed in go up now. */
async function uploadLocalOnly() {
  for (const b of await db.books.filter((b) => b.local && !b.dropboxPath).toArray()) {
    if (uploading.has(b.id)) continue
    uploading.add(b.id)
    try {
      await upload(b)
    } catch (e) {
      console.warn('Could not upload book to Dropbox', b.title, e)
    } finally {
      uploading.delete(b.id)
    }
  }
}

async function upload(b: Book, onProgress?: Progress) {
  const file = await localFile(b)
  if (!file) return
  const safe = `${b.title}${b.author ? ` - ${b.author}` : ''}`.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150)
  const up = await uploadFile(`${EBOOKS_FOLDER}/${safe}.${b.ext}`, file, onProgress)
  await db.books.update(b.id, { dropboxPath: up.path })
}

/** Adds a book file from this device: kept here, and uploaded to Dropbox when signed in. */
export async function importBook(file: File, onProgress?: Progress): Promise<Book> {
  const ext = extOf(file.name)
  if (!BOOK_EXTENSIONS.includes(ext)) throw new Error(`${file.name}: Feeder reads EPUB, MOBI, AZW3, FB2, CBZ and PDF books`)
  const id = await contentHash(file)
  const existing = await db.books.get(id)
  const meta = await describeFile(file)
  if (!existing?.local) await writeLocal({ id, ext }, file, file.size)
  const book: Book = {
    id,
    ext,
    size: file.size,
    ...meta,
    dropboxPath: existing?.dropboxPath ?? null,
    local: true,
    described: true,
    addedAt: existing?.addedAt ?? Date.now(),
    lastOpenedAt: existing?.lastOpenedAt ?? 0,
  }
  await db.books.put(book)
  if (!book.dropboxPath && (await getDropbox())) {
    uploading.add(id)
    try {
      await upload(book, onProgress)
    } catch (e) {
      console.warn('Could not upload book to Dropbox', e)
    } finally {
      uploading.delete(id)
    }
  }
  return (await db.books.get(id))!
}

/** The book's file, downloading it from Dropbox first when it isn't on this device. */
export async function bookFile(id: string, onProgress?: Progress, signal?: AbortSignal): Promise<File> {
  let b = await db.books.get(id)
  if (!b) throw new Error('This book is no longer in the library')
  let file = b.local ? await localFile(b) : null
  if (!file) {
    if (!b.dropboxPath) throw new Error('This book is not on this device, and not in Dropbox')
    const { body, size } = await downloadStream(b.dropboxPath, signal)
    await writeLocal(b, body, size || b.size, onProgress, signal)
    await db.books.update(id, { local: true })
    b = (await db.books.get(id))!
    file = await localFile(b)
    if (!file) throw new Error('The download could not be saved')
  }
  if (!b.described) {
    try {
      const meta = await describeFile(file)
      await db.books.update(id, { ...meta, described: true })
      file = new File([file], `${meta.title}.${b.ext}`, { type: file.type })
    } catch {
      // opens anyway; the reader shows its own error if it really can't
    }
  }
  await db.books.update(id, { lastOpenedAt: Date.now() })
  return file
}

/** Frees the space on this device; the book stays in Dropbox. */
export async function removeFromDevice(id: string): Promise<void> {
  const b = await db.books.get(id)
  if (!b) return
  await (await dir()).removeEntry(fileName(b)).catch(() => undefined)
  if (b.dropboxPath) await db.books.update(id, { local: false })
  else await db.books.delete(id)
}

/** Deletes the book from Dropbox and this device. Positions and highlights stay. */
export async function deleteBook(id: string): Promise<void> {
  const b = await db.books.get(id)
  if (!b) return
  if (b.dropboxPath) await deleteFile(b.dropboxPath)
  await (await dir()).removeEntry(fileName(b)).catch(() => undefined)
  await db.books.delete(id)
}

/** Where a book was left, as saved here or synced from another device, with its page when known. */
export async function savePosition(id: string, cfi: string, f: number, pages?: { page: number; pages: number } | null): Promise<void> {
  await db.bookPos.put({ id, cfi, f: Math.round(f * 10000) / 10000, ...(pages ? { p: pages.page, n: pages.pages } : {}), ts: Date.now(), ...thisDevice() })
}
