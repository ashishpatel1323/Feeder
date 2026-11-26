import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

export const DATA_DIR = process.env.FEEDER_MCP_DIR || join(homedir(), 'Library', 'Application Support', 'feeder-mcp')
export const DB_PATH = join(DATA_DIR, 'index.db')
export const BOOKS_DIR = join(DATA_DIR, 'books')
const SECRET_FILE = join(DATA_DIR, 'sync-secret.json')
export const SYNC_SERVER = (process.env.FEEDER_SYNC_SERVER || 'https://feeder-web-snowy.vercel.app').replace(/\/+$/, '')

export function ensureDirs() {
  mkdirSync(DATA_DIR, { recursive: true })
  mkdirSync(BOOKS_DIR, { recursive: true })
}

const SERVICE = 'feeder-mcp'
const ACCOUNT = 'sync'

export interface SyncSecret {
  syncCode: string
  secretKey: string
}

/**
 * The sync code and key live in the macOS Keychain. FEEDER_SYNC_CODE + FEEDER_SYNC_KEY override it
 * (tests and non-Mac machines).
 */
export function loadSecret(): SyncSecret | null {
  if (process.env.FEEDER_SYNC_CODE && process.env.FEEDER_SYNC_KEY) {
    return { syncCode: process.env.FEEDER_SYNC_CODE, secretKey: process.env.FEEDER_SYNC_KEY }
  }
  // Linux servers have no Keychain: the secret lives in a file only this user can read
  if (process.platform !== 'darwin') {
    try {
      const s = JSON.parse(readFileSync(SECRET_FILE, 'utf8')) as SyncSecret
      return s.syncCode && s.secretKey ? s : null
    } catch {
      return null
    }
  }
  try {
    const out = execFileSync('security', ['find-generic-password', '-s', SERVICE, '-a', ACCOUNT, '-w'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
    const parsed = JSON.parse(out) as SyncSecret
    return parsed.syncCode && parsed.secretKey ? parsed : null
  } catch {
    return null
  }
}

export function saveSecret(s: SyncSecret) {
  if (process.platform !== 'darwin') {
    writeFileSync(SECRET_FILE, JSON.stringify(s), { mode: 0o600 })
    return
  }
  execFileSync('security', ['add-generic-password', '-U', '-s', SERVICE, '-a', ACCOUNT, '-w', JSON.stringify(s)], {
    stdio: 'ignore',
  })
}

export function deleteSecret() {
  if (process.platform !== 'darwin') {
    rmSync(SECRET_FILE, { force: true })
    return
  }
  try {
    execFileSync('security', ['delete-generic-password', '-s', SERVICE, '-a', ACCOUNT], { stdio: 'ignore' })
  } catch {
    // not there
  }
}
