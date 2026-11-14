/**
 * Feeder sync chain client, ported from the Android app's sync/SyncRestClient.kt.
 * Same server, same encrypted formats, so this browser is just another device in the chain.
 */
import { apiHeaders, HttpError } from './api'
import { b64encode, decodeKey, decryptString, encryptString, type SecretKeys } from './crypto'
import { javaStringHash as javaHash } from './feedparse'
import { db, getKv, itemId, newFeed, setKv, type Feed } from './db'

export interface SyncState {
  syncCode: string
  deviceId: number
  deviceName: string
  secretKey: string
  /** Custom sync server; empty = the default one the backend is configured with. */
  server: string
  latestMessageTimestamp: number
  lastFeedsRemoteHash: number
  /** Hash of our own feed list right after the last successful exchange (see sendUpdatedFeeds). */
  localFeedsHashAtLastSync: number | null
  lastSyncAt?: number
  lastError?: string | null
}

/** The fields the chain shares for each feed (EncryptedFeed in RestBodies.kt). */
interface EncryptedFeed {
  url: string
  title?: string
  customTitle?: string
  tag?: string
  imageUrl?: string | null
  fullTextByDefault?: boolean
  openArticlesWith?: string
  alternateId?: boolean
  fetchOgImages?: boolean
  blockRules?: string
  allowRules?: string
  whenModified?: number
}

const SYNC_KEY = 'sync'
const DEVICE_NOT_REGISTERED = 'device not registered'

export const getSyncState = () => getKv<SyncState | null>(SYNC_KEY, null)
const saveSyncState = (s: SyncState | null) => setKv(SYNC_KEY, s)

function browserDeviceName(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /Firefox\//.test(ua)
      ? 'Firefox'
      : /Chrome\//.test(ua)
        ? 'Chrome'
        : /Safari\//.test(ua)
          ? 'Safari'
          : 'Browser'
  const os = /Windows/.test(ua)
    ? 'Windows'
    : /Mac OS X/.test(ua)
      ? 'Mac'
      : /Android/.test(ua)
        ? 'Android'
        : /Linux/.test(ua)
          ? 'Linux'
          : /iPhone|iPad/.test(ua)
            ? 'iOS'
            : ''
  return `Feeder Web (${browser}${os ? ` on ${os}` : ''})`
}

async function call<T>(
  state: Pick<SyncState, 'server' | 'syncCode' | 'deviceId'> | null,
  method: 'GET' | 'POST' | 'DELETE',
  path: string,
  options: { body?: unknown; syncCode?: string; ifMatch?: string; query?: Record<string, string> } = {},
): Promise<T> {
  const headers = apiHeaders({ 'content-type': 'application/json' })
  const code = options.syncCode ?? state?.syncCode
  if (code) headers.set('x-feeder-id', code)
  if (state && state.deviceId) headers.set('x-feeder-device-id', String(state.deviceId))
  if (state?.server) headers.set('x-sync-server', state.server)
  if (options.ifMatch) headers.set('if-match', options.ifMatch)
  const qs = options.query ? `?${new URLSearchParams(options.query)}` : ''
  const res = await fetch(`/api/sync/${path}${qs}`, {
    method,
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })
  const text = await res.text()
  if (!res.ok) throw new HttpError(res.status, text)
  return (text ? JSON.parse(text) : {}) as T
}

// ---------------------------------------------------------------------------------------------
// Joining and leaving

export async function joinChain(syncCode: string, secretKey: string, server = ''): Promise<SyncState> {
  const keys = await decodeKey(secretKey)
  const deviceName = browserDeviceName()
  const res = await call<{ syncCode: string; deviceId: number }>({ server, syncCode, deviceId: 0 }, 'POST', 'join', {
    syncCode,
    body: { deviceName: await encryptString(deviceName, keys) },
  })
  const state: SyncState = {
    syncCode: res.syncCode,
    deviceId: res.deviceId,
    deviceName,
    secretKey: keys.encoded,
    server,
    latestMessageTimestamp: 0,
    lastFeedsRemoteHash: 0,
    localFeedsHashAtLastSync: null,
  }
  await saveSyncState(state)
  return state
}

/** Starts a new chain with this browser as the first device. */
export async function createChain(server = ''): Promise<SyncState> {
  const aes = crypto.getRandomValues(new Uint8Array(16))
  const hmac = crypto.getRandomValues(new Uint8Array(32))
  const secretKey = `${b64encode(aes)}:${b64encode(hmac)}`
  const keys = await decodeKey(secretKey)
  const deviceName = browserDeviceName()
  const res = await call<{ syncCode: string; deviceId: number }>({ server, syncCode: '', deviceId: 0 }, 'POST', 'create', {
    body: { deviceName: await encryptString(deviceName, keys) },
  })
  const state: SyncState = {
    syncCode: res.syncCode,
    deviceId: res.deviceId,
    deviceName,
    secretKey,
    server,
    latestMessageTimestamp: 0,
    lastFeedsRemoteHash: 0,
    localFeedsHashAtLastSync: null,
  }
  await saveSyncState(state)
  return state
}

/** The link other devices open (or paste) to join, same format as the Android app. */
export function addDeviceLink(state: SyncState): string {
  return `https://feederapp.nononsenseapps.com/sync/join?sync_code=${state.syncCode}&key=${encodeURIComponent(state.secretKey)}`
}

async function forgetChain() {
  await saveSyncState(null)
  await db.remoteFeeds.clear()
  await db.remoteReadMarks.clear()
}

export async function leaveChain(): Promise<void> {
  const state = await getSyncState()
  if (!state) return
  try {
    await call(state, 'DELETE', `devices/${state.deviceId}`)
  } finally {
    await forgetChain()
  }
}

export interface Device {
  deviceId: number
  deviceName: string
}

export async function getDevices(): Promise<Device[]> {
  const state = await getSyncState()
  if (!state) return []
  const keys = await decodeKey(state.secretKey)
  const res = await guarded(state, () =>
    call<{ devices: { deviceId: number; deviceName: string }[] }>(state, 'GET', 'devices'),
  )
  return Promise.all(
    res.devices.map(async (d) => ({
      deviceId: d.deviceId,
      deviceName: await decryptString(d.deviceName, keys).catch(() => '(unreadable name)'),
    })),
  )
}

export async function removeDevice(deviceId: number): Promise<void> {
  const state = await getSyncState()
  if (!state) return
  await guarded(state, () => call(state, 'DELETE', `devices/${deviceId}`))
}

/** If another device removed this one from the chain, leave locally (like leaveChainIfKickedOut). */
async function guarded<T>(_state: SyncState, block: () => Promise<T>): Promise<T> {
  try {
    return await block()
  } catch (e) {
    if (e instanceof HttpError && e.body.toLowerCase().includes(DEVICE_NOT_REGISTERED)) {
      await forgetChain()
      throw new Error('This browser was removed from the sync chain by another device.')
    }
    throw e
  }
}

// ---------------------------------------------------------------------------------------------
// Feeds

function toEncryptedFeed(f: Feed): Required<EncryptedFeed> {
  return {
    url: f.url,
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
    whenModified: f.whenModified,
  }
}

function applyRemote(remote: EncryptedFeed, base: Feed): Feed {
  return {
    ...base,
    url: remote.url,
    title: remote.title ?? '',
    customTitle: remote.customTitle ?? '',
    tag: remote.tag ?? '',
    imageUrl: remote.imageUrl ?? null,
    fullTextByDefault: remote.fullTextByDefault ?? false,
    openArticlesWith: remote.openArticlesWith ?? '',
    alternateId: remote.alternateId ?? false,
    fetchOgImages: remote.fetchOgImages ?? false,
    blockRules: remote.blockRules ?? '',
    allowRules: remote.allowRules ?? '',
    whenModified: remote.whenModified ?? 0,
  }
}

async function localFeedsSorted(): Promise<Feed[]> {
  const feeds = await db.feeds.toArray()
  // Stable order across devices: by URL (sendUpdatedFeeds in SyncRestClient.kt)
  return feeds.sort((a, b) => (a.url < b.url ? -1 : a.url > b.url ? 1 : 0))
}

async function localFeedsHash(): Promise<number> {
  return javaHash(JSON.stringify((await localFeedsSorted()).map(toEncryptedFeed)))
}

/** Port of feedDiffing in SyncRestClient.kt. Returns feed URLs that were added. */
async function feedDiffing(remoteFeeds: EncryptedFeed[]): Promise<string[]> {
  const added: string[] = []
  await db.transaction('rw', db.feeds, db.items, db.remoteFeeds, async () => {
    const seen = new Set((await db.remoteFeeds.toArray()).map((r) => r.url))
    const remoteUrls = new Set(remoteFeeds.map((f) => f.url))

    for (const url of seen) {
      if (!remoteUrls.has(url)) {
        // Deleted on another device
        await db.feeds.delete(url)
        await db.items.where('feedUrl').equals(url).delete()
      }
    }
    for (const remote of remoteFeeds) {
      const local = await db.feeds.get(remote.url)
      if (!local && !seen.has(remote.url)) {
        await db.feeds.put(applyRemote(remote, newFeed(remote.url)))
        added.push(remote.url)
      } else if (local && (remote.whenModified ?? 0) > local.whenModified) {
        await db.feeds.put(applyRemote(remote, local))
      }
      // !local && seen: deleted here, removed from the chain on the next send
    }
    await db.remoteFeeds.clear()
    await db.remoteFeeds.bulkPut(remoteFeeds.map((f) => ({ url: f.url })))
  })
  return added
}

async function getFeeds(state: SyncState, keys: SecretKeys): Promise<string[]> {
  let res: { hash: number; encrypted: string }
  try {
    res = await guarded(state, () => call<{ hash: number; encrypted: string }>(state, 'GET', 'feeds'))
  } catch (e) {
    // A chain nobody has uploaded feeds to yet
    if (e instanceof HttpError && e.status === 404) return []
    throw e
  }
  if (res.hash === state.lastFeedsRemoteHash || !res.encrypted) return []
  const decoded = JSON.parse(await decryptString(res.encrypted, keys)) as { feeds: EncryptedFeed[] }
  // Were there local edits not yet sent? Then keep them marked as unsent after merging.
  const before = await localFeedsHash()
  const noLocalEdits =
    state.localFeedsHashAtLastSync === before ||
    (state.localFeedsHashAtLastSync === null && (await db.feeds.count()) === 0)
  const added = await feedDiffing(decoded.feeds ?? [])
  state.lastFeedsRemoteHash = res.hash
  if (noLocalEdits) state.localFeedsHashAtLastSync = await localFeedsHash()
  await saveSyncState(state)
  return added
}

/**
 * Uploads the feed list when it changed here. The Android app compares a Kotlin hashCode with
 * the server's hash, which a browser can't reproduce, so instead this remembers its own hash
 * from the last exchange and only uploads after a local change. That avoids the two devices
 * re-uploading to each other forever.
 */
async function sendUpdatedFeeds(state: SyncState, keys: SecretKeys): Promise<void> {
  const feeds = (await localFeedsSorted()).map(toEncryptedFeed)
  const currentHash = javaHash(JSON.stringify(feeds))
  if (state.localFeedsHashAtLastSync === currentHash) return
  if (state.localFeedsHashAtLastSync === null && feeds.length === 0) {
    state.localFeedsHashAtLastSync = currentHash
    await saveSyncState(state)
    return
  }
  const encrypted = await encryptString(JSON.stringify({ feeds }), keys)
  try {
    const res = await guarded(state, () =>
      call<{ hash: number }>(state, 'POST', 'feeds', {
        ifMatch: `W/"${state.lastFeedsRemoteHash}"`,
        body: { contentHash: currentHash, encrypted },
      }),
    )
    state.lastFeedsRemoteHash = res.hash
    state.localFeedsHashAtLastSync = currentHash
    await db.remoteFeeds.clear()
    await db.remoteFeeds.bulkPut(feeds.map((f) => ({ url: f.url })))
    await saveSyncState(state)
  } catch (e) {
    if (e instanceof HttpError && e.status === 412) {
      // Someone else updated first: fetch theirs, then try again next sync
      state.lastFeedsRemoteHash = -1
      await saveSyncState(state)
      return
    }
    throw e
  }
}

// ---------------------------------------------------------------------------------------------
// Read marks

async function getRead(state: SyncState, keys: SecretKeys): Promise<number> {
  const res = await guarded(state, () =>
    call<{ readMarks: { timestamp: number; encrypted: string }[] }>(state, 'GET', 'ereadmark', {
      query: { since: String(state.latestMessageTimestamp + 1) },
    }),
  )
  let applied = 0
  for (const mark of res.readMarks ?? []) {
    try {
      const content = JSON.parse(await decryptString(mark.encrypted, keys)) as { feedUrl: string; articleGuid: string }
      const key = itemId(content.feedUrl, content.articleGuid)
      await db.transaction('rw', db.items, db.remoteReadMarks, async () => {
        await db.remoteReadMarks.put({ key })
        const item = await db.items.get(key)
        if (item && !item.read) {
          await db.items.update(key, { read: 1, readTime: mark.timestamp, readSynced: 1 })
          applied++
        } else if (item) {
          await db.items.update(key, { readSynced: 1 })
        }
      })
    } catch (e) {
      console.warn('Skipping unreadable read mark', e)
    }
    state.latestMessageTimestamp = Math.max(state.latestMessageTimestamp, mark.timestamp)
  }
  await saveSyncState(state)
  return applied
}

async function sendReadMarks(state: SyncState, keys: SecretKeys): Promise<number> {
  const pending = await db.items.where('[read+readSynced]').equals([1, 0]).toArray()
  for (let i = 0; i < pending.length; i += 100) {
    const batch = pending.slice(i, i + 100)
    const items = await Promise.all(
      batch.map(async (it) => ({
        encrypted: await encryptString(JSON.stringify({ feedUrl: it.feedUrl, articleGuid: it.guid }), keys),
      })),
    )
    await guarded(state, () => call(state, 'POST', 'ereadmark', { body: { items } }))
    await db.items.bulkUpdate(batch.map((it) => ({ key: it.id, changes: { readSynced: 1 } })))
  }
  return pending.length
}

// ---------------------------------------------------------------------------------------------

export interface SyncResult {
  addedFeeds: string[]
  readMarksApplied: number
  readMarksSent: number
}

let running: Promise<SyncResult | null> | null = null

/** One full exchange with the chain. Safe to call often; concurrent calls share one run. */
export function syncNow(): Promise<SyncResult | null> {
  if (running) return running
  running = (async () => {
    const state = await getSyncState()
    if (!state) return null
    const keys = await decodeKey(state.secretKey)
    try {
      const addedFeeds = await getFeeds(state, keys)
      const readMarksApplied = await getRead(state, keys)
      await sendUpdatedFeeds(state, keys)
      const readMarksSent = await sendReadMarks(state, keys)
      const fresh = (await getSyncState()) ?? state
      await saveSyncState({ ...fresh, lastSyncAt: Date.now(), lastError: null })
      return { addedFeeds, readMarksApplied, readMarksSent }
    } catch (e) {
      const fresh = await getSyncState()
      if (fresh) await saveSyncState({ ...fresh, lastError: e instanceof Error ? e.message : String(e) })
      throw e
    }
  })().finally(() => {
    running = null
  })
  return running
}
