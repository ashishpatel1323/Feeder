/** Keyword search (SQLite FTS5, BM25) over every chunk, with filters. */
import type { Db } from './db.js'
import type { Source } from './store.js'

export interface SearchFilters {
  kinds?: Source[]
  feeds?: string[]
  folders?: string[]
  since?: number
  until?: number
  read?: boolean
  saved?: boolean
  book?: string
  docIds?: string[]
}

export interface SearchHit {
  rowid: number
  doc_id: string
  source: Source
  ref_id: string
  title: string
  heading: string
  locator: string
  snippet: string
  score: number
  feed_url: string | null
  feed_title: string | null
  sort_time: number | null
}

/** User text → an FTS5 query. Words are quoted so punctuation can't break the syntax. */
export function ftsQuery(q: string, op: 'AND' | 'OR'): string | null {
  const phrases: string[] = []
  const rest = q.replace(/"([^"]+)"/g, (_, p: string) => {
    phrases.push(`"${p.replace(/"/g, '')}"`)
    return ' '
  })
  const words = rest
    .split(/[^\p{L}\p{N}_'-]+/u)
    .map((w) => w.replace(/^['-]+|['-]+$/g, ''))
    .filter((w) => w.length > 1 && !STOP.has(w.toLowerCase()))
    .map((w) => `"${w}"`)
  const terms = [...phrases, ...words]
  return terms.length ? terms.join(` ${op} `) : null
}

const STOP = new Set(['the', 'and', 'for', 'are', 'was', 'what', 'about', 'with', 'from', 'that', 'this', 'have', 'has', 'how', 'why', 'who', 'did', 'does', 'any', 'all', 'my', 'me', 'of', 'in', 'on', 'to', 'is', 'it', 'an', 'or', 'at', 'by', 'be'])

export function search(db: Db, query: string, filters: SearchFilters = {}, limit = 10): SearchHit[] {
  for (const op of ['AND', 'OR'] as const) {
    const fts = ftsQuery(query, op)
    if (!fts) return []
    const hits = runSearch(db, fts, filters, limit)
    if (hits.length >= Math.min(3, limit) || op === 'OR') return hits
  }
  return []
}

function runSearch(db: Db, fts: string, f: SearchFilters, limit: number): SearchHit[] {
  const where: string[] = ['chunks_fts MATCH @fts']
  const params: Record<string, unknown> = { fts, limit: limit * 4 }
  if (f.kinds?.length) {
    where.push(`d.source IN (${f.kinds.map((_, i) => `@k${i}`).join(',')})`)
    f.kinds.forEach((k, i) => (params[`k${i}`] = k))
  }
  if (f.docIds?.length) {
    where.push(`d.doc_id IN (${f.docIds.map((_, i) => `@d${i}`).join(',')})`)
    f.docIds.forEach((k, i) => (params[`d${i}`] = k))
  }
  if (f.book) {
    where.push(`(d.source = 'book' AND d.ref_id = @book)`)
    params.book = f.book
  }
  if (f.feeds?.length) {
    where.push(`i.feed_url IN (${f.feeds.map((_, n) => `@f${n}`).join(',')})`)
    f.feeds.forEach((u, n) => (params[`f${n}`] = u))
  }
  if (f.folders?.length) {
    where.push(`fe.folder IN (${f.folders.map((_, n) => `@g${n}`).join(',')})`)
    f.folders.forEach((u, n) => (params[`g${n}`] = u))
  }
  if (f.since != null) {
    where.push('(i.sort_time >= @since OR (i.id IS NULL AND d.updated_at >= @since))')
    params.since = f.since
  }
  if (f.until != null) {
    where.push('i.sort_time <= @until')
    params.until = f.until
  }
  if (f.read != null) where.push(f.read ? 'st.read = 1' : 'COALESCE(st.read, 0) = 0')
  if (f.saved != null) where.push(f.saved ? 'st.saved = 1' : 'COALESCE(st.saved, 0) = 0')
  where.push('COALESCE(st.hidden, 0) = 0')
  const sql = `
    SELECT c.rowid, c.doc_id, d.source, d.ref_id, d.title, c.heading, c.locator,
           snippet(chunks_fts, 0, '**', '**', ' … ', 24) AS snippet,
           bm25(chunks_fts, 1.0, 3.0, 1.5) AS score,
           i.feed_url, COALESCE(NULLIF(fe.custom_title, ''), fe.title) AS feed_title, i.sort_time
    FROM chunks_fts
    JOIN chunks c ON c.rowid = chunks_fts.rowid
    JOIN documents d ON d.doc_id = c.doc_id
    LEFT JOIN items i ON i.id = d.ref_id AND d.source IN ('article', 'podcast', 'youtube')
    LEFT JOIN feeds fe ON fe.url = i.feed_url
    LEFT JOIN item_state st ON st.id = d.ref_id
    WHERE ${where.join(' AND ')}
    ORDER BY score
    LIMIT @limit`
  const rows = db.prepare(sql).all(params) as SearchHit[]
  // At most two chunks from one document, so one long article doesn't fill the list
  const per = new Map<string, number>()
  const out: SearchHit[] = []
  for (const r of rows) {
    const n = per.get(r.doc_id) ?? 0
    if (n >= 2) continue
    per.set(r.doc_id, n + 1)
    out.push(r)
    if (out.length >= limit) break
  }
  return out
}
