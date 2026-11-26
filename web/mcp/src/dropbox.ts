/**
 * Dropbox (the Feeder app folder: /Ebooks and /ZIM), using the sign-in that Feeder already
 * syncs (record kind "dropbox": app key + refresh token). No separate sign-in needed.
 */
import { createWriteStream } from 'node:fs'
import { rename } from 'node:fs/promises'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type { ReadableStream as WebStream } from 'node:stream/web'
import { getKv, type Db } from './db.js'

export const EBOOKS_FOLDER = '/Ebooks'
export const ZIM_FOLDER = '/ZIM'

interface Link {
  appKey: string
  refreshToken: string
}

export interface DropboxFile {
  name: string
  path: string
  size: number
  hash: string
  modified: string
}

export class Dropbox {
  private token: string | null = null
  private expiresAt = 0
  private refreshing: Promise<string> | null = null

  constructor(private link: Link) {}

  static fromDb(db: Db): Dropbox | null {
    const link = getKv<Link | null>(db, 'dropbox', null)
    return link?.refreshToken && link.appKey ? new Dropbox(link) : null
  }

  private async accessToken(force = false): Promise<string> {
    if (!force && this.token && this.expiresAt > Date.now()) return this.token
    this.refreshing ??= (async () => {
      const res = await fetch('https://api.dropboxapi.com/oauth2/token', {
        method: 'POST',
        body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: this.link.refreshToken, client_id: this.link.appKey }),
      })
      const t = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error_description?: string; error?: string }
      if (!res.ok || !t.access_token) throw new Error(`Dropbox sign-in failed: ${t.error_description || t.error || res.status}. Sign in to Dropbox again in Feeder's Settings.`)
      this.token = t.access_token
      this.expiresAt = Date.now() + (t.expires_in ?? 14400) * 1000 - 60_000
      return t.access_token
    })().finally(() => {
      this.refreshing = null
    })
    return this.refreshing
  }

  private async call(url: string, init: (token: string) => RequestInit): Promise<Response> {
    for (let attempt = 0; ; attempt++) {
      const token = await this.accessToken(attempt > 0)
      const res = await fetch(url, init(token))
      if (res.status === 401 && attempt === 0) continue
      if (res.status === 429 && attempt < 3) {
        await new Promise((r) => setTimeout(r, (Number(res.headers.get('retry-after')) || 2) * 1000))
        continue
      }
      if (!res.ok && res.status !== 206) {
        const text = await res.text().catch(() => '')
        throw new Error(`Dropbox ${res.status}: ${text.slice(0, 200)}`)
      }
      return res
    }
  }

  private async rpc<T>(endpoint: string, arg: unknown): Promise<T> {
    const res = await this.call(`https://api.dropboxapi.com/2/${endpoint}`, (token) => ({
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(arg),
    }))
    return (await res.json()) as T
  }

  async listFolder(path: string): Promise<DropboxFile[]> {
    type Raw = { '.tag': string; name: string; path_display: string; size: number; content_hash: string; server_modified: string }
    const out: DropboxFile[] = []
    let res: { entries: Raw[]; cursor: string; has_more: boolean }
    try {
      res = await this.rpc('files/list_folder', { path, recursive: false, include_deleted: false })
    } catch (e) {
      if (String(e).includes('not_found')) return []
      throw e
    }
    for (;;) {
      for (const e of res.entries)
        if (e['.tag'] === 'file') out.push({ name: e.name, path: e.path_display, size: e.size, hash: e.content_hash, modified: e.server_modified })
      if (!res.has_more) break
      res = await this.rpc('files/list_folder/continue', { cursor: res.cursor })
    }
    return out
  }

  /** Downloads a whole file to disk (written to a temp name first, so a half download is never used). */
  async downloadTo(path: string, dest: string): Promise<void> {
    const res = await this.call('https://content.dropboxapi.com/2/files/download', (token) => ({
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Dropbox-API-Arg': headerArg({ path }) },
    }))
    if (!res.body) throw new Error('Dropbox: empty download')
    const tmp = `${dest}.part`
    await pipeline(Readable.fromWeb(res.body as unknown as WebStream<Uint8Array>), createWriteStream(tmp))
    await rename(tmp, dest)
  }

  /** Reads part of a file (HTTP range), for ZIM files too big to download. */
  async readRange(path: string, offset: number, length: number): Promise<Uint8Array> {
    const res = await this.call('https://content.dropboxapi.com/2/files/download', (token) => ({
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Dropbox-API-Arg': headerArg({ path }), Range: `bytes=${offset}-${offset + length - 1}` },
    }))
    return new Uint8Array(await res.arrayBuffer())
  }
}

/** JSON for the Dropbox-API-Arg header: HTTP headers must be ASCII. */
export function headerArg(arg: unknown): string {
  return JSON.stringify(arg).replace(/[\u007f-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`)
}
