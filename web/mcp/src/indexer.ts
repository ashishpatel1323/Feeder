/** Keeps the index fresh: sync pull, feed refresh, Dropbox listing, chunking. */
import { loadSecret } from './config.js'
import { getKv, setKv, type Db } from './db.js'
import { Dropbox } from './dropbox.js'
import { refreshBooks } from './ebooks.js'
import { refreshFeeds } from './feeds.js'
import { kindOfRow, type ItemRow } from './content.js'
import { deleteDocument, putDocument } from './store.js'
import { chunkBlocks, estTokens, htmlToBlocks, textToBlocks } from './text.js'
import { pull } from './sync.js'
import { indexZim, listZims, refreshZims, zimIndexed } from './zims.js'

const log = (...a: unknown[]) => console.error('[feeder-mcp]', ...a)

export interface Step {
  sync?: boolean
  feeds?: boolean
  dropbox?: boolean
  zimFullText?: boolean
}

export async function syncNow(db: Db) {
  const secret = loadSecret()
  if (!secret) throw new Error('Not set up: run `feeder-mcp setup` and paste Feeder’s “add device” link.')
  const r = await pull(db, secret)
  indexHighlights(db)
  return r
}

/** One document per highlight: the passage plus its note. */
export function indexHighlights(db: Db) {
  const rows = db.prepare('SELECT id, item_id, title, feed, text, note, deleted, cfi FROM highlights').all() as {
    id: string
    item_id: string
    title: string
    feed: string
    text: string
    note: string
    deleted: number
    cfi: string | null
  }[]
  db.transaction(() => {
    for (const h of rows) {
      const docId = `highlight:${h.id}`
      if (h.deleted) {
        deleteDocument(db, docId)
        continue
      }
      const text = h.note ? `${h.text}\n\nNote: ${h.note}` : h.text
      putDocument(db, docId, 'highlight', h.id, `Highlight in ${h.title}${h.feed ? ' — ' + h.feed : ''}`, [
        { seq: 0, heading: '', text, tokens: estTokens(text), locator: { highlight: h.id, item: h.item_id, ...(h.cfi ? { cfi: h.cfi } : {}) } },
      ])
    }
  })()
}

/** Chunks the feed text of articles not indexed yet (full text and transcripts are made on demand). */
export function indexNewItems(db: Db, limit = 3000): number {
  const rows = db
    .prepare(
      `SELECT i.* FROM items i LEFT JOIN documents d ON d.doc_id = 'item:' || i.id
       LEFT JOIN item_state s ON s.id = i.id
       WHERE d.doc_id IS NULL AND COALESCE(s.hidden, 0) = 0
       ORDER BY i.sort_time DESC LIMIT ?`,
    )
    .all(limit) as ItemRow[]
  let n = 0
  // Small transactions so other readers (tool calls) aren't held up
  for (let at = 0; at < rows.length; at += 200) db.transaction(() => {
    for (const it of rows.slice(at, at + 200)) {
      let blocks = htmlToBlocks(it.full_html || it.content_html)
      if (!blocks.length && it.snippet) blocks = textToBlocks(it.snippet)
      if (!blocks.length) blocks = [{ level: 0, text: it.title }]
      const kind = kindOfRow(it)
      putDocument(db, `item:${it.id}`, kind, it.id, it.title, chunkBlocks(blocks, { item: it.id }))
      n++
    }
  })()
  return n
}

export async function runOnce(db: Db, what: Step = { sync: true, feeds: true, dropbox: true, zimFullText: true }) {
  const out: Record<string, unknown> = {}
  if (what.sync) {
    try {
      const r = await syncNow(db)
      out.sync = { records: r.records, byKind: r.byKind }
      // New feeds get fetched right away
      if (r.newFeeds.length && !what.feeds) out.newFeeds = await refreshFeeds(db, r.newFeeds)
    } catch (e) {
      out.syncError = String((e as Error).message ?? e)
    }
  }
  if (what.feeds) {
    out.feeds = await refreshFeeds(db)
    setKv(db, 'lastFeedRefresh', Date.now())
  }
  out.indexedItems = indexNewItems(db)
  if (what.dropbox) {
    const dbx = Dropbox.fromDb(db)
    try {
      if (dbx) out.books = await refreshBooks(db, dbx)
      out.zims = await refreshZims(db, dbx)
      setKv(db, 'lastDropbox', Date.now())
    } catch (e) {
      out.dropboxError = String((e as Error).message ?? e)
    }
  }
  if (what.zimFullText) {
    for (const z of listZims(db)) {
      if (!zimIndexed(db, z.id) && z.local_path) {
        try {
          out[`zim:${z.name}`] = await indexZim(db, z)
        } catch (e) {
          log('ZIM index failed', z.name, e)
        }
      }
    }
  }
  return out
}

const SYNC_EVERY = 5 * 60_000
const FEEDS_EVERY = 30 * 60_000
const DROPBOX_EVERY = 30 * 60_000

/** Background loop inside the MCP server process. */
export function startBackground(db: Db) {
  let busy = false
  const tick = async () => {
    if (busy) return
    busy = true
    try {
      const now = Date.now()
      const step: Step = {
        sync: now - getKv(db, 'lastSync', 0) > SYNC_EVERY,
        feeds: now - getKv(db, 'lastFeedRefresh', 0) > FEEDS_EVERY,
        dropbox: now - getKv(db, 'lastDropbox', 0) > DROPBOX_EVERY,
        zimFullText: true,
      }
      if (step.sync || step.feeds || step.dropbox) {
        const r = await runOnce(db, step)
        log('refreshed', JSON.stringify(r))
      }
    } catch (e) {
      log('background refresh failed', e)
    } finally {
      busy = false
    }
  }
  setTimeout(tick, 2_000)
  const timer = setInterval(tick, 60_000)
  timer.unref()
}
