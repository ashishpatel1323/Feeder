/**
 * Feeder sync v2, read-only. feeder-mcp joins the sync as one more device: it derives the account
 * from the sync code + key (same as web/src/lib/sync2.ts and Android sync2/Sync2Keys.kt), pulls every
 * record changed since its cursor, decrypts it locally and writes it into SQLite. It never pushes.
 */
import { b64encode, decodeKey, decryptString, encryptString, type SecretKeys } from './vendor/crypto.js'
import { SYNC_SERVER, type SyncSecret } from './config.js'
import { getKv, setKv, type Db } from './db.js'

const enc = new TextEncoder()
const b64url = (b: Uint8Array) => b64encode(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

async function hmac(key: CryptoKey, text: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(text)))
}

export interface SyncKeys {
  secret: SecretKeys
  account: string
  auth: string
  idKey: CryptoKey
}

export async function deriveKeys(syncCode: string, secretKey: string): Promise<SyncKeys> {
  const secret = await decodeKey(secretKey)
  const code = syncCode.toLowerCase()
  return {
    secret,
    account: b64url(await hmac(secret.hmac, `feeder-sync2 account\n${code}`)),
    auth: b64url(await hmac(secret.hmac, `feeder-sync2 auth\n${code}`)),
    idKey: await crypto.subtle.importKey('raw', (await hmac(secret.hmac, 'feeder-sync2 id')) as Uint8Array<ArrayBuffer>, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']),
  }
}

/** Record id: HMAC of "kind \n key" with the id key (same as web sync2.ts recordId). */
export async function recordId(keys: SyncKeys, kind: string, key: string): Promise<string> {
  return b64url(await hmac(keys.idKey, `${kind}\n${key}`))
}

/**
 * Writes records to sync (last writer wins by ts), then applies them locally. Used for the few
 * writes feeder-mcp makes, such as adding a note.
 */
export async function push(db: Db, secret: SyncSecret, payloads: { p: Payload; ts: number }[]): Promise<void> {
  const keys = await deriveKeys(secret.syncCode, secret.secretKey)
  const records = await Promise.all(
    payloads.map(async ({ p, ts }) => ({
      id: await recordId(keys, p.k, p.key),
      ts,
      data: await encryptString(JSON.stringify({ k: p.k, key: p.key, v: p.v, ...(p.a ? { a: p.a } : {}) }), keys.secret),
    })),
  )
  // since = current cursor so the answer is only what's new; the next pull applies it as usual
  await post({ account: keys.account, auth: keys.auth, since: getKv(db, 'syncCursor', 0), push: records })
  db.transaction(() => {
    for (const { p, ts } of payloads) applyRecord(db, p, ts)
  })()
}

export interface Payload {
  k: string
  key: string
  v: unknown
  a?: { title?: string; link?: string | null; sortTime?: number; author?: string | null; imageUrl?: string | null; snippet?: string }
}

interface ExchangeResponse {
  cursor: number
  more: boolean
  changes: { id: string; ts: number; data: string }[]
}

export interface PullResult {
  records: number
  byKind: Record<string, number>
  newFeeds: string[]
}

type Rec = Record<string, unknown>
const isObj = (v: unknown): v is Rec => !!v && typeof v === 'object' && !Array.isArray(v)
const str = (v: unknown, d = '') => (typeof v === 'string' ? v : d)

/** Writes one decrypted record into the database. Exported for tests. */
export function applyRecord(db: Db, p: Payload, ts: number, result?: PullResult) {
  const v = p.v
  const deleted = isObj(v) && v.deleted === true
  switch (p.k) {
    case 'feed': {
      if (deleted || !isObj(v)) {
        db.prepare('UPDATE feeds SET deleted = 1, modified_at = ? WHERE url = ?').run(ts, p.key)
        return
      }
      const existed = db.prepare('SELECT deleted FROM feeds WHERE url = ?').get(p.key) as { deleted: number } | undefined
      if (!existed || existed.deleted) result?.newFeeds.push(p.key)
      db.prepare(
        `INSERT INTO feeds (url, title, custom_title, folder, image_url, full_text_by_default, alternate_id, deleted, modified_at)
         VALUES (@url, @title, @custom, @folder, @image, @ft, @alt, 0, @ts)
         ON CONFLICT(url) DO UPDATE SET
           title = CASE WHEN excluded.title <> '' THEN excluded.title ELSE feeds.title END,
           custom_title = excluded.custom_title, folder = excluded.folder,
           image_url = COALESCE(excluded.image_url, feeds.image_url),
           full_text_by_default = excluded.full_text_by_default, alternate_id = excluded.alternate_id,
           deleted = 0, modified_at = excluded.modified_at`,
      ).run({
        url: p.key,
        title: str(v.title),
        custom: str(v.customTitle),
        folder: str(v.tag),
        image: typeof v.imageUrl === 'string' ? v.imageUrl : null,
        ft: v.fullTextByDefault === true ? 1 : 0,
        alt: v.alternateId === true ? 1 : 0,
        ts,
      })
      return
    }
    case 'read':
      db.prepare(
        'INSERT INTO item_state (id, read, read_ts) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET read = excluded.read, read_ts = excluded.read_ts',
      ).run(p.key, v === true ? 1 : 0, ts)
      return
    case 'saved':
      db.prepare(
        `INSERT INTO item_state (id, saved, saved_ts, saved_copy) VALUES (?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET saved = excluded.saved, saved_ts = excluded.saved_ts,
           saved_copy = COALESCE(excluded.saved_copy, item_state.saved_copy)`,
      ).run(p.key, v === true ? 1 : 0, ts, p.a ? JSON.stringify(p.a) : null)
      if (v === true && p.a) ensureStub(db, p.key, p.a)
      return
    case 'hidden':
      db.prepare('INSERT INTO item_state (id, hidden) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET hidden = excluded.hidden').run(
        p.key,
        v === true ? 1 : 0,
      )
      return
    case 'video':
      if (isObj(v))
        db.prepare('INSERT INTO item_state (id, pos_t, pos_d) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET pos_t = excluded.pos_t, pos_d = excluded.pos_d').run(
          p.key,
          Number(v.t) || 0,
          Number(v.d) || 0,
        )
      return
    case 'playlist':
      db.prepare(
        'INSERT INTO playlists (id, value, deleted, ts) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET value = excluded.value, deleted = excluded.deleted, ts = excluded.ts',
      ).run(p.key, JSON.stringify(deleted ? {} : v), deleted ? 1 : 0, ts)
      return
    case 'highlight': {
      if (deleted || !isObj(v)) {
        db.prepare('UPDATE highlights SET deleted = 1, ts = ? WHERE id = ?').run(ts, p.key)
        return
      }
      db.prepare(
        `INSERT INTO highlights (id, item_id, title, link, feed, text, note, color, prefix, start, cfi, created_at, ts, deleted)
         VALUES (@id, @item, @title, @link, @feed, @text, @note, @color, @prefix, @start, @cfi, @created, @ts, 0)
         ON CONFLICT(id) DO UPDATE SET item_id = excluded.item_id, title = excluded.title, link = excluded.link, feed = excluded.feed,
           text = excluded.text, note = excluded.note, color = excluded.color, prefix = excluded.prefix, start = excluded.start,
           cfi = excluded.cfi, created_at = excluded.created_at, ts = excluded.ts, deleted = 0`,
      ).run({
        id: p.key,
        item: str(v.item),
        title: str(v.title),
        link: typeof v.link === 'string' ? v.link : null,
        feed: str(v.feed),
        text: str(v.text),
        note: str(v.note),
        color: str(v.color, 'yellow'),
        prefix: str(v.prefix),
        start: Number(v.start) || 0,
        cfi: typeof v.cfi === 'string' ? v.cfi : null,
        created: Number(v.createdAt) || ts,
        ts,
      })
      return
    }
    case 'bookpos':
      if (isObj(v))
        db.prepare('INSERT INTO book_pos (id, cfi, f, ts) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET cfi = excluded.cfi, f = excluded.f, ts = excluded.ts').run(
          p.key,
          str(v.cfi),
          Number(v.f) || 0,
          ts,
        )
      return
    case 'dropbox':
      setKv(db, 'dropbox', deleted || !isObj(v) ? null : { appKey: str(v.appKey), refreshToken: str(v.refreshToken), name: str(v.name) })
      return
    case 'layout':
      if (isObj(v)) setKv(db, 'layout', v)
      return
    default:
    // Kinds this version doesn't know (readlater, …) are skipped
  }
}

/** A Saved article this device never fetched: keep the synced copy so it can be listed and read. */
function ensureStub(db: Db, id: string, a: NonNullable<Payload['a']>) {
  const nl = id.indexOf('\n')
  if (nl < 0) return
  db.prepare(
    `INSERT INTO items (id, feed_url, guid, title, link, author, image_url, sort_time, first_seen, snippet, stub)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) ON CONFLICT(id) DO NOTHING`,
  ).run(id, id.slice(0, nl), id.slice(nl + 1), a.title ?? '', a.link ?? null, a.author ?? null, a.imageUrl ?? null, a.sortTime ?? 0, Date.now(), a.snippet ?? '')
}

async function post(body: unknown): Promise<ExchangeResponse> {
  const res = await fetch(`${SYNC_SERVER}/api/v2/sync`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'user-agent': 'feeder-mcp' },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`Sync server answered ${res.status}: ${text.slice(0, 200)}`)
  return JSON.parse(text) as ExchangeResponse
}

/** Pulls everything changed since the stored cursor. */
export async function pull(db: Db, secret: SyncSecret): Promise<PullResult> {
  const keys = await deriveKeys(secret.syncCode, secret.secretKey)
  // A different sync account starts from scratch
  if (getKv(db, 'syncAccount', '') !== keys.account) {
    setKv(db, 'syncAccount', keys.account)
    setKv(db, 'syncCursor', 0)
  }
  let cursor = getKv(db, 'syncCursor', 0)
  const result: PullResult = { records: 0, byKind: {}, newFeeds: [] }
  for (;;) {
    const res = await post({ account: keys.account, auth: keys.auth, since: cursor, push: [] })
    const decoded: [Payload, number][] = []
    for (const c of res.changes) {
      try {
        const p = JSON.parse(await decryptString(c.data, keys.secret)) as Payload
        if (typeof p.k === 'string' && typeof p.key === 'string') decoded.push([p, c.ts])
      } catch {
        // unreadable record (another key, damaged): skip it
      }
    }
    db.transaction(() => {
      for (const [p, ts] of decoded) {
        applyRecord(db, p, ts, result)
        result.byKind[p.k] = (result.byKind[p.k] ?? 0) + 1
      }
      cursor = res.cursor
      setKv(db, 'syncCursor', cursor)
    })()
    result.records += decoded.length
    if (!res.more) break
  }
  setKv(db, 'lastSync', Date.now())
  return result
}
