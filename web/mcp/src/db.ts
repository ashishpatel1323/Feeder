import { createRequire } from 'node:module'
import type { DatabaseSync } from 'node:sqlite'

// Loaded on first use (not a static import) so quiet.ts can hide the "experimental" warning first
const sqlite = () => createRequire(import.meta.url)('node:sqlite') as typeof import('node:sqlite')

/**
 * Node's built-in SQLite (FTS5 included), so there's no native module to rebuild: the same code runs
 * under plain Node and inside the Feeder Mac app (Electron). The small API below matches what the
 * rest of the code used from better-sqlite3: prepare/exec and nestable transaction(fn)().
 */
/* eslint-disable @typescript-eslint/no-explicit-any */
/** Rows come back untyped (as with better-sqlite3); callers cast to their row types. */
export interface Stmt {
  get(...params: any[]): any
  all(...params: any[]): any[]
  run(...params: any[]): { changes: number | bigint; lastInsertRowid: number | bigint }
}

export class Db {
  private depth = 0
  private cache = new Map<string, Stmt>()
  constructor(readonly raw: DatabaseSync) {}

  prepare(sql: string): Stmt {
    let st = this.cache.get(sql)
    if (!st) {
      st = this.raw.prepare(sql) as unknown as Stmt
      this.cache.set(sql, st)
    }
    return st
  }

  exec(sql: string) {
    this.raw.exec(sql)
  }

  /** Wraps fn in a transaction (a savepoint when already inside one), like better-sqlite3. */
  transaction<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R {
    return (...args: A) => {
      const name = `sp${this.depth}`
      this.raw.exec(this.depth ? `SAVEPOINT ${name}` : 'BEGIN')
      this.depth++
      try {
        const out = fn(...args)
        this.depth--
        this.raw.exec(this.depth ? `RELEASE ${name}` : 'COMMIT')
        return out
      } catch (e) {
        this.depth--
        this.raw.exec(this.depth ? `ROLLBACK TO ${name}; RELEASE ${name}` : 'ROLLBACK')
        throw e
      }
    }
  }

  close() {
    this.raw.close()
  }
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS feeds (
  url TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  custom_title TEXT NOT NULL DEFAULT '',
  folder TEXT NOT NULL DEFAULT '',
  image_url TEXT,
  site_url TEXT,
  full_text_by_default INTEGER NOT NULL DEFAULT 0,
  alternate_id INTEGER NOT NULL DEFAULT 0,
  deleted INTEGER NOT NULL DEFAULT 0,
  modified_at INTEGER NOT NULL DEFAULT 0,
  last_fetched INTEGER,
  last_error TEXT
);

-- id = feedUrl + newline + guid, the same as Feeder everywhere
CREATE TABLE IF NOT EXISTS items (
  id TEXT PRIMARY KEY,
  feed_url TEXT NOT NULL,
  guid TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  link TEXT,
  author TEXT,
  image_url TEXT,
  sort_time INTEGER NOT NULL DEFAULT 0,
  first_seen INTEGER NOT NULL DEFAULT 0,
  content_html TEXT NOT NULL DEFAULT '',
  snippet TEXT NOT NULL DEFAULT '',
  word_count INTEGER NOT NULL DEFAULT 0,
  enclosure_url TEXT,
  duration INTEGER,
  full_html TEXT,
  full_word_count INTEGER,
  transcript_url TEXT,
  transcript_type TEXT,
  transcript_json TEXT,
  transcript_source TEXT,
  stub INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS items_feed ON items (feed_url, sort_time);
CREATE INDEX IF NOT EXISTS items_time ON items (sort_time);

-- Synced per-article state, kept apart from items so it lands even before the article is fetched
CREATE TABLE IF NOT EXISTS item_state (
  id TEXT PRIMARY KEY,
  read INTEGER, read_ts INTEGER,
  saved INTEGER, saved_ts INTEGER, saved_copy TEXT,
  hidden INTEGER,
  pos_t REAL, pos_d REAL
);

CREATE TABLE IF NOT EXISTS playlists (
  id TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  ts INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS highlights (
  id TEXT PRIMARY KEY,
  item_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  link TEXT,
  feed TEXT NOT NULL DEFAULT '',
  text TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  color TEXT NOT NULL DEFAULT 'yellow',
  prefix TEXT NOT NULL DEFAULT '',
  start INTEGER NOT NULL DEFAULT 0,
  cfi TEXT,
  created_at INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS highlights_item ON highlights (item_id);

CREATE TABLE IF NOT EXISTS book_pos (id TEXT PRIMARY KEY, cfi TEXT, f REAL, ts INTEGER);

-- Ebooks and ZIM files found in Dropbox (id = Dropbox content hash)
CREATE TABLE IF NOT EXISTS files (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,           -- book | zim
  path TEXT NOT NULL,
  name TEXT NOT NULL,
  size INTEGER NOT NULL,
  title TEXT,
  author TEXT,
  local_path TEXT,
  toc_json TEXT,
  seen_at INTEGER NOT NULL
);

-- Searchable text: one document per item text / transcript / book chapter / highlight
CREATE TABLE IF NOT EXISTS documents (
  doc_id TEXT PRIMARY KEY,      -- e.g. item:<id>, transcript:<id>, book:<id>:<n>, highlight:<id>
  source TEXT NOT NULL,         -- article | podcast | youtube | highlight | book | zim
  ref_id TEXT NOT NULL,
  title TEXT NOT NULL,
  text_hash TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS documents_ref ON documents (ref_id);

CREATE TABLE IF NOT EXISTS chunks (
  rowid INTEGER PRIMARY KEY,
  doc_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  heading TEXT NOT NULL DEFAULT '',
  text TEXT NOT NULL,
  tokens INTEGER NOT NULL,
  locator TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS chunks_doc ON chunks (doc_id, seq);

CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
  text, title, heading, tokenize='porter unicode61'
);
`

export function openDb(path: string): Db {
  const db = new Db(new (sqlite().DatabaseSync)(path))
  db.exec('PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000')
  db.exec(SCHEMA)
  return db
}

export function getKv<T>(db: Db, key: string, fallback: T): T {
  const row = db.prepare('SELECT value FROM kv WHERE key = ?').get(key) as { value: string } | undefined
  return row ? (JSON.parse(row.value) as T) : fallback
}

export function setKv(db: Db, key: string, value: unknown) {
  db.prepare('INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run(key, JSON.stringify(value))
}
