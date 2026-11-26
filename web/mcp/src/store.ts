/** Searchable documents: chunks in SQLite with an FTS5 index over them. */
import { createHash } from 'node:crypto'
import type { Db } from './db.js'
import type { Chunk } from './text.js'

export type Source = 'article' | 'podcast' | 'youtube' | 'highlight' | 'book' | 'zim'

export interface StoredChunk {
  rowid: number
  doc_id: string
  seq: number
  heading: string
  text: string
  tokens: number
  locator: string
}

const hashOf = (chunks: Chunk[], title: string) =>
  createHash('sha1')
    .update(title)
    .update(chunks.map((c) => c.heading + '\u0000' + c.text).join('\u0001'))
    .digest('hex')

/** Stores a document's chunks, replacing the old ones only when the text changed. */
export function putDocument(db: Db, docId: string, source: Source, refId: string, title: string, chunks: Chunk[]): boolean {
  const hash = hashOf(chunks, title)
  const old = db.prepare('SELECT text_hash FROM documents WHERE doc_id = ?').get(docId) as { text_hash: string } | undefined
  if (old?.text_hash === hash) return false
  db.transaction(() => {
    deleteChunks(db, docId)
    db.prepare(
      `INSERT INTO documents (doc_id, source, ref_id, title, text_hash, updated_at) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(doc_id) DO UPDATE SET source = excluded.source, ref_id = excluded.ref_id, title = excluded.title,
         text_hash = excluded.text_hash, updated_at = excluded.updated_at`,
    ).run(docId, source, refId, title, hash, Date.now())
    const ins = db.prepare('INSERT INTO chunks (doc_id, seq, heading, text, tokens, locator) VALUES (?, ?, ?, ?, ?, ?)')
    const fts = db.prepare('INSERT INTO chunks_fts (rowid, text, title, heading) VALUES (?, ?, ?, ?)')
    for (const c of chunks) {
      const r = ins.run(docId, c.seq, c.heading, c.text, c.tokens, JSON.stringify(c.locator))
      fts.run(r.lastInsertRowid, c.text, title, c.heading)
    }
  })()
  return true
}

function deleteChunks(db: Db, docId: string) {
  const rows = db.prepare('SELECT rowid FROM chunks WHERE doc_id = ?').all(docId) as { rowid: number }[]
  const del = db.prepare('DELETE FROM chunks_fts WHERE rowid = ?')
  for (const r of rows) del.run(r.rowid)
  db.prepare('DELETE FROM chunks WHERE doc_id = ?').run(docId)
}

export function deleteDocument(db: Db, docId: string) {
  db.transaction(() => {
    deleteChunks(db, docId)
    db.prepare('DELETE FROM documents WHERE doc_id = ?').run(docId)
  })()
}

export function hasDocument(db: Db, docId: string): boolean {
  return !!db.prepare('SELECT 1 FROM documents WHERE doc_id = ?').get(docId)
}

export function chunksOf(db: Db, docId: string): StoredChunk[] {
  return db.prepare('SELECT rowid, doc_id, seq, heading, text, tokens, locator FROM chunks WHERE doc_id = ? ORDER BY seq').all(docId) as StoredChunk[]
}
