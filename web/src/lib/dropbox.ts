/**
 * Dropbox as the shared place for big files: ebooks (/Ebooks), ZIM books (/ZIM) and audiobooks
 * (/Audiobooks) live in the
 * app's own folder (Apps/<app name>/), so every device sees the same library. Reading positions
 * and highlights don't go here; they travel in Feeder's own encrypted sync (sync2.ts).
 *
 * Sign-in is OAuth with PKCE (no app secret). The refresh token it gives never expires and works
 * from any device with the same app key, so sync2 carries it (end-to-end encrypted, kind
 * "dropbox") and the other devices are signed in without asking. The Android app does the same
 * (sync2/ and dropbox/ in the app).
 */
import { getKv, setKv } from './db'

/**
 * The Dropbox app's key ("Feeder" app in Ashish's Dropbox, app folder access). It is public:
 * PKCE apps have no secret. VITE_DROPBOX_APP_KEY or the key typed at sign-in override it.
 */
export const DEFAULT_DROPBOX_APP_KEY = (import.meta.env?.VITE_DROPBOX_APP_KEY as string | undefined) || 'nw6h0xyqgr4g1wh'

export const DROPBOX_SCOPES = 'account_info.read files.metadata.read files.metadata.write files.content.read files.content.write'

export const EBOOKS_FOLDER = '/Ebooks'
export const ZIM_FOLDER = '/ZIM'
export const AUDIOBOOKS_FOLDER = '/Audiobooks'

/** What sync2 carries between devices (kind "dropbox", key "main"). */
export interface DropboxLink {
  appKey: string
  refreshToken: string
  /** The account's display name, for Settings. */
  name: string
  email: string
}

interface DropboxState extends DropboxLink {
  accessToken: string
  expiresAt: number
}

export const DROPBOX_KEY = 'dropbox'
const PENDING_KEY = 'feeder.dropboxPending'
/** When the link was last set or cleared here, so sync knows the newer side. */
export const DROPBOX_TS_KEY = 'dropboxTs'

const listeners = new Set<() => void>()
export const onDropboxChange = (fn: () => void) => {
  listeners.add(fn)
  return () => void listeners.delete(fn)
}
const changed = () => listeners.forEach((fn) => fn())

export async function getDropbox(): Promise<DropboxState | null> {
  const s = await getKv<DropboxState | null>(DROPBOX_KEY, null)
  return s?.refreshToken && s.appKey ? s : null
}

export async function dropboxLink(): Promise<DropboxLink | null> {
  const s = await getDropbox()
  return s ? { appKey: s.appKey, refreshToken: s.refreshToken, name: s.name, email: s.email } : null
}

/** Signs in with a link another device synced (or clears it with null). Doesn't bump the time. */
export async function applyDropboxLink(link: DropboxLink | null): Promise<void> {
  const now = await getDropbox()
  if (!link) {
    if (now) await setKv(DROPBOX_KEY, null)
  } else if (now?.refreshToken !== link.refreshToken || now.appKey !== link.appKey) {
    await setKv(DROPBOX_KEY, { ...link, accessToken: '', expiresAt: 0 } satisfies DropboxState)
  } else if (now.name !== link.name || now.email !== link.email) {
    await setKv(DROPBOX_KEY, { ...now, name: link.name, email: link.email })
  }
  changed()
}

// ---------------------------------------------------------------------------------------------
// Sign-in (OAuth 2 code flow with PKCE)

const b64url = (bytes: Uint8Array) =>
  btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

export async function pkceChallenge(verifier: string): Promise<string> {
  return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))))
}

export const redirectUri = () => `${window.location.origin}/dropbox-auth`

/** Sends the browser to Dropbox's sign-in page; it comes back to /dropbox-auth. */
export async function startDropboxSignIn(appKey: string, returnTo = window.location.hash || '#/books') {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)))
  const state = b64url(crypto.getRandomValues(new Uint8Array(16)))
  sessionStorage.setItem(PENDING_KEY, JSON.stringify({ verifier, state, appKey, returnTo }))
  const q = new URLSearchParams({
    client_id: appKey,
    response_type: 'code',
    code_challenge: await pkceChallenge(verifier),
    code_challenge_method: 'S256',
    token_access_type: 'offline',
    // Only what Feeder uses. Without this Dropbox asks for every permission ticked on the app,
    // and one team permission makes it refuse personal accounts ("sign in with a work account").
    scope: DROPBOX_SCOPES,
    redirect_uri: redirectUri(),
    state,
  })
  window.location.assign(`https://www.dropbox.com/oauth2/authorize?${q}`)
}

/**
 * Finishes a sign-in when the page loads at /dropbox-auth?code=… Returns the hash to go back to,
 * or null when this page load isn't a sign-in.
 */
export async function finishDropboxSignIn(): Promise<{ returnTo: string; error?: string } | null> {
  if (window.location.pathname !== '/dropbox-auth') return null
  const q = new URLSearchParams(window.location.search)
  const pending = JSON.parse(sessionStorage.getItem(PENDING_KEY) || 'null') as { verifier: string; state: string; appKey: string; returnTo: string } | null
  sessionStorage.removeItem(PENDING_KEY)
  const returnTo = pending?.returnTo || '#/books'
  history.replaceState(null, '', `/${returnTo}`)
  if (q.get('error')) return { returnTo, error: q.get('error_description') || q.get('error') || 'Sign-in cancelled' }
  const code = q.get('code')
  if (!code || !pending || q.get('state') !== pending.state) return { returnTo, error: 'The Dropbox sign-in expired. Try again.' }
  try {
    const res = await fetch('https://api.dropboxapi.com/oauth2/token', {
      method: 'POST',
      body: new URLSearchParams({ code, grant_type: 'authorization_code', client_id: pending.appKey, code_verifier: pending.verifier, redirect_uri: redirectUri() }),
    })
    const t = (await res.json()) as { access_token?: string; refresh_token?: string; expires_in?: number; error_description?: string }
    if (!res.ok || !t.access_token || !t.refresh_token) throw new Error(t.error_description || `Dropbox said ${res.status}`)
    const state: DropboxState = {
      appKey: pending.appKey,
      refreshToken: t.refresh_token,
      accessToken: t.access_token,
      expiresAt: Date.now() + (t.expires_in ?? 14400) * 1000 - 60_000,
      name: '',
      email: '',
    }
    await setKv(DROPBOX_KEY, state)
    try {
      const me = await rpc<{ name?: { display_name?: string }; email?: string }>('users/get_current_account', null)
      await setKv(DROPBOX_KEY, { ...state, name: me.name?.display_name ?? '', email: me.email ?? '' })
    } catch {
      // the name is only for Settings
    }
    await setKv(DROPBOX_TS_KEY, Date.now())
    changed()
    return { returnTo }
  } catch (e) {
    return { returnTo, error: e instanceof Error ? e.message : String(e) }
  }
}

/** Signs out on every device (through sync). The files stay in Dropbox. */
export async function signOutDropbox(): Promise<void> {
  const s = await getDropbox()
  await setKv(DROPBOX_KEY, null)
  await setKv(DROPBOX_TS_KEY, Date.now())
  changed()
  if (s?.accessToken && s.expiresAt > Date.now()) {
    // Revoking would sign out the other devices' copies too, which is what signing out means here
    fetch('https://api.dropboxapi.com/2/auth/token/revoke', { method: 'POST', headers: { Authorization: `Bearer ${s.accessToken}` } }).catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------------------------
// Calls

export class DropboxError extends Error {
  constructor(
    public status: number,
    public summary: string,
  ) {
    super(status === 401 ? 'Dropbox sign-in expired: sign in again in Settings' : `Dropbox: ${summary || status}`)
  }
}

let refreshing: Promise<string> | null = null

async function accessToken(force = false): Promise<string> {
  const s = await getDropbox()
  if (!s) throw new DropboxError(401, 'not signed in')
  if (!force && s.accessToken && s.expiresAt > Date.now()) return s.accessToken
  refreshing ??= (async () => {
    const res = await fetch('https://api.dropboxapi.com/oauth2/token', {
      method: 'POST',
      body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: s.refreshToken, client_id: s.appKey }),
    })
    const t = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error?: string; error_description?: string }
    if (!res.ok || !t.access_token) throw new DropboxError(res.status === 400 ? 401 : res.status, t.error_description || t.error || '')
    const fresh = await getDropbox()
    if (fresh?.refreshToken === s.refreshToken) {
      await setKv(DROPBOX_KEY, { ...fresh, accessToken: t.access_token, expiresAt: Date.now() + (t.expires_in ?? 14400) * 1000 - 60_000 })
    }
    return t.access_token
  })().finally(() => {
    refreshing = null
  })
  return refreshing
}

/** JSON for the Dropbox-API-Arg header: HTTP headers must be ASCII. */
export function headerArg(arg: unknown): string {
  return JSON.stringify(arg).replace(/[\u007f-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`)
}

async function call(url: string, init: (token: string) => RequestInit, signal?: AbortSignal): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const token = await accessToken(attempt > 0)
    let res: Response
    try {
      res = await fetch(url, { ...init(token), signal })
    } catch (e) {
      // The browser gives only "Failed to fetch": say which Dropbox request it was
      if (signal?.aborted) throw e
      throw new DropboxError(0, `could not reach ${url.replace(/^https:\/\/[^/]+\/2\//, '')} (${e instanceof Error ? e.message : String(e)})`)
    }
    if (res.status === 401 && attempt === 0) continue
    if (res.status === 429 && attempt < 3) {
      await new Promise((r) => setTimeout(r, (Number(res.headers.get('retry-after')) || 2) * 1000))
      continue
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      let summary = text
      try {
        summary = (JSON.parse(text) as { error_summary?: string }).error_summary ?? text
      } catch {
        // plain text error
      }
      throw new DropboxError(res.status, summary.slice(0, 200))
    }
    return res
  }
}

async function rpc<T>(endpoint: string, arg: unknown, signal?: AbortSignal): Promise<T> {
  const res = await call(
    `https://api.dropboxapi.com/2/${endpoint}`,
    (token) => ({
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, ...(arg === null ? {} : { 'Content-Type': 'application/json' }) },
      body: arg === null ? undefined : JSON.stringify(arg),
    }),
    signal,
  )
  return (await res.json()) as T
}

export interface DropboxFile {
  /** Dropbox's own id ("id:…"): stays the same when the file is moved or renamed. */
  id: string
  name: string
  path: string
  size: number
  /** Dropbox content hash (see contentHash): the same file has the same hash on every device. */
  hash: string
  modified: string
}

interface RawEntry {
  '.tag': string
  id: string
  name: string
  path_lower: string
  path_display: string
  size: number
  content_hash: string
  server_modified: string
}

const toFile = (e: RawEntry): DropboxFile => ({ id: e.id, name: e.name, path: e.path_display, size: e.size, hash: e.content_hash, modified: e.server_modified })

/** Files in one folder of the app folder (not subfolders). A missing folder is empty. */
export async function listFolder(path: string, signal?: AbortSignal): Promise<DropboxFile[]> {
  const out: DropboxFile[] = []
  let res: { entries: RawEntry[]; cursor: string; has_more: boolean }
  try {
    res = await rpc('files/list_folder', { path, recursive: false, include_deleted: false }, signal)
  } catch (e) {
    if (e instanceof DropboxError && e.status === 409 && e.summary.includes('not_found')) return []
    throw e
  }
  for (;;) {
    out.push(...res.entries.filter((e) => e['.tag'] === 'file').map(toFile))
    if (!res.has_more) break
    res = await rpc('files/list_folder/continue', { cursor: res.cursor }, signal)
  }
  return out
}

/** One page-through listing of a folder: its files, and with [folders] its subfolders' paths too. */
async function listEntries(path: string, recursive: boolean, signal?: AbortSignal): Promise<{ files: DropboxFile[]; folders: string[] }> {
  const files: DropboxFile[] = []
  const folders: string[] = []
  let res: { entries: RawEntry[]; cursor: string; has_more: boolean }
  try {
    res = await rpc('files/list_folder', { path, recursive, include_deleted: false }, signal)
  } catch (e) {
    if (e instanceof DropboxError && e.status === 409 && e.summary.includes('not_found')) return { files, folders }
    throw e
  }
  for (;;) {
    for (const e of res.entries) {
      if (e['.tag'] === 'file') files.push(toFile(e))
      else if (e['.tag'] === 'folder') folders.push(e.path_display)
    }
    if (!res.has_more) break
    res = await rpc('files/list_folder/continue', { cursor: res.cursor }, signal)
  }
  return { files, folders }
}

/**
 * Every file under a folder, subfolders included. A missing folder is empty. When the one
 * recursive listing fails (some networks drop it), it lists folder by folder instead.
 */
export async function listTree(path: string, signal?: AbortSignal): Promise<DropboxFile[]> {
  try {
    return (await listEntries(path, true, signal)).files
  } catch (e) {
    if (!(e instanceof DropboxError) || e.status !== 0) throw e
    console.warn('Recursive Dropbox listing failed, listing folder by folder', e)
  }
  const out: DropboxFile[] = []
  const todo = [path]
  for (let n = 0; todo.length && n < 500; n++) {
    const { files, folders } = await listEntries(todo.shift()!, false, signal)
    out.push(...files)
    todo.push(...folders)
  }
  return out
}

const links = new Map<string, { link: string; until: number }>()

/**
 * A direct link to a file that an <audio> element can stream (with seeking). Dropbox keeps it
 * working for 4 hours; it is reused for 3, and [fresh] asks for a new one (after a playback error).
 */
export async function temporaryLink(path: string, fresh = false): Promise<string> {
  const known = links.get(path)
  if (!fresh && known && known.until > Date.now()) return known.link
  const { link } = await rpc<{ link: string }>('files/get_temporary_link', { path })
  links.set(path, { link, until: Date.now() + 3 * 60 * 60 * 1000 })
  return link
}

/** Bytes [offset, offset + length) of a file, for reading tags without downloading the audio. */
export async function downloadRange(path: string, offset: number, length: number, signal?: AbortSignal): Promise<Uint8Array> {
  if (length <= 0) return new Uint8Array(0)
  const res = await call(
    'https://content.dropboxapi.com/2/files/download',
    (token) => ({ method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Dropbox-API-Arg': headerArg({ path }), Range: `bytes=${offset}-${offset + length - 1}` } }),
    signal,
  )
  const all = new Uint8Array(await res.arrayBuffer())
  // A server that ignored the range sent the whole file
  return res.status === 206 ? all : all.subarray(offset, offset + length)
}

export type Progress = (done: number, total: number) => void

/** Downloads a file as a stream (for saving straight to disk). */
export async function downloadStream(path: string, signal?: AbortSignal): Promise<{ body: ReadableStream<Uint8Array>; size: number }> {
  const res = await call(
    'https://content.dropboxapi.com/2/files/download',
    (token) => ({ method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Dropbox-API-Arg': headerArg({ path }) } }),
    signal,
  )
  const meta = JSON.parse(res.headers.get('dropbox-api-result') || '{}') as Partial<RawEntry>
  if (!res.body) throw new DropboxError(0, 'empty download')
  return { body: res.body, size: meta.size ?? (Number(res.headers.get('content-length')) || 0) }
}

const CHUNK = 8 * 1024 * 1024
const SINGLE_MAX = 32 * 1024 * 1024

/**
 * Uploads a file into the app folder. An existing file with the same name and contents is kept as
 * is; a different one with the same name gets a numbered name (Dropbox's autorename).
 */
export async function uploadFile(path: string, blob: Blob, onProgress?: Progress, signal?: AbortSignal): Promise<DropboxFile> {
  const commit = { path, mode: 'add', autorename: true, mute: true, strict_conflict: false }
  const content = (token: string, arg: unknown, body: Blob): RequestInit => ({
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/octet-stream', 'Dropbox-API-Arg': headerArg(arg) },
    body,
  })
  if (blob.size <= SINGLE_MAX) {
    const res = await call('https://content.dropboxapi.com/2/files/upload', (t) => content(t, commit, blob), signal)
    onProgress?.(blob.size, blob.size)
    return toFile((await res.json()) as RawEntry)
  }
  const start = await call('https://content.dropboxapi.com/2/files/upload_session/start', (t) => content(t, { close: false }, blob.slice(0, CHUNK)), signal)
  const { session_id } = (await start.json()) as { session_id: string }
  let offset = Math.min(CHUNK, blob.size)
  onProgress?.(offset, blob.size)
  while (blob.size - offset > CHUNK) {
    await call('https://content.dropboxapi.com/2/files/upload_session/append_v2', (t) => content(t, { cursor: { session_id, offset }, close: false }, blob.slice(offset, offset + CHUNK)), signal)
    offset += CHUNK
    onProgress?.(offset, blob.size)
  }
  const res = await call(
    'https://content.dropboxapi.com/2/files/upload_session/finish',
    (t) => content(t, { cursor: { session_id, offset }, commit }, blob.slice(offset)),
    signal,
  )
  onProgress?.(blob.size, blob.size)
  return toFile((await res.json()) as RawEntry)
}

export async function deleteFile(path: string): Promise<void> {
  try {
    await rpc('files/delete_v2', { path })
  } catch (e) {
    if (!(e instanceof DropboxError && e.status === 409 && e.summary.includes('not_found'))) throw e
  }
}

/**
 * Dropbox's content hash: SHA-256 of every 4 MB block, then SHA-256 of those hashes together.
 * Books are named by it, so a book has the same id on every device whether it came from Dropbox
 * or a local file.
 */
export async function contentHash(blob: Blob): Promise<string> {
  const BLOCK = 4 * 1024 * 1024
  const parts: Uint8Array[] = []
  for (let at = 0; at < blob.size; at += BLOCK) {
    parts.push(new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.slice(at, at + BLOCK).arrayBuffer())))
  }
  const all = new Uint8Array(parts.length * 32)
  parts.forEach((p, i) => all.set(p, i * 32))
  const out = new Uint8Array(await crypto.subtle.digest('SHA-256', all))
  return Array.from(out, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** The app key to sign in with: Settings' own, else the one built in. */
export async function dropboxAppKey(): Promise<string> {
  return (await getKv<string>('dropboxAppKey', '')) || DEFAULT_DROPBOX_APP_KEY
}

export async function setDropboxAppKey(key: string): Promise<void> {
  await setKv('dropboxAppKey', key.trim())
}

