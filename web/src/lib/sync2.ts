/**
 * Feeder's own sync (v2), shared with the Android app (sync2/ in the app).
 *
 * Talks to POST /api/v2/sync on this app's backend (server/sync2.ts). Every fact is one
 * encrypted last-writer-wins record:
 *
 *   kind     key                  value
 *   feed     feed URL             synced feed fields, or { deleted: true } after unsubscribing
 *   read     feedUrl \n guid      true / false
 *   saved    feedUrl \n guid      true / false (with a small copy of the article in `a`)
 *   hidden   feedUrl \n guid      true = deleted for good on every device
 *   layout   "main"               { feedOrder, folderOrder, emptyFolders } (web only so far)
 *   video    feedUrl \n guid      { t, d }: where a video or podcast episode was left, in seconds
 *   playlist playlist id          PlaylistValue, or { deleted: true } after deleting it
 *   highlight highlight id        HighlightValue, or { deleted: true } after deleting it
 *
 * New kinds (Read later links, …) can be added later: devices keep records of kinds they
 * don't know and the server never looks inside.
 *
 * The device remembers the last value it exchanged for every record (db.syncKnown). A sync
 * sends whatever differs locally, then applies what other devices changed.
 */
import { offlineNow } from './settings'
import { apiHeaders, HttpError } from './api'
import { b64encode, decodeKey, decryptString, encryptString, type SecretKeys } from './crypto'
import { PLAYLISTS_KEY, STARTER_IDS, loadPlaylists, type Playlist } from './playlists'
import {
  db,
  EMPTY_FOLDERS_KEY,
  FEED_ORDER_KEY,
  FOLDER_ORDER_KEY,
  getKv,
  newFeed,
  setKv,
  type Feed,
  type Highlight,
  type HighlightColor,
  type Item,
  type SyncKnown,
} from './db'

export interface SyncState {
  /** 64 hex characters; together with the key it names the sync account. */
  syncCode: string
  /** "aesKey:hmacKey", same as Feeder's old sync chain. */
  secretKey: string
  /** Server cursor: changes up to here have been applied. */
  cursor: number
  /** Moved over from Feeder's old sync chain. */
  fromChain?: boolean
  lastSyncAt?: number
  lastError?: string | null
}

export type Kind = 'feed' | 'read' | 'saved' | 'hidden' | 'layout' | 'video' | 'playlist' | 'highlight'

/** The plaintext inside a record. */
export interface Payload {
  k: string
  key: string
  v: unknown
  /** Saved records carry a small copy of the article for devices that never fetched it. */
  a?: SavedArticle
}

export interface SavedArticle {
  title: string
  link: string | null
  sortTime: number
  author?: string | null
  imageUrl?: string | null
  snippet?: string
}

export interface FeedValue {
  title: string
  customTitle: string
  tag: string
  imageUrl: string | null
  fullTextByDefault: boolean
  openArticlesWith: string
  alternateId: boolean
  fetchOgImages: boolean
  blockRules: string
  allowRules: string
}

export interface VideoValue {
  /** Seconds watched */
  t: number
  /** Video length in seconds, 0 when not known */
  d: number
}

/**
 * A playlist as synced. The Android app writes the same fields in the same order: articles by
 * "feedUrl \n guid", feeds by URL, and colours as "#rrggbb".
 */
export interface PlaylistValue {
  name: string
  icon: string
  color: string
  smart: boolean
  rules: {
    folders: string[]
    feeds: string[]
    kinds: string[]
    statuses: string[]
    savedOnly: boolean
    downloadedOnly: boolean
    withinDays: number | null
    minMinutes: number | null
    maxMinutes: number | null
  }
  items: string[]
  sort: string
  createdAt: number
}

/** Items of a manual playlist that fit in one record (the server takes up to 64 KB). */
export const MAX_SYNCED_PLAYLIST_ITEMS = 300

export function playlistValue(p: Playlist): PlaylistValue {
  const r = p.rules
  return {
    name: p.name,
    icon: p.icon,
    color: p.color.toLowerCase(),
    smart: p.smart,
    rules: {
      folders: [...r.folders],
      feeds: [...r.feeds],
      kinds: [...r.kinds],
      statuses: [...r.statuses],
      savedOnly: !!r.savedOnly,
      downloadedOnly: !!r.downloadedOnly,
      withinDays: r.withinDays ?? null,
      minMinutes: r.minMinutes ?? null,
      maxMinutes: r.maxMinutes ?? null,
    },
    items: p.smart ? [] : p.itemIds.slice(0, MAX_SYNCED_PLAYLIST_ITEMS),
    sort: p.sort,
    createdAt: p.createdAt || 0,
  }
}

const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
const numOrNull = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/** A playlist from a synced value; fields an older or newer device left out get defaults. */
export function playlistFromValue(id: string, v: Partial<PlaylistValue>, ts: number): Playlist {
  const r = (v.rules ?? {}) as Partial<PlaylistValue['rules']>
  const sorts = ['newest', 'oldest', 'shortest', 'longest']
  return {
    id,
    name: typeof v.name === 'string' ? v.name : 'Playlist',
    icon: typeof v.icon === 'string' ? v.icon : '★',
    color: typeof v.color === 'string' ? v.color.toLowerCase() : '#1d4ed8',
    smart: v.smart === true,
    rules: {
      folders: strings(r.folders),
      feeds: strings(r.feeds),
      kinds: strings(r.kinds).filter((k) => k === 'podcast' || k === 'youtube' || k === 'article') as Playlist['rules']['kinds'],
      statuses: strings(r.statuses).filter((k) => k === 'unread' || k === 'inprogress' || k === 'read') as Playlist['rules']['statuses'],
      savedOnly: r.savedOnly === true,
      downloadedOnly: r.downloadedOnly === true,
      withinDays: numOrNull(r.withinDays),
      minMinutes: numOrNull(r.minMinutes),
      maxMinutes: numOrNull(r.maxMinutes),
    },
    itemIds: strings(v.items),
    sort: (sorts.includes(v.sort as string) ? v.sort : 'newest') as Playlist['sort'],
    createdAt: typeof v.createdAt === 'number' ? v.createdAt : 0,
    modifiedAt: ts,
  }
}

/**
 * Starter playlists made before they had fixed ids get them, unless already synced under their
 * old id, so two devices end up with one "Morning drive" between them.
 */
export function adoptStarterIds(list: Playlist[], synced: Set<string>): Playlist[] {
  const taken = new Set(list.map((p) => p.id))
  return list.map((p) => {
    const fixed = STARTER_IDS[p.name]
    if (!fixed || !p.smart || p.id === fixed || synced.has(p.id) || taken.has(fixed)) return p
    taken.add(fixed)
    return { ...p, id: fixed }
  })
}

/**
 * A highlight as synced; the Android app writes the same fields in the same order. `item` is the
 * article's "feedUrl \n guid"; title, link, feed and image are copied so the Highlights page works
 * on devices that never fetched the article. `start` counts characters of that device's article
 * text, so devices find the text again by `text` and `prefix` when it doesn't match.
 */
export interface HighlightValue {
  item: string
  title: string
  link: string | null
  feed: string
  image: string | null
  text: string
  start: number
  prefix: string
  color: HighlightColor
  note: string
  createdAt: number
}

const HIGHLIGHT_COLOR_IDS: HighlightColor[] = ['yellow', 'green', 'blue', 'pink']

export function highlightValue(h: Highlight): HighlightValue {
  return {
    item: h.itemId,
    title: h.itemTitle,
    link: h.itemLink,
    feed: h.feedTitle,
    image: h.imageUrl,
    text: h.text,
    start: h.start,
    prefix: h.prefix,
    color: h.color,
    note: h.note,
    createdAt: h.createdAt,
  }
}

/** A highlight from a synced value; fields an older or newer device left out get defaults. */
export function highlightFromValue(id: string, v: Partial<HighlightValue>, ts: number): Highlight {
  const str = (x: unknown) => (typeof x === 'string' ? x : '')
  return {
    id,
    itemId: str(v.item),
    itemTitle: str(v.title),
    itemLink: typeof v.link === 'string' ? v.link : null,
    feedTitle: str(v.feed),
    imageUrl: typeof v.image === 'string' ? v.image : null,
    text: str(v.text),
    start: typeof v.start === 'number' && Number.isFinite(v.start) ? v.start : 0,
    prefix: str(v.prefix),
    color: HIGHLIGHT_COLOR_IDS.includes(v.color as HighlightColor) ? (v.color as HighlightColor) : 'yellow',
    note: str(v.note),
    createdAt: typeof v.createdAt === 'number' ? v.createdAt : ts,
    modifiedAt: ts,
  }
}

export interface LayoutValue {
  feedOrder: string[]
  folderOrder: string[]
  emptyFolders: string[]
}

const STATE_KEY = 'sync2'
/** Where the old chain's state goes once moved over, so it is never used again. */
const OLD_CHAIN_KEY = 'sync'
const OLD_CHAIN_DONE_KEY = 'syncChainMovedToV2'
export const LAYOUT_KEY = 'main'

export const knownKey = (kind: string, key: string) => `${kind}\n${key}`

export async function getSyncState(): Promise<SyncState | null> {
  return getKv<SyncState | null>(STATE_KEY, null)
}
const saveState = (s: SyncState | null) => setKv(STATE_KEY, s)

// ---------------------------------------------------------------------------------------------
// Keys. The Android app derives the same values (sync2/Sync2Keys.kt).

const enc = new TextEncoder()

function b64url(bytes: Uint8Array): string {
  return b64encode(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

async function hmac(key: CryptoKey, text: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(text)))
}

export interface Sync2Keys {
  secret: SecretKeys
  account: string
  auth: string
  idKey: CryptoKey
}

export async function deriveKeys(syncCode: string, secretKey: string): Promise<Sync2Keys> {
  const secret = await decodeKey(secretKey)
  const account = b64url(await hmac(secret.hmac, `feeder-sync2 account\n${syncCode.toLowerCase()}`))
  const auth = b64url(await hmac(secret.hmac, `feeder-sync2 auth\n${syncCode.toLowerCase()}`))
  const idBytes = await hmac(secret.hmac, 'feeder-sync2 id')
  const idKey = await crypto.subtle.importKey('raw', idBytes as Uint8Array<ArrayBuffer>, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return { secret, account, auth, idKey }
}

export async function recordId(keys: Sync2Keys, kind: string, key: string): Promise<string> {
  return b64url(await hmac(keys.idKey, knownKey(kind, key)))
}

// ---------------------------------------------------------------------------------------------
// Values

export function feedValue(f: Feed): FeedValue {
  return {
    title: f.title,
    customTitle: f.customTitle,
    tag: f.tag,
    imageUrl: f.imageUrl,
    fullTextByDefault: f.fullTextByDefault,
    openArticlesWith: f.openArticlesWith,
    alternateId: f.alternateId,
    fetchOgImages: f.fetchOgImages,
    blockRules: f.blockRules,
    allowRules: f.allowRules,
  }
}

function applyFeedValue(v: Partial<FeedValue>, base: Feed, ts: number): Feed {
  return {
    ...base,
    title: v.title ?? base.title ?? '',
    customTitle: v.customTitle ?? '',
    tag: v.tag ?? '',
    imageUrl: v.imageUrl ?? null,
    fullTextByDefault: v.fullTextByDefault ?? base.fullTextByDefault,
    openArticlesWith: v.openArticlesWith ?? '',
    alternateId: v.alternateId ?? false,
    fetchOgImages: v.fetchOgImages ?? false,
    blockRules: v.blockRules ?? '',
    allowRules: v.allowRules ?? '',
    whenModified: ts,
  }
}

async function layoutValue(): Promise<LayoutValue> {
  return {
    feedOrder: await getKv<string[]>(FEED_ORDER_KEY, []),
    folderOrder: await getKv<string[]>(FOLDER_ORDER_KEY, []),
    emptyFolders: await getKv<string[]>(EMPTY_FOLDERS_KEY, []),
  }
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const isDeleted = (v: unknown) => !!v && typeof v === 'object' && (v as { deleted?: boolean }).deleted === true
/** Known value of a record seen but never exchanged (moving over from the old chain, Force sync). */
const isPlaceholder = (v: unknown) => !!v && typeof v === 'object' && (v as { known?: boolean }).known === true

/**
 * The time sent for a feed or Saved mark this device has never exchanged: joining with feeds
 * already here (an OPML import, a restored copy), or Force sync. It is older than anything another
 * device synced, so their value wins and this one only fills in what the server lacks. The Android
 * app sends the same.
 */
export const FIRST_CONTACT_TS = 1

// ---------------------------------------------------------------------------------------------
// Local changes

interface Pending extends Payload {
  ts: number
  /** Item rows to mark as sent afterwards, with the values that were sent. */
  item?: { id: string; read: number; saved: number }
}

/** Everything that differs from what was last exchanged. */
export async function collectChanges(now = Date.now()): Promise<Pending[]> {
  const out: Pending[] = []

  // Feeds: edits, new feeds, and unsubscribes (known but gone here)
  const feeds = await db.feeds.toArray()
  const knownFeeds = new Map((await db.syncKnown.where('k').startsWith('feed\n').toArray()).map((r) => [r.k.slice(5), r]))
  for (const f of feeds) {
    const v = feedValue(f)
    const kn = knownFeeds.get(f.url)
    if (!kn || !same(kn.v, v)) {
      const ts = !kn || isPlaceholder(kn.v) ? FIRST_CONTACT_TS : Math.max(f.whenModified || now, kn.ts + 1)
      out.push({ k: 'feed', key: f.url, v, ts })
    }
  }
  const local = new Set(feeds.map((f) => f.url))
  for (const [url, kn] of knownFeeds) {
    if (!local.has(url) && !isDeleted(kn.v)) {
      out.push({ k: 'feed', key: url, v: { deleted: true }, ts: isPlaceholder(kn.v) ? Math.max(kn.ts, 1) : Math.max(now, kn.ts + 1) })
    }
  }

  // Articles touched here since the last sync
  const dirty = await db.items.where('readSynced').equals(0).toArray()
  const knownItems = new Map<string, SyncKnown>()
  const keys = dirty.flatMap((i) => [knownKey('read', i.id), knownKey('saved', i.id)])
  for (const r of await db.syncKnown.bulkGet(keys)) if (r) knownItems.set(r.k, r)
  for (const i of dirty) {
    const before = out.length
    const item = { id: i.id, read: i.read, saved: i.saved }
    const kr = knownItems.get(knownKey('read', i.id))
    if (!!i.read !== (kr?.v === true)) {
      out.push({ k: 'read', key: i.id, v: !!i.read, ts: Math.max(i.read ? (i.readTime ?? now) : now, (kr?.ts ?? 0) + 1), item })
    }
    const ks = knownItems.get(knownKey('saved', i.id))
    if (!!i.saved !== (ks?.v === true)) {
      const ts = ks ? Math.max(now, ks.ts + 1) : FIRST_CONTACT_TS
      out.push({ k: 'saved', key: i.id, v: !!i.saved, a: i.saved ? savedArticle(i) : undefined, ts, item })
    }
    if (out.length === before) out.push({ k: '', key: i.id, v: null, ts: 0, item }) // just mark as sent
  }

  // Deleted articles, and deletions undone here
  const hidden = await db.hidden.toArray()
  const knownHidden = await db.syncKnown.bulkGet(hidden.map((h) => knownKey('hidden', h.id)))
  hidden.forEach((h, n) => {
    const kn = knownHidden[n]
    if (kn?.v !== true) out.push({ k: 'hidden', key: h.id, v: true, ts: Math.max(h.ts, (kn?.ts ?? 0) + 1) })
  })
  const hiddenHere = new Set(hidden.map((h) => h.id))
  for (const kn of await db.syncKnown.where('k').startsWith('hidden\n').toArray()) {
    const id = kn.k.slice('hidden\n'.length)
    if (kn.v === true && !hiddenHere.has(id)) out.push({ k: 'hidden', key: id, v: false, ts: Math.max(now, kn.ts + 1) })
  }

  // Feed and folder order
  const layout = await layoutValue()
  const kl = await db.syncKnown.get(knownKey('layout', LAYOUT_KEY))
  const emptyLayout = !layout.feedOrder.length && !layout.folderOrder.length && !layout.emptyFolders.length
  if (kl ? !same(kl.v, layout) : !emptyLayout) {
    out.push({ k: 'layout', key: LAYOUT_KEY, v: layout, ts: Math.max(now, (kl?.ts ?? 0) + 1) })
  }

  // Playlists: new, edited (newest edit wins) and deleted ones
  const knownLists = new Map((await db.syncKnown.where('k').startsWith('playlist\n').toArray()).map((r) => [r.k.slice('playlist\n'.length), r]))
  const before = await loadPlaylists()
  const lists = adoptStarterIds(before, new Set(knownLists.keys()))
  if (lists.some((p, n) => p.id !== before[n].id)) await setKv(PLAYLISTS_KEY, lists)
  for (const pl of lists) {
    const v = playlistValue(pl)
    const kn = knownLists.get(pl.id)
    if (!kn || !same(kn.v, v)) out.push({ k: 'playlist', key: pl.id, v, ts: Math.max(pl.modifiedAt || pl.createdAt || 1, (kn?.ts ?? 0) + 1) })
  }
  const localLists = new Set(lists.map((p) => p.id))
  for (const [id, kn] of knownLists) {
    if (!localLists.has(id) && !isDeleted(kn.v)) out.push({ k: 'playlist', key: id, v: { deleted: true }, ts: Math.max(now, kn.ts + 1) })
  }

  // Highlights: new, recolored or noted (newest edit wins) and deleted ones
  const knownHl = new Map((await db.syncKnown.where('k').startsWith('highlight\n').toArray()).map((r) => [r.k.slice('highlight\n'.length), r]))
  const highlights = await db.highlights.toArray()
  for (const h of highlights) {
    const v = highlightValue(h)
    const kn = knownHl.get(h.id)
    if (!kn || !same(kn.v, v)) out.push({ k: 'highlight', key: h.id, v, ts: Math.max(h.modifiedAt || h.createdAt || 1, (kn?.ts ?? 0) + 1) })
  }
  const localHl = new Set(highlights.map((h) => h.id))
  for (const [id, kn] of knownHl) {
    if (!localHl.has(id) && !isDeleted(kn.v)) out.push({ k: 'highlight', key: id, v: { deleted: true }, ts: Math.max(now, kn.ts + 1) })
  }

  // Video and episode positions saved here since they were last exchanged
  const videos = await db.videoPos.toArray()
  const knownVideos = await db.syncKnown.bulkGet(videos.map((v) => knownKey('video', v.id)))
  videos.forEach((v, n) => {
    const kn = knownVideos[n]
    const value: VideoValue = { t: v.t, d: v.d }
    if (!kn || (v.ts > kn.ts && !same(kn.v, value))) out.push({ k: 'video', key: v.id, v: value, ts: Math.max(v.ts, (kn?.ts ?? 0) + 1) })
  })
  return out
}

function savedArticle(i: Item): SavedArticle {
  return { title: i.title, link: i.link, sortTime: i.sortTime, author: i.author, imageUrl: i.imageUrl, snippet: i.snippet.slice(0, 300) }
}

// ---------------------------------------------------------------------------------------------
// Remote changes

export interface ApplyResult {
  addedFeeds: string[]
  applied: number
}

/** Applies one record from another device, unless what this device knows is newer. */
export async function applyRemote(p: Payload, ts: number, result: ApplyResult): Promise<void> {
  const k = knownKey(p.k, p.key)
  await db.transaction('rw', [db.feeds, db.items, db.syncKnown, db.hidden, db.kv, db.videoPos, db.highlights], async () => {
    const kn = await db.syncKnown.get(k)
    if (kn && kn.ts > ts) return
    if (kn && kn.ts === ts && same(kn.v, p.v)) return
    await db.syncKnown.put({ k, v: p.v, ts, ...(p.a ? { a: p.a } : {}) })
    result.applied++
    switch (p.k) {
      case 'feed': {
        const local = await db.feeds.get(p.key)
        if (isDeleted(p.v)) {
          if (local) {
            await db.feeds.delete(p.key)
            await db.items.where('feedUrl').equals(p.key).delete()
          }
        } else if (p.v && typeof p.v === 'object') {
          if (!local) result.addedFeeds.push(p.key)
          const feed = applyFeedValue(p.v as Partial<FeedValue>, local ?? newFeed(p.key), ts)
          await db.feeds.put(feed)
          // Remember it as stored here, so fields an older device left out don't look like edits
          await db.syncKnown.put({ k, v: feedValue(feed), ts })
        }
        break
      }
      case 'read': {
        const item = await db.items.get(p.key)
        if (item && !!item.read !== (p.v === true)) {
          await db.items.update(p.key, { read: p.v === true ? 1 : 0, readTime: p.v === true ? ts : null })
        }
        break
      }
      case 'saved': {
        const item = await db.items.get(p.key)
        if (item) {
          if (!!item.saved !== (p.v === true)) await db.items.update(p.key, { saved: p.v === true ? 1 : 0 })
        } else if (p.v === true && p.a && !(await db.hidden.get(p.key))) {
          await db.items.put(stubItem(p.key, p.a, ts, await db.syncKnown.get(knownKey('read', p.key))))
        }
        break
      }
      case 'hidden': {
        if (p.v === true) {
          await db.items.delete(p.key)
          await db.hidden.put({ id: p.key, ts })
        } else {
          await db.hidden.delete(p.key)
        }
        break
      }
      case 'layout': {
        const v = p.v as Partial<LayoutValue>
        await setKv(FEED_ORDER_KEY, Array.isArray(v?.feedOrder) ? v.feedOrder : [])
        await setKv(FOLDER_ORDER_KEY, Array.isArray(v?.folderOrder) ? v.folderOrder : [])
        await setKv(EMPTY_FOLDERS_KEY, Array.isArray(v?.emptyFolders) ? v.emptyFolders : [])
        break
      }
      case 'video': {
        const v = p.v as Partial<VideoValue> | null
        if (v && typeof v.t === 'number' && Number.isFinite(v.t)) {
          const local = await db.videoPos.get(p.key)
          if (!local || local.ts <= ts) {
            await db.videoPos.put({ id: p.key, t: v.t, d: typeof v.d === 'number' && Number.isFinite(v.d) ? v.d : 0, ts })
          }
        }
        break
      }
      case 'playlist': {
        const list = await loadPlaylists()
        if (isDeleted(p.v)) {
          if (list.some((x) => x.id === p.key)) await setKv(PLAYLISTS_KEY, list.filter((x) => x.id !== p.key))
        } else if (p.v && typeof p.v === 'object') {
          const pl = playlistFromValue(p.key, p.v as Partial<PlaylistValue>, ts)
          await setKv(PLAYLISTS_KEY, list.some((x) => x.id === p.key) ? list.map((x) => (x.id === p.key ? pl : x)) : [...list, pl])
          // Remember it as stored here, so fields another device wrote differently don't look like edits
          await db.syncKnown.put({ k, v: playlistValue(pl), ts })
        }
        break
      }
      case 'highlight': {
        if (isDeleted(p.v)) {
          await db.highlights.delete(p.key)
        } else if (p.v && typeof p.v === 'object') {
          const h = highlightFromValue(p.key, p.v as Partial<HighlightValue>, ts)
          if (h.itemId && h.text) {
            await db.highlights.put(h)
            await db.syncKnown.put({ k, v: highlightValue(h), ts })
          }
        }
        break
      }
      // Unknown kinds are only remembered (a newer version of the app will use them)
    }
  })
}

/** A saved article another device has but this one never fetched. */
function stubItem(id: string, a: SavedArticle, ts: number, read: SyncKnown | undefined): Item {
  const cut = id.indexOf('\n')
  return {
    id,
    feedUrl: id.slice(0, cut),
    guid: id.slice(cut + 1),
    title: a.title ?? '',
    link: a.link ?? null,
    contentHtml: '',
    snippet: a.snippet ?? '',
    imageUrl: a.imageUrl ?? null,
    author: a.author ?? null,
    sortTime: a.sortTime || ts,
    firstSeen: Date.now(),
    wordCount: 0,
    read: read?.v === true ? 1 : 0,
    readTime: read?.v === true ? read.ts : null,
    readSynced: 1,
    saved: 1,
  }
}

/** What a newly fetched article should start as, from records received before it existed here. */
export async function knownStateFor(ids: string[]): Promise<Map<string, { read?: boolean; readTs?: number; saved?: boolean; hidden?: boolean }>> {
  const keys = ids.flatMap((id) => [knownKey('read', id), knownKey('saved', id), knownKey('hidden', id)])
  const rows = await db.syncKnown.bulkGet(keys)
  const out = new Map<string, { read?: boolean; readTs?: number; saved?: boolean; hidden?: boolean }>()
  ids.forEach((id, n) => {
    const [r, s, h] = [rows[n * 3], rows[n * 3 + 1], rows[n * 3 + 2]]
    if (r || s || h) out.set(id, { read: r?.v === true, readTs: r?.ts, saved: s?.v === true, hidden: h?.v === true })
  })
  return out
}

// ---------------------------------------------------------------------------------------------
// Exchange

interface ExchangeResponse {
  cursor: number
  more: boolean
  changes: { id: string; ts: number; data: string }[]
}

async function post(body: unknown): Promise<ExchangeResponse> {
  const res = await fetch('/api/v2/sync', {
    method: 'POST',
    headers: apiHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) {
    let msg = text
    try {
      msg = (JSON.parse(text) as { error?: string }).error ?? text
    } catch {
      // keep text
    }
    throw new HttpError(res.status, msg)
  }
  return JSON.parse(text) as ExchangeResponse
}

export interface SyncResult extends ApplyResult {
  sent: number
}

class StateChanged extends Error {}

async function assertCurrent(state: SyncState) {
  const now = await getSyncState()
  if (now?.syncCode !== state.syncCode || now.secretKey !== state.secretKey) throw new StateChanged()
}

async function runSync(state: SyncState): Promise<SyncResult> {
  const keys = await deriveKeys(state.syncCode, state.secretKey)
  const pending = await collectChanges()
  const toSend = pending.filter((p) => p.k)
  const result: SyncResult = { addedFeeds: [], applied: 0, sent: toSend.length }

  const records = await Promise.all(
    toSend.map(async (p) => ({
      id: await recordId(keys, p.k, p.key),
      ts: p.ts,
      data: await encryptString(JSON.stringify({ k: p.k, key: p.key, v: p.v, ...(p.a ? { a: p.a } : {}) } satisfies Payload), keys.secret),
    })),
  )

  let cursor = state.cursor
  const BATCH = 400
  let i = 0
  do {
    const batch = records.slice(i, i + BATCH)
    let res = await post({ account: keys.account, auth: keys.auth, since: cursor, push: batch })
    await assertCurrent(state)
    // What was sent is now the known value; the server's answer below may still override it
    await db.syncKnown.bulkPut(
      toSend.slice(i, i + BATCH).map((p) => ({ k: knownKey(p.k, p.key), v: p.v, ts: p.ts, ...(p.a ? { a: p.a } : {}) })),
    )
    for (;;) {
      for (const c of res.changes) {
        let payload: Payload
        try {
          payload = JSON.parse(await decryptString(c.data, keys.secret)) as Payload
          if (typeof payload.k !== 'string' || typeof payload.key !== 'string') throw new Error('Bad record')
        } catch (e) {
          console.warn('Skipping unreadable sync record', e)
          continue
        }
        await applyRemote(payload, c.ts, result)
      }
      cursor = res.cursor
      await saveState({ ...(await getSyncState())!, cursor })
      if (!res.more) break
      res = await post({ account: keys.account, auth: keys.auth, since: cursor, push: [] })
      await assertCurrent(state)
    }
    i += BATCH
  } while (i < records.length)

  // Articles whose state went out unchanged since: no longer dirty
  const sentItems = pending.filter((p) => p.item).map((p) => p.item!)
  await db.transaction('rw', db.items, async () => {
    const rows = await db.items.bulkGet(sentItems.map((s) => s.id))
    const done = rows.filter((r, n) => r && r.read === sentItems[n].read && r.saved === sentItems[n].saved).map((r) => r!.id)
    await db.items.bulkUpdate([...new Set(done)].map((key) => ({ key, changes: { readSynced: 1 } })))
  })
  return result
}

let running: Promise<SyncResult | null> | null = null

/** One full exchange. Safe to call often; concurrent calls share one run. */
export function syncNow(): Promise<SyncResult | null> {
  if (running) return running
  running = (async () => {
    // Offline mode or no connection: changes wait in the queue
    if (await offlineNow()) return null
    const state = await moveFromOldChain()
    if (!state) return null
    try {
      const r = await runSync(state)
      await assertCurrent(state)
      await saveState({ ...(await getSyncState())!, lastSyncAt: Date.now(), lastError: null })
      return r
    } catch (e) {
      if (e instanceof StateChanged) return null
      const fresh = await getSyncState()
      if (fresh?.syncCode === state.syncCode) await saveState({ ...fresh, lastError: e instanceof Error ? e.message : String(e) })
      throw e
    }
  })().finally(() => {
    running = null
  })
  return running
}

/** Starts over: forgets what was exchanged, sends everything this browser has and pulls everything. */
export async function forceSync(): Promise<SyncResult | null> {
  if (running) await running.catch(() => undefined)
  const state = await getSyncState()
  if (!state) return null
  await resetExchange(state)
  return syncNow()
}

async function resetExchange(state: SyncState) {
  await db.transaction('rw', [db.items, db.syncKnown, db.kv], async () => {
    // Keep remembered unsubscribes and deletions, so they are sent again rather than undone.
    // Unsubscribes keep the time they happened, so a feed subscribed to again elsewhere since stays.
    const keep = await db.syncKnown.filter((r) => (r.k.startsWith('feed\n') && isDeleted(r.v)) || r.k.startsWith('hidden\n')).toArray()
    await db.syncKnown.clear()
    await db.syncKnown.bulkPut(
      keep.map((r) => (r.k.startsWith('feed\n') ? { ...r, v: { known: true } } : { ...r, ts: 0, v: false })),
    )
    await db.items.toCollection().modify({ readSynced: 0 })
    await setKv(STATE_KEY, { ...state, cursor: 0 })
  })
}

// ---------------------------------------------------------------------------------------------
// Setting up

export function randomSyncCode(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, '0')).join('')
}

export function randomSecretKey(): string {
  return `${b64encode(crypto.getRandomValues(new Uint8Array(16)))}:${b64encode(crypto.getRandomValues(new Uint8Array(32)))}`
}

async function start(syncCode: string, secretKey: string, extra: Partial<SyncState> = {}): Promise<SyncState> {
  const keys = await decodeKey(secretKey) // validates
  const state: SyncState = { syncCode: syncCode.toLowerCase(), secretKey: keys.encoded, cursor: 0, ...extra }
  await db.transaction('rw', [db.items, db.syncKnown, db.kv], async () => {
    await db.syncKnown.clear()
    await db.items.toCollection().modify({ readSynced: 0 })
    await setKv(STATE_KEY, state)
  })
  return state
}

/** Joins the sync other devices use, from the "add device" link they show. */
export async function joinSync(syncCode: string, secretKey: string): Promise<SyncState> {
  if (running) await running.catch(() => undefined)
  return start(syncCode, secretKey)
}

/** Starts a new sync with this browser as the first device. */
export async function createSync(): Promise<SyncState> {
  if (running) await running.catch(() => undefined)
  return start(randomSyncCode(), randomSecretKey())
}

/** Stops syncing in this browser. Feeds and articles stay; other devices are not touched. */
export async function leaveSync(): Promise<void> {
  if (running) await running.catch(() => undefined)
  await db.transaction('rw', [db.items, db.syncKnown, db.kv], async () => {
    await setKv(STATE_KEY, null)
    await db.syncKnown.clear()
    await db.items.toCollection().modify({ readSynced: 0 })
  })
}

/** The link other devices open (or paste) to join. Same format as Feeder's old chain links. */
export function addDeviceLink(state: Pick<SyncState, 'syncCode' | 'secretKey'>): string {
  return `https://feederapp.nononsenseapps.com/sync/join?sync_code=${state.syncCode}&key=${encodeURIComponent(state.secretKey)}`
}

async function serverReady(): Promise<boolean> {
  try {
    const res = await fetch('/api/v2/status', { headers: apiHeaders() })
    return res.ok && ((await res.json()) as { store?: string }).store !== 'none'
  } catch {
    return false
  }
}

/**
 * Moving over from Feeder's old sync chain, once per browser, with nothing lost:
 *  1. one last pull from the old chain, so read marks and feed changes made elsewhere arrive
 *  2. the same sync code and key name the new sync account, so no device needs re-pairing
 *  3. feeds the chain knew but this browser removed become unsubscribes, and the chain's read
 *     marks for articles not fetched yet are kept
 *  4. the first sync then sends every feed, read mark and saved article this browser has
 */
export async function moveFromOldChain(): Promise<SyncState | null> {
  const current = await getSyncState()
  if (current) return current
  const old = await getKv<{ syncCode: string; secretKey: string } | null>(OLD_CHAIN_KEY, null)
  if (!old?.syncCode || !old.secretKey) return null

  const { syncNow: oldSync } = await import('./sync')
  // Until the server has its sync database, keep using the old chain
  if (!(await serverReady())) {
    await oldSync()
    return null
  }
  try {
    await Promise.race([oldSync(), new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 30_000))])
  } catch (e) {
    console.warn('Last pull from the old sync chain failed; moving over with what is here', e)
  }

  const state = await start(old.syncCode, old.secretKey, { fromChain: true })
  await db.transaction('rw', [db.syncKnown, db.remoteFeeds, db.remoteReadMarks, db.kv], async () => {
    const seen = await db.remoteFeeds.toArray()
    await db.syncKnown.bulkPut(seen.map((r) => ({ k: knownKey('feed', r.url), v: { known: true }, ts: 0 })))
    const marks = await db.remoteReadMarks.toArray()
    await db.syncKnown.bulkPut(marks.map((m) => ({ k: knownKey('read', m.key), v: true, ts: 0 })))
    await setKv(OLD_CHAIN_DONE_KEY, { ...old, at: Date.now() })
    await setKv(OLD_CHAIN_KEY, null)
  })
  return state
}
