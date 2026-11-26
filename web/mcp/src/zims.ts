/**
 * ZIM files: from Dropbox /ZIM (read by HTTP range, never downloaded whole) and from local
 * folders (FEEDER_ZIM_DIRS, default ~/Zimit). Small ZIMs (under 1 GB) are fully indexed for search;
 * big ones (Wikipedia) get title lookup only.
 */
import { createHash } from 'node:crypto'
import { existsSync, openSync, readdirSync, readSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, join } from 'node:path'
import type { Db } from './db.js'
import { parseHtml } from './dom.js'
import { Dropbox, ZIM_FOLDER } from './dropbox.js'
import { chunksOf, hasDocument, putDocument, type StoredChunk } from './store.js'
import { chunkBlocks, elementToBlocks } from './text.js'
import { ZimArchive, type ZimSource } from './vendor/zim.js'

export const FULL_INDEX_MAX = 1024 * 1024 * 1024

export interface ZimRow {
  id: string
  path: string
  name: string
  size: number
  title: string | null
  local_path: string | null
}

const zimDirs = () => (process.env.FEEDER_ZIM_DIRS ?? join(homedir(), 'Zimit')).split(':').filter(Boolean)

function findLocal(dir: string, depth = 2): string[] {
  if (!existsSync(dir)) return []
  const out: string[] = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isFile() && e.name.endsWith('.zim') && !e.name.startsWith('partial')) out.push(p)
    else if (e.isDirectory() && depth > 0) out.push(...findLocal(p, depth - 1))
  }
  return out
}

/** Finds ZIMs in Dropbox and in the local folders. */
export async function refreshZims(db: Db, dbx: Dropbox | null): Promise<number> {
  const now = Date.now()
  const rows: { id: string; path: string; name: string; size: number; local: string | null }[] = []
  if (dbx) {
    for (const f of await dbx.listFolder(ZIM_FOLDER).catch(() => [])) if (f.name.endsWith('.zim')) rows.push({ id: f.hash, path: f.path, name: f.name, size: f.size, local: null })
  }
  for (const dir of zimDirs())
    for (const p of findLocal(dir)) {
      const st = statSync(p)
      const id = 'local-' + createHash('sha1').update(`${p}\n${st.size}\n${st.mtimeMs}`).digest('hex').slice(0, 24)
      rows.push({ id, path: p, name: basename(p), size: st.size, local: p })
    }
  db.transaction(() => {
    const up = db.prepare(
      `INSERT INTO files (id, kind, path, name, size, title, local_path, seen_at) VALUES (?, 'zim', ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET path = excluded.path, name = excluded.name, size = excluded.size, local_path = excluded.local_path, seen_at = excluded.seen_at`,
    )
    for (const r of rows) up.run(r.id, r.path, r.name, r.size, r.name.replace(/\.zim$/, '').replace(/_/g, ' '), r.local, now)
    db.prepare(`DELETE FROM files WHERE kind = 'zim' AND seen_at < ?`).run(now)
  })()
  return rows.length
}

export function listZims(db: Db): ZimRow[] {
  return db.prepare(`SELECT id, path, name, size, title, local_path FROM files WHERE kind = 'zim' ORDER BY title`).all() as ZimRow[]
}

const open = new Map<string, Promise<ZimArchive>>()

function fileSource(path: string): ZimSource {
  const fd = openSync(path, 'r')
  const size = statSync(path).size
  return {
    size,
    read: async (offset, length) => {
      const n = Math.max(0, Math.min(length, size - offset))
      const buf = Buffer.alloc(n)
      readSync(fd, buf, 0, n, offset)
      return new Uint8Array(buf.buffer, buf.byteOffset, n)
    },
  }
}

function dropboxSource(dbx: Dropbox, path: string, size: number): ZimSource {
  return { size, read: (offset, length) => dbx.readRange(path, offset, Math.min(length, size - offset)) }
}

export function archive(db: Db, z: ZimRow): Promise<ZimArchive> {
  let p = open.get(z.id)
  if (!p) {
    if (z.local_path) p = ZimArchive.open(fileSource(z.local_path))
    else {
      const dbx = Dropbox.fromDb(db)
      if (!dbx) throw new Error('Not signed in to Dropbox in Feeder, so ZIM files there can’t be read.')
      p = ZimArchive.open(dropboxSource(dbx, z.path, z.size))
    }
    p.catch(() => open.delete(z.id))
    open.set(z.id, p)
  }
  return p
}

export async function zimTitle(db: Db, z: ZimRow): Promise<string> {
  const a = await archive(db, z)
  return (await a.metadata('Title')) || z.title || z.name
}

function articleBlocks(html: string) {
  const doc = parseHtml(html)
  const main = doc.querySelector('main, article, #content, #mw-content-text, .content') ?? doc.body
  return { title: doc.title?.trim() ?? '', blocks: elementToBlocks(main) }
}

/** An article's chunks (made once and kept). */
export async function zimArticle(db: Db, z: ZimRow, path: string): Promise<{ title: string; path: string; chunks: StoredChunk[] } | null> {
  const a = await archive(db, z)
  const item = await a.get(path)
  if (!item) return null
  const docId = `zim:${z.id}:${item.path}`
  if (!hasDocument(db, docId)) {
    if (!item.mime.startsWith('text/html')) return { title: item.title, path: item.path, chunks: [] }
    const { title, blocks } = articleBlocks(new TextDecoder().decode(item.data))
    putDocument(db, docId, 'zim', z.id, item.title || title || item.path, chunkBlocks(blocks, { zim: z.id, path: item.path }))
  }
  const title = (db.prepare('SELECT title FROM documents WHERE doc_id = ?').get(docId) as { title: string }).title
  return { title, path: item.path, chunks: chunksOf(db, docId) }
}

/** Indexes every HTML article of a small ZIM. */
export async function indexZim(db: Db, z: ZimRow, onProgress?: (done: number, total: number) => void): Promise<number> {
  if (z.size > FULL_INDEX_MAX) return 0
  const a = await archive(db, z)
  let n = 0
  for (let i = 0; i < a.entryCount; i++) {
    const d = await a.direntAt(i)
    if (d.redirect || !a.mime(d).startsWith('text/html')) continue
    if (d.namespace !== 'C' && d.namespace !== 'A') continue
    const path = a.urlPath(d)
    const docId = `zim:${z.id}:${path}`
    if (hasDocument(db, docId)) continue
    try {
      const { title, blocks } = articleBlocks(new TextDecoder().decode(await a.blobData(d)))
      if (blocks.length) putDocument(db, docId, 'zim', z.id, d.title || title || path, chunkBlocks(blocks, { zim: z.id, path }))
      n++
    } catch {
      // damaged entry
    }
    if (i % 50 === 0) onProgress?.(i, a.entryCount)
  }
  db.prepare(`INSERT INTO kv (key, value) VALUES (?, '1') ON CONFLICT(key) DO UPDATE SET value = '1'`).run(`zimIndexed:${z.id}`)
  return n
}

export const zimIndexed = (db: Db, id: string) => !!db.prepare('SELECT 1 FROM kv WHERE key = ?').get(`zimIndexed:${id}`)
