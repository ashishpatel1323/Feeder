/**
 * Feeder's own sync (v2). One endpoint, POST /api/v2/sync, backed by one database table.
 *
 * The server stores opaque encrypted records and never sees what is in them. Each record is a
 * last-writer-wins register: { id, ts, data }.
 *  - id:   an HMAC of "kind + entity" made on the device, so the server can't read feed URLs
 *  - ts:   the change time in epoch millis, chosen by the device; the higher ts wins
 *  - data: the encrypted payload (same AES-CBC + HMAC format as Feeder's old sync)
 *
 * A device sends its changed records and the cursor it got last time, and gets back every
 * record changed since then (its own included) plus the new cursor. One request does both.
 *
 * An account is identified by `account` and proved by `auth`. Both are derived on the device
 * from the sync code and secret key every device already shares, so the server never sees the
 * key. The first request for an unknown account creates it and stores SHA-256(auth).
 */

export interface SyncRecord {
  id: string
  ts: number
  data: string
}

export interface StoredRecord extends SyncRecord {
  seq: number
}

export interface SyncStore {
  /** The stored SHA-256(auth) for an account, or null when the account doesn't exist. */
  getAuthHash(account: string): Promise<string | null>
  /** Creates the account unless it exists. Returns false when the account limit is reached. */
  createAccount(account: string, authHash: string, maxAccounts: number): Promise<boolean>
  /**
   * Writes records that are newer than what is stored (higher ts, or same ts and greater data),
   * then returns up to `limit` records with seq > since in seq order, plus the current versions
   * of every pushed record (so a device whose write lost learns the winner).
   */
  exchange(account: string, push: SyncRecord[], since: number, limit: number): Promise<{ changes: StoredRecord[]; more: boolean }>
}

export const MAX_PUSH = 500
export const MAX_PULL = 1000
const MAX_DATA = 64 * 1024
const ID_RE = /^[A-Za-z0-9_-]{16,64}$/
const ACCOUNT_RE = /^[A-Za-z0-9_-]{20,64}$/

/** True when `a` should replace `b` (the same rule the SQL uses). */
export function newer(a: SyncRecord, b: SyncRecord): boolean {
  return a.ts > b.ts || (a.ts === b.ts && a.data > b.data)
}

// ---------------------------------------------------------------------------------------------
// In-memory store: tests and `npm run dev` without a database. Lost on restart.

export class MemoryStore implements SyncStore {
  private accounts = new Map<string, string>()
  private records = new Map<string, Map<string, StoredRecord>>()
  private seq = 0

  async getAuthHash(account: string) {
    return this.accounts.get(account) ?? null
  }

  async createAccount(account: string, authHash: string, maxAccounts: number) {
    if (this.accounts.has(account)) return true
    if (this.accounts.size >= maxAccounts) return false
    this.accounts.set(account, authHash)
    return true
  }

  async exchange(account: string, push: SyncRecord[], since: number, limit: number) {
    let table = this.records.get(account)
    if (!table) this.records.set(account, (table = new Map()))
    for (const r of push) {
      const old = table.get(r.id)
      if (!old || newer(r, old)) table.set(r.id, { ...r, seq: ++this.seq })
    }
    const all = [...table.values()].filter((r) => r.seq > since).sort((a, b) => a.seq - b.seq)
    const page = all.slice(0, limit)
    const ids = new Set(page.map((r) => r.id))
    for (const r of push) {
      const cur = table.get(r.id)!
      if (!ids.has(r.id) && cur.seq <= since) {
        page.push(cur)
        ids.add(r.id)
      }
    }
    return { changes: page, more: all.length > limit }
  }
}

// ---------------------------------------------------------------------------------------------
// Postgres store (Neon's HTTP driver, works in Vercel's edge runtime)

type Sql = {
  (strings: TemplateStringsArray, ...values: unknown[]): Promise<Record<string, unknown>[]>
  transaction(queries: unknown[]): Promise<Record<string, unknown>[][]>
}

export class PostgresStore implements SyncStore {
  private ready: Promise<unknown> | null = null

  constructor(private sql: Sql) {}

  private init() {
    this.ready ??= this.sql
      .transaction([
        this.sql`CREATE SEQUENCE IF NOT EXISTS feeder_sync_seq`,
        this.sql`CREATE TABLE IF NOT EXISTS feeder_sync_accounts (
          id text PRIMARY KEY,
          auth_hash text NOT NULL,
          created_at timestamptz NOT NULL DEFAULT now()
        )`,
        this.sql`CREATE TABLE IF NOT EXISTS feeder_sync_records (
          account text NOT NULL,
          id text NOT NULL,
          ts bigint NOT NULL,
          data text NOT NULL,
          seq bigint NOT NULL,
          PRIMARY KEY (account, id)
        )`,
        this.sql`CREATE INDEX IF NOT EXISTS feeder_sync_records_seq ON feeder_sync_records (account, seq)`,
      ])
      .catch((e) => {
        this.ready = null
        throw e
      })
    return this.ready
  }

  async getAuthHash(account: string) {
    await this.init()
    const rows = await this.sql`SELECT auth_hash FROM feeder_sync_accounts WHERE id = ${account}`
    return rows.length ? String(rows[0].auth_hash) : null
  }

  async createAccount(account: string, authHash: string, maxAccounts: number) {
    await this.init()
    const rows = await this.sql`
      INSERT INTO feeder_sync_accounts (id, auth_hash)
      SELECT ${account}, ${authHash}
      WHERE (SELECT count(*) FROM feeder_sync_accounts) < ${maxAccounts}
      ON CONFLICT (id) DO NOTHING
      RETURNING id`
    if (rows.length) return true
    return (await this.getAuthHash(account)) !== null
  }

  async exchange(account: string, push: SyncRecord[], since: number, limit: number) {
    await this.init()
    const ids = push.map((r) => r.id)
    const results = await this.sql.transaction([
      // Writes and reads for one account take turns, so seq numbers become visible in order
      // and a cursor never skips a record that committed late.
      this.sql`SELECT pg_advisory_xact_lock(hashtext(${account}))`,
      this.sql`
        INSERT INTO feeder_sync_records AS r (account, id, ts, data, seq)
        SELECT ${account}, x.id, x.ts, x.data, nextval('feeder_sync_seq')
        FROM unnest(${ids}::text[], ${push.map((r) => r.ts)}::bigint[], ${push.map((r) => r.data)}::text[]) AS x(id, ts, data)
        ON CONFLICT (account, id) DO UPDATE SET ts = excluded.ts, data = excluded.data, seq = excluded.seq
        WHERE excluded.ts > r.ts OR (excluded.ts = r.ts AND excluded.data > r.data)`,
      this.sql`
        SELECT id, ts, data, seq FROM feeder_sync_records
        WHERE account = ${account} AND seq > ${since}
        ORDER BY seq LIMIT ${limit + 1}`,
      this.sql`
        SELECT id, ts, data, seq FROM feeder_sync_records
        WHERE account = ${account} AND seq <= ${since} AND id = ANY(${ids}::text[])`,
    ])
    const toRecord = (row: Record<string, unknown>): StoredRecord => ({
      id: String(row.id),
      ts: Number(row.ts),
      data: String(row.data),
      seq: Number(row.seq),
    })
    const fresh = results[2].map(toRecord)
    const more = fresh.length > limit
    return { changes: [...fresh.slice(0, limit), ...results[3].map(toRecord)], more }
  }
}

// ---------------------------------------------------------------------------------------------
// HTTP

export interface Sync2Env {
  DATABASE_URL?: string
  /** "1" uses the in-memory store (development only). */
  SYNC_MEMORY?: string
  /** How many sync accounts may exist; stops strangers filling the database. Default 3. */
  SYNC_MAX_ACCOUNTS?: string
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** Same length, constant time. */
function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return diff === 0
}

let memoryStore: MemoryStore | null = null
let pgStore: { url: string; store: PostgresStore } | null = null

async function storeFor(env: Sync2Env): Promise<SyncStore | null> {
  if (env.DATABASE_URL) {
    if (pgStore?.url !== env.DATABASE_URL) {
      const { neon } = await import('@neondatabase/serverless')
      pgStore = { url: env.DATABASE_URL, store: new PostgresStore(neon(env.DATABASE_URL) as unknown as Sql) }
    }
    return pgStore.store
  }
  if (env.SYNC_MEMORY === '1') return (memoryStore ??= new MemoryStore())
  return null
}

/** For tests: start over with an empty in-memory store. */
export function resetMemoryStore() {
  memoryStore = null
}

export function syncStoreKind(env: Sync2Env): 'postgres' | 'memory' | 'none' {
  return env.DATABASE_URL ? 'postgres' : env.SYNC_MEMORY === '1' ? 'memory' : 'none'
}

export async function handleSync2(request: Request, env: Sync2Env, injected?: SyncStore): Promise<Response> {
  if (request.method !== 'POST') return json(405, { error: 'Use POST' })
  const store = injected ?? (await storeFor(env))
  if (!store) return json(503, { error: 'Sync database is not set up (DATABASE_URL is missing)' })

  let body: { account?: unknown; auth?: unknown; since?: unknown; push?: unknown; limit?: unknown }
  try {
    body = await request.json()
  } catch {
    return json(400, { error: 'Body must be JSON' })
  }
  const { account, auth } = body
  if (typeof account !== 'string' || !ACCOUNT_RE.test(account)) return json(400, { error: 'Bad account' })
  if (typeof auth !== 'string' || auth.length < 20 || auth.length > 128) return json(400, { error: 'Bad auth' })
  const since = typeof body.since === 'number' && Number.isSafeInteger(body.since) && body.since >= 0 ? body.since : 0
  const limit =
    typeof body.limit === 'number' && Number.isSafeInteger(body.limit) && body.limit > 0 ? Math.min(body.limit, MAX_PULL) : MAX_PULL
  const pushIn = body.push ?? []
  if (!Array.isArray(pushIn) || pushIn.length > MAX_PUSH) return json(400, { error: `push must be a list of at most ${MAX_PUSH}` })

  // Keep only the newest version of each id (one statement can't write a row twice)
  const byId = new Map<string, SyncRecord>()
  for (const r of pushIn as Partial<SyncRecord>[]) {
    if (!r || typeof r.id !== 'string' || !ID_RE.test(r.id)) return json(400, { error: 'Bad record id' })
    if (typeof r.ts !== 'number' || !Number.isSafeInteger(r.ts) || r.ts < 0) return json(400, { error: 'Bad record ts' })
    if (typeof r.data !== 'string' || r.data.length > MAX_DATA) return json(400, { error: 'Bad record data' })
    const rec = { id: r.id, ts: r.ts, data: r.data }
    const old = byId.get(rec.id)
    if (!old || newer(rec, old)) byId.set(rec.id, rec)
  }

  const authHash = await sha256(auth)
  let stored = await store.getAuthHash(account)
  if (stored === null) {
    const max = Number(env.SYNC_MAX_ACCOUNTS) > 0 ? Number(env.SYNC_MAX_ACCOUNTS) : 3
    if (!(await store.createAccount(account, authHash, max))) return json(403, { error: 'No more sync accounts allowed on this server' })
    stored = await store.getAuthHash(account)
  }
  if (stored === null || !safeEqual(stored, authHash)) return json(403, { error: 'Wrong sync key for this account' })

  const { changes, more } = await store.exchange(account, [...byId.values()], since, limit)
  const cursor = changes.reduce((m, r) => (r.seq > since ? Math.max(m, r.seq) : m), since)
  return json(200, {
    cursor,
    more,
    changes: changes.map((r) => ({ id: r.id, ts: r.ts, data: r.data })),
  })
}
