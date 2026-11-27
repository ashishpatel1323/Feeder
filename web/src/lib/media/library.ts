/**
 * Library > Player: any audio or video file, from Dropbox's /Media folder (in the app folder) or
 * kept on this device, the same way the Audiobooks tab keeps books. Unlike audiobooks, every file
 * is its own item and folders stay folders: /Media/Talks/2024/keynote.mp4 shows inside Talks › 2024.
 * Files in a folder play in natural name order ("2" before "10"), one after the other.
 *
 * An item's id is its Dropbox content hash (the same on every device), so where it was left syncs
 * as kind "audiopos" (file 0, like a one-file audiobook) and the Android app's Player tab
 * (model/media/MediaLibrary.kt) shows the same place. A subtitle file next to a video (same name,
 * .vtt or .srt, also "name.en.srt") is shown with it.
 */
import { MEDIA_FOLDER, contentHash, deleteFile, downloadStream, getDropbox, listTree, temporaryLink, uploadFile, type DropboxFile, type Progress } from '../dropbox'
import { db, type AudioTrack, type Audiobook, type MediaFile } from '../db'
import { comparePaths, extOf, localSupported, type Picked } from '../audiobooks/library'
import { nameWithoutExt } from '../audiobooks/timeline'

export { localSupported }

export const VIDEO_EXTENSIONS = ['mp4', 'm4v', 'mkv', 'webm', 'mov', 'avi', '3gp', 'ts']
export const AUDIO_EXTENSIONS = ['mp3', 'm4a', 'm4b', 'aac', 'opus', 'ogg', 'oga', 'flac', 'wav', 'wma']
const SUB_EXTENSIONS = ['vtt', 'srt']
export const ACCEPT_MEDIA = [...AUDIO_EXTENSIONS, ...VIDEO_EXTENSIONS, ...SUB_EXTENSIONS].map((e) => `.${e}`).join(',') + ',audio/*,video/*'

/** Formats a browser can't play (Chrome and Safari): shown as "Android only" on the web. */
const BROWSER_CANT = ['mkv', 'avi', 'wma', '3gp', 'ts']

export function kindOf(name: string): 'audio' | 'video' | null {
  const e = extOf(name)
  if (VIDEO_EXTENSIONS.includes(e)) return 'video'
  if (AUDIO_EXTENSIONS.includes(e)) return 'audio'
  return null
}

export const isSubtitleName = (name: string) => SUB_EXTENSIONS.includes(extOf(name))

/** Whether this browser can probably play it (mkv and friends: no). */
export function playsHere(name: string): boolean {
  return !BROWSER_CANT.includes(extOf(name))
}

/** "02 - The Road_South.mp4" → "02 - The Road South" */
export const titleOf = (name: string) => nameWithoutExt(name)

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

/** The folder part of a path relative to /Media (or to what was picked): "Talks/2024" for "Talks/2024/a.mp4". */
const dirOf = (rel: string) => (rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '')
const baseOf = (rel: string) => rel.slice(rel.lastIndexOf('/') + 1)
/** "keynote.en.srt" and "keynote.srt" both belong to "keynote.mp4" */
const subStem = (name: string) => name.replace(/\.(vtt|srt)$/i, '').replace(/\.[a-z]{2,3}(-[a-z]{2,4})?$/i, '')
const mediaStem = (name: string) => name.replace(/\.[a-z0-9]+$/i, '')

/** A file found in a listing of /Media, before what is known about it is merged in. */
export interface MediaDraft {
  id: string
  name: string
  folder: string
  dropboxPath: string
  size: number
  kind: 'audio' | 'video'
  subs: string | null
  addedAt: number
}

/**
 * Media files in a listing of /Media (every file, subfolders included). A file that is there twice
 * (the same content in two folders) shows once, where it is first in name order.
 */
export function mediaInListing(files: DropboxFile[], root = MEDIA_FOLDER): MediaDraft[] {
  const prefix = root.toLowerCase() + '/'
  const subs = new Map<string, string>()
  const media: { rel: string; f: DropboxFile; kind: 'audio' | 'video' }[] = []
  for (const f of files) {
    if (!f.path.toLowerCase().startsWith(prefix)) continue
    const rel = f.path.slice(prefix.length)
    if (isSubtitleName(f.name)) {
      const key = `${dirOf(rel).toLowerCase()}/${subStem(f.name).toLowerCase()}`
      // A .vtt beats an .srt; the first one found otherwise
      if (!subs.has(key) || extOf(f.name) === 'vtt') subs.set(key, f.path)
      continue
    }
    const kind = kindOf(f.name)
    if (kind && f.hash) media.push({ rel, f, kind })
  }
  media.sort((a, b) => comparePaths(a.rel, b.rel))
  const seen = new Set<string>()
  const out: MediaDraft[] = []
  for (const { rel, f, kind } of media) {
    if (seen.has(f.hash)) continue
    seen.add(f.hash)
    const folder = dirOf(rel)
    out.push({
      id: f.hash,
      name: f.name,
      folder,
      dropboxPath: f.path,
      size: f.size,
      kind,
      subs: subs.get(`${folder.toLowerCase()}/${mediaStem(f.name).toLowerCase()}`) ?? null,
      addedAt: Date.parse(f.modified) || Date.now(),
    })
  }
  return out
}

/** How the list changes after a listing: new files, moved ones, and ones no longer there (kept when on this device). */
export function mergeMedia(have: MediaFile[], drafts: MediaDraft[]): { put: MediaFile[]; remove: string[] } {
  const byId = new Map(have.map((m) => [m.id, m]))
  const seen = new Set<string>()
  const put: MediaFile[] = []
  for (const d of drafts) {
    seen.add(d.id)
    const old = byId.get(d.id)
    if (!old) {
      put.push({ ...d, duration: null, local: false, lastOpenedAt: 0 })
      continue
    }
    // A file first added on this device keeps its folder there until it is in Dropbox
    const subs = d.subs ?? (old.subs?.startsWith(LOCAL) ? old.subs : null)
    if (old.dropboxPath !== d.dropboxPath || old.folder !== d.folder || old.name !== d.name || old.subs !== subs) put.push({ ...old, dropboxPath: d.dropboxPath, folder: d.folder, name: d.name, subs })
  }
  const remove: string[] = []
  for (const m of have) {
    if (seen.has(m.id) || !m.dropboxPath) continue
    if (m.local) put.push({ ...m, dropboxPath: null, subs: m.subs?.startsWith(LOCAL) ? m.subs : null })
    else remove.push(m.id)
  }
  return { put, remove }
}

export interface MediaFolder {
  name: string
  /** Its path, as in [MediaFile.folder] */
  path: string
  /** Files in it and in its subfolders */
  count: number
  size: number
}

/** What is inside [path]: its subfolders (with how much is in each) and its own files, both in name order. */
export function folderView(items: MediaFile[], path: string): { folders: MediaFolder[]; files: MediaFile[] } {
  const prefix = path ? `${path}/` : ''
  const folders = new Map<string, MediaFolder>()
  const files: MediaFile[] = []
  for (const m of items) {
    if (m.folder === path) {
      files.push(m)
      continue
    }
    if (path && !m.folder.startsWith(prefix)) continue
    const name = m.folder.slice(prefix.length).split('/')[0]
    if (!name) continue
    const f = folders.get(name) ?? { name, path: prefix + name, count: 0, size: 0 }
    f.count++
    f.size += m.size
    folders.set(name, f)
  }
  return {
    folders: [...folders.values()].sort((a, b) => collator.compare(a.name, b.name)),
    files: files.sort((a, b) => collator.compare(a.name, b.name)),
  }
}

/** Every file under [path], subfolders included, in play order (files before subfolders at each level). */
export function filesUnder(items: MediaFile[], path: string): MediaFile[] {
  const prefix = path ? `${path}/` : ''
  return items
    .filter((m) => m.folder === path || !path || m.folder.startsWith(prefix))
    .sort((a, b) => comparePaths(a.folder ? `${a.folder}/${a.name}` : a.name, b.folder ? `${b.folder}/${b.name}` : b.name))
}

/** Converts SubRip (.srt) text to WebVTT, which a <track> element reads. */
export function srtToVtt(srt: string): string {
  const body = srt
    .replace(/^﻿/, '')
    .replace(/\r\n?/g, '\n')
    // 00:00:01,500 --> 00:00:04,000 becomes 00:00:01.500 --> 00:00:04.000
    .replace(/(\d{1,2}:\d{2}:\d{2}),(\d{1,3})/g, '$1.$2')
    // Cue numbers on their own line before a timing line
    .replace(/(^|\n)\d+\n(?=\d{1,2}:\d{2}:\d{2}\.\d)/g, '$1')
    .trim()
  return `WEBVTT\n\n${body}\n`
}

// ---- Dropbox ----

let refreshing: Promise<void> | null = null

/** Reads Dropbox's /Media again. */
export function refreshMedia(): Promise<void> {
  refreshing ??= (async () => {
    if (!(await getDropbox())) return
    const drafts = mediaInListing(await listTree(MEDIA_FOLDER))
    const { put, remove } = mergeMedia(await db.media.toArray(), drafts)
    await db.transaction('rw', db.media, async () => {
      await db.media.bulkPut(put)
      await db.media.bulkDelete(remove)
    })
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

const safe = (s: string) => s.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150) || 'File'

/** Where a file on this device goes in Dropbox: the same folder under /Media. */
export function uploadPath(m: Pick<MediaFile, 'folder' | 'name'>): string {
  const dir = m.folder ? m.folder.split('/').map(safe).join('/') + '/' : ''
  return `${MEDIA_FOLDER}/${dir}${safe(m.name)}`
}

/** Puts a file that is only on this device into Dropbox › Apps › Feeder › Media, in its folder. */
export async function uploadMedia(id: string, onProgress?: Progress): Promise<void> {
  const m = await db.media.get(id)
  if (!m) return
  const file = await localMediaFile(m)
  if (!file) throw new Error(`${m.name} is not on this device`)
  const path = uploadPath(m)
  const subs = m.subs?.startsWith(LOCAL) ? await localByName(m.subs.slice(LOCAL.length)) : null
  // uploadFile goes on from the last piece that got there, after a failure
  await uploadFile(path, file, onProgress)
  if (subs) await uploadFile(path.replace(/\.[a-z0-9]+$/i, '') + (extOf(m.subs!) === 'srt' ? '.srt' : '.vtt'), subs).catch((e) => console.warn('Subtitles not uploaded', e))
  await db.media.update(id, { dropboxPath: path })
  await refreshMedia()
}

/** Deletes the file from Dropbox for every device. When this device has a copy, it stays here. */
export async function deleteMediaFromDropbox(id: string): Promise<void> {
  const m = await db.media.get(id)
  if (!m?.dropboxPath) return
  await deleteFile(m.dropboxPath)
  if (m.subs && !m.subs.startsWith(LOCAL)) await deleteFile(m.subs).catch(() => undefined)
  if (m.local) await db.media.update(id, { dropboxPath: null, subs: m.subs?.startsWith(LOCAL) ? m.subs : null })
  else await db.media.delete(id)
  await refreshMedia()
}

// ---- On this device ----

/** Subtitles kept on this device are "local:<stored name>". */
export const LOCAL = 'local:'

async function mediaDir(): Promise<FileSystemDirectoryHandle> {
  const root = await navigator.storage.getDirectory()
  return root.getDirectoryHandle('media', { create: true })
}

/** A file is kept once, by content. */
const storedName = (m: Pick<MediaFile, 'id' | 'name'>) => `${m.id}.${extOf(m.name) || 'media'}`

async function localByName(name: string): Promise<File | null> {
  try {
    return await (await (await mediaDir()).getFileHandle(name)).getFile()
  } catch {
    return null
  }
}

export async function localMediaFile(m: MediaFile): Promise<File | null> {
  if (!m.local) return null
  return localByName(storedName(m))
}

async function writeLocal(name: string, data: Blob | ReadableStream<Uint8Array>, onBytes?: (n: number) => void, signal?: AbortSignal) {
  navigator.storage.persist?.().catch(() => undefined)
  const d = await mediaDir()
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

/** Where the player loads a file from: this device's copy, else a Dropbox streaming link. */
export async function mediaUrl(m: MediaFile, fresh = false): Promise<string> {
  const file = await localMediaFile(m)
  if (file) {
    const key = storedName(m)
    let url = objectUrls.get(key)
    if (!url) objectUrls.set(key, (url = URL.createObjectURL(new Blob([file], { type: file.type || mimeOf(m) }))))
    return url
  }
  if (!m.dropboxPath) throw new Error(`${m.name} is no longer on this device`)
  return temporaryLink(m.dropboxPath, fresh)
}

function mimeOf(m: Pick<MediaFile, 'name' | 'kind'>): string {
  const e = extOf(m.name)
  if (e === 'webm') return `${m.kind}/webm`
  if (e === 'mov') return 'video/quicktime'
  if (e === 'mp3') return 'audio/mpeg'
  if (e === 'm4a' || e === 'm4b') return 'audio/mp4'
  if (e === 'opus' || e === 'ogg' || e === 'oga') return 'audio/ogg'
  return m.kind === 'video' ? 'video/mp4' : `audio/${e || 'mpeg'}`
}

const subUrls = new Map<string, string>()

/** The subtitles as a WebVTT blob link for a <track>, or null when there are none (or they can't be read). */
export async function subtitlesUrl(m: MediaFile): Promise<string | null> {
  if (!m.subs) return null
  const known = subUrls.get(m.subs)
  if (known) return known
  try {
    let text: string
    if (m.subs.startsWith(LOCAL)) {
      const f = await localByName(m.subs.slice(LOCAL.length))
      if (!f) return null
      text = await f.text()
    } else {
      const { body } = await downloadStream(m.subs)
      text = await new Response(body).text()
    }
    const vtt = /^﻿?WEBVTT/.test(text) ? text : srtToVtt(text)
    const url = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }))
    subUrls.set(m.subs, url)
    return url
  } catch (e) {
    console.warn('Could not read subtitles', m.subs, e)
    return null
  }
}

/** Files from a folder picker, with the picked folder's own name kept as the top folder. */
export function pickedMediaFolder(files: File[]): Picked[] {
  return files.map((file) => ({ file, rel: (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name }))
}

/**
 * Keeps picked files on this device (nothing goes to Dropbox). A picked folder keeps its folders;
 * loose files go at the top. A file that is also in Dropbox becomes the same item, so its place
 * syncs. [onProgress] gets bytes done of all the bytes. Returns how many files were added.
 */
export async function addLocalMedia(picked: Picked[], onProgress?: Progress, signal?: AbortSignal): Promise<number> {
  return (await addLocalMediaIds(picked, onProgress, signal)).length
}

/** [addLocalMedia], returning the ids of the files added (in the order picked). */
export async function addLocalMediaIds(picked: Picked[], onProgress?: Progress, signal?: AbortSignal): Promise<string[]> {
  const media = picked.filter((p) => kindOf(p.file.name))
  const subs = picked.filter((p) => isSubtitleName(p.file.name))
  if (!media.length) throw new Error('Pick audio or video files: MP3, M4A, Opus, FLAC, MP4, WebM, MOV…')
  const total = media.reduce((n, p) => n + p.file.size, 0) * 2
  let done = 0
  const bytes = (n: number) => onProgress?.((done += n), total)
  const d = await mediaDir()
  const ids: string[] = []
  for (const p of media) {
    const id = await contentHash(p.file)
    ids.push(id)
    bytes(p.file.size)
    const folder = dirOf(p.rel)
    const name = baseOf(p.rel) || p.file.name
    const kind = kindOf(name)!
    const m = { id, name } as MediaFile
    const exists = await d
      .getFileHandle(storedName(m))
      .then((h) => h.getFile())
      .then((f) => f.size === p.file.size)
      .catch(() => false)
    if (!exists) await writeLocal(storedName(m), p.file, bytes, signal)
    else bytes(p.file.size)
    // Subtitles next to it in what was picked
    const sub = subs.find((s) => dirOf(s.rel) === folder && subStem(baseOf(s.rel)).toLowerCase() === mediaStem(name).toLowerCase())
    let subsKey: string | null = null
    if (sub) {
      const subName = `${id}.${extOf(sub.file.name)}`
      await writeLocal(subName, sub.file)
      subsKey = `${LOCAL}${subName}`
    }
    const old = await db.media.get(id)
    if (old) await db.media.update(id, { local: true, subs: old.subs ?? subsKey })
    else await db.media.put({ id, name, folder, dropboxPath: null, size: p.file.size, kind, duration: null, subs: subsKey, local: true, addedAt: Date.now(), lastOpenedAt: 0 })
  }
  return ids
}

/** Downloads a Dropbox file to this device, to play offline. */
export async function downloadMedia(id: string, onProgress?: Progress, signal?: AbortSignal): Promise<void> {
  const m = await db.media.get(id)
  if (!m?.dropboxPath || (await localMediaFile(m))) return
  let done = 0
  const { body } = await downloadStream(m.dropboxPath, signal)
  await writeLocal(storedName(m), body, (n) => onProgress?.((done += n), m.size), signal)
  await db.media.update(id, { local: true })
}

/** Frees this device's copy. A file in Dropbox stays (it streams from there); one only here is gone. */
export async function removeMediaFromDevice(id: string): Promise<void> {
  const m = await db.media.get(id)
  if (!m) return
  const d = await mediaDir()
  await d.removeEntry(storedName(m)).catch(() => undefined)
  const url = objectUrls.get(storedName(m))
  if (url) URL.revokeObjectURL(url)
  objectUrls.delete(storedName(m))
  if (m.subs?.startsWith(LOCAL)) await d.removeEntry(m.subs.slice(LOCAL.length)).catch(() => undefined)
  if (m.dropboxPath) await db.media.update(id, { local: false, subs: m.subs?.startsWith(LOCAL) ? null : m.subs })
  else await db.media.delete(id)
}

// ---- Playing ----

/** The file as a one-track book, for the shared player (audiobooks/player.ts), which keeps its place as "audiopos". */
export function asBook(m: MediaFile): Audiobook {
  const track: AudioTrack = {
    path: m.dropboxPath ?? `${LOCAL}${m.name}`,
    name: m.name,
    hash: m.id,
    size: m.size,
    duration: m.duration,
    title: titleOf(m.name),
    marks: [],
    scanned: true,
    local: m.local,
  }
  return {
    id: m.id,
    title: titleOf(m.name),
    author: m.folder ? (m.folder.split('/').pop() ?? '') : '',
    cover: null,
    dropboxPath: m.dropboxPath,
    folder: false,
    tracks: [track],
    size: m.size,
    described: true,
    addedAt: m.addedAt,
    lastOpenedAt: m.lastOpenedAt,
  }
}
