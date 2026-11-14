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
    fullTextByDefault: false,
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
