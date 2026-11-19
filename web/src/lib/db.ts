import Dexie, { type EntityTable } from 'dexie'

/** A subscription. The synced fields mirror EncryptedFeed in the Android app's sync/RestBodies.kt. */
export interface Feed {
  url: string
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
  /** Epoch millis; the newer side wins when syncing. */
  whenModified: number
  // Local only
  siteUrl?: string | null
  lastFetched?: number
  lastError?: string | null
}

export interface Item {
  /** feedUrl + "\n" + guid */
  id: string
  feedUrl: string
  guid: string
  title: string
  link: string | null
  contentHtml: string
  snippet: string
  imageUrl: string | null
  author: string | null
  /** Publication time, or first-seen time when the feed gives none (epoch millis). */
  sortTime: number
  firstSeen: number
  wordCount: number
  /** 0/1 so they can be indexed. */
  read: number
  readTime: number | null
  /** 1 once the read mark has been sent to (or came from) the sync chain. */
  readSynced: number
  saved: number
  enclosureUrl?: string | null
  fullHtml?: string | null
  fullWordCount?: number
  ogChecked?: number
  hnPoints?: number | null
  hnComments?: number | null
  hnId?: string | null
  hnChecked?: number
  summary?: string | null
}

/** A read mark received from the chain for an article this browser hasn't fetched yet. */
export interface RemoteReadMark {
  key: string
}

/** Feed URLs seen in the chain's feed list, to tell remote deletions from new local feeds. */
export interface RemoteFeed {
  url: string
}

export interface KeyValue {
  key: string
  value: unknown
}

export const itemId = (feedUrl: string, guid: string) => `${feedUrl}\n${guid}`

class FeederDb extends Dexie {
  feeds!: EntityTable<Feed, 'url'>
  items!: EntityTable<Item, 'id'>
  remoteReadMarks!: EntityTable<RemoteReadMark, 'key'>
  remoteFeeds!: EntityTable<RemoteFeed, 'url'>
  kv!: EntityTable<KeyValue, 'key'>

  constructor() {
    super('feeder-web')
    this.version(1).stores({
      feeds: '&url, tag',
      items: '&id, feedUrl, sortTime, read, saved, [read+readSynced]',
      remoteReadMarks: '&key',
      remoteFeeds: '&url',
      kv: '&key',
    })
    // Full article by default for every feed: switch the existing ones on once. Bumping
    // whenModified makes the change win over older settings from the sync chain.
    this.version(2).upgrade(async (tx) => {
      const now = Date.now()
      await tx
        .table('feeds')
        .toCollection()
        .modify((f: Feed) => {
          if (!f.fullTextByDefault) {
            f.fullTextByDefault = true
            f.whenModified = now
          }
        })
    })
  }
}

export const db = new FeederDb()

export async function getKv<T>(key: string, fallback: T): Promise<T> {
  const row = await db.kv.get(key)
  return row === undefined ? fallback : (row.value as T)
}

export async function setKv(key: string, value: unknown): Promise<void> {
  await db.kv.put({ key, value })
}

export function newFeed(url: string, partial: Partial<Feed> = {}): Feed {
  return {
    url,
    title: '',
    customTitle: '',
    tag: '',
    imageUrl: null,
    fullTextByDefault: true,
    openArticlesWith: '',
    alternateId: false,
    fetchOgImages: false,
    blockRules: '',
    allowRules: '',
    whenModified: Date.now(),
    ...partial,
  }
}

export const feedDisplayTitle = (f: Pick<Feed, 'customTitle' | 'title' | 'url'> | undefined) =>
  f ? f.customTitle.trim() || f.title || f.url : ''

/**
 * Moves a feed into another folder (tag); "" means no folder. Bumps whenModified so the move
 * wins over the older tag in the sync chain. Returns false when nothing changed.
 */
export async function moveFeedToTag(url: string, tag: string): Promise<boolean> {
  return db.transaction('rw', db.feeds, async () => {
    const feed = await db.feeds.get(url)
    if (!feed || feed.tag === tag) return false
    await db.feeds.put({ ...feed, tag, whenModified: Math.max(Date.now(), feed.whenModified + 1) })
    return true
  })
}

/**
 * The manual order of feeds inside their folders, as a list of feed URLs. Kept in this browser
 * only: the sync chain (EncryptedFeed in the Android app) has no field for it.
 */
export const FEED_ORDER_KEY = 'feedOrder'

/** Sorts one folder's feeds: by manual position first, then the rest alphabetically. */
export function sortFolder(feeds: Feed[], order: string[]): Feed[] {
  const pos = new Map(order.map((u, i) => [u, i]))
  return [...feeds].sort((a, b) => {
    const pa = pos.get(a.url) ?? Infinity
    const pb = pos.get(b.url) ?? Infinity
    if (pa !== pb) return pa < pb ? -1 : 1
    return feedDisplayTitle(a).toLowerCase().localeCompare(feedDisplayTitle(b).toLowerCase())
  })
}

/**
 * Puts `url` into a folder whose feeds are currently shown as `folderUrls`, just before
 * `beforeUrl` (null = at the end). Returns the new manual order for every feed.
 */
export function placeInOrder(order: string[], folderUrls: string[], url: string, beforeUrl: string | null): string[] {
  const folder = folderUrls.filter((u) => u !== url)
  const at = beforeUrl === null ? -1 : folder.indexOf(beforeUrl)
  folder.splice(at < 0 ? folder.length : at, 0, url)
  const inFolder = new Set(folder)
  return [...order.filter((u) => !inFolder.has(u)), ...folder]
}

// ---------------------------------------------------------------------------------------------
// Manage feeds page

const bump = (f: Feed) => Math.max(Date.now(), f.whenModified + 1)

/**
 * Changes synced fields of a feed and bumps whenModified so the change wins in the sync chain.
 * Returns false when the feed is gone or nothing changed.
 */
export async function updateFeed(url: string, patch: Partial<Omit<Feed, 'url' | 'whenModified'>>): Promise<boolean> {
  return db.transaction('rw', db.feeds, async () => {
    const feed = await db.feeds.get(url)
    if (!feed) return false
    const changed = (Object.keys(patch) as (keyof typeof patch)[]).some((k) => feed[k] !== patch[k])
    if (!changed) return false
    await db.feeds.put({ ...feed, ...patch, whenModified: bump(feed) })
    return true
  })
}

/** Sets the title shown for a feed. An empty title, or the feed's own title, clears the custom one. */
export async function renameFeed(url: string, title: string): Promise<boolean> {
  const feed = await db.feeds.get(url)
  if (!feed) return false
  const t = title.trim()
  return updateFeed(url, { customTitle: t === feed.title ? '' : t })
}

/** Moves several feeds into folder `tag` ("" = no folder). Returns how many moved. */
export async function moveFeedsToTag(urls: string[], tag: string): Promise<number> {
  let n = 0
  for (const url of urls) if (await moveFeedToTag(url, tag)) n++
  return n
}

/** Unsubscribes from feeds: removes them and their articles. The next sync removes them from the chain. */
export async function deleteFeeds(urls: string[]): Promise<void> {
  await db.transaction('rw', db.feeds, db.items, async () => {
    await db.feeds.bulkDelete(urls)
    await db.items.where('feedUrl').anyOf(urls).delete()
  })
}

/**
 * Folders created on the manage page that have no feeds yet. Kept in this browser only: in the
 * sync chain a folder is just the tag on its feeds, so an empty one can't be sent.
 */
export const EMPTY_FOLDERS_KEY = 'emptyFolders'

/** The manual order of folders (tags), kept in this browser only like FEED_ORDER_KEY. */
export const FOLDER_ORDER_KEY = 'folderOrder'

/** Sorts folder names by manual position first, then the rest alphabetically. */
export function sortFolders(tags: string[], order: string[]): string[] {
  const pos = new Map(order.map((t, i) => [t, i]))
  return [...tags].sort((a, b) => {
    const pa = pos.get(a) ?? Infinity
    const pb = pos.get(b) ?? Infinity
    if (pa !== pb) return pa < pb ? -1 : 1
    return a.toLowerCase().localeCompare(b.toLowerCase())
  })
}

/** Moves `tag` just before `beforeTag` (null = at the end) among `shown`. Returns the new folder order. */
export function placeFolder(shown: string[], tag: string, beforeTag: string | null): string[] {
  const list = shown.filter((t) => t !== tag)
  const at = beforeTag === null ? -1 : list.indexOf(beforeTag)
  list.splice(at < 0 ? list.length : at, 0, tag)
  return list
}

/** Adds an empty folder. Returns false when a folder with that name already exists. */
export async function addEmptyFolder(name: string): Promise<boolean> {
  const tag = name.trim()
  if (!tag) return false
  if (await db.feeds.where('tag').equals(tag).count()) return false
  const empty = await getKv<string[]>(EMPTY_FOLDERS_KEY, [])
  if (empty.includes(tag)) return false
  await setKv(EMPTY_FOLDERS_KEY, [...empty, tag])
  return true
}

const renameIn = (list: string[], from: string, to: string) => {
  const out: string[] = []
  for (const t of list) {
    const n = t === from ? to : t
    if (!out.includes(n)) out.push(n)
  }
  return out
}

/**
 * Renames a folder by changing the tag on all its feeds (each bumped so it syncs). Renaming onto
 * an existing folder merges the two. Returns how many feeds changed.
 */
export async function renameTag(from: string, to: string): Promise<number> {
  const target = to.trim()
  if (!from || !target || from === target) return 0
  return db.transaction('rw', db.feeds, db.kv, async () => {
    const feeds = await db.feeds.where('tag').equals(from).toArray()
    await db.feeds.bulkPut(feeds.map((f) => ({ ...f, tag: target, whenModified: bump(f) })))
    await setKv(EMPTY_FOLDERS_KEY, renameIn(await getKv<string[]>(EMPTY_FOLDERS_KEY, []), from, target))
    await setKv(FOLDER_ORDER_KEY, renameIn(await getKv<string[]>(FOLDER_ORDER_KEY, []), from, target))
    return feeds.length
  })
}

/**
 * Deletes a folder. With "keep" its feeds move to no folder; with "unsubscribe" they are removed.
 * Returns how many feeds were affected.
 */
export async function deleteTag(tag: string, feedsMode: 'keep' | 'unsubscribe'): Promise<number> {
  if (!tag) return 0
  return db.transaction('rw', db.feeds, db.items, db.kv, async () => {
    const feeds = await db.feeds.where('tag').equals(tag).toArray()
    if (feedsMode === 'keep') await db.feeds.bulkPut(feeds.map((f) => ({ ...f, tag: '', whenModified: bump(f) })))
    else await deleteFeeds(feeds.map((f) => f.url))
    await setKv(EMPTY_FOLDERS_KEY, (await getKv<string[]>(EMPTY_FOLDERS_KEY, [])).filter((t) => t !== tag))
    await setKv(FOLDER_ORDER_KEY, (await getKv<string[]>(FOLDER_ORDER_KEY, [])).filter((t) => t !== tag))
    return feeds.length
  })
}

/**
 * Per-feed "open articles with", using the Android app's values (SettingsStore.kt). "" follows the
 * app default (the reader on the web). The web opens web view and custom tab as a browser tab.
 */
export const OPEN_WITH_READER = '0'
export const OPEN_WITH_BROWSER = '2'
export const opensInBrowser = (f: Pick<Feed, 'openArticlesWith'> | undefined) => !!f && ['1', '2', '3'].includes(f.openArticlesWith)
