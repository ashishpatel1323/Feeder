/**
 * The "AI agents (MCP)" switch in the Mac app's Settings. When it's on, the app runs Feeder's MCP
 * server (web/mcp, shipped inside the app) so AI agents such as Claude can search and read the
 * library: articles, playlists, highlights, transcripts, ebooks and ZIM files.
 *
 *  - The sync code and key are copied into the macOS Keychain (service "feeder-mcp"), because the
 *    MCP server is one more sync device and decrypts the library on this Mac.
 *  - An HTTP endpoint runs on 127.0.0.1:47816 while the app is open (Claude Code, other agents).
 *  - Claude Desktop gets a stdio entry that runs the same server with this app's own binary
 *    (ELECTRON_RUN_AS_NODE), so it works even when Feeder isn't open.
 * Turning it off stops the server, removes the Claude Desktop entry and the Keychain item. The
 * index (~/Library/Application Support/feeder-mcp) is kept so turning it on again is quick.
 */
import { execFile, spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { promisify } from 'node:util'

const run = promisify(execFile)

export const MCP_PORT = Number(process.env.FEEDER_MCP_PORT) || 47816
// FEEDER_MCP_DIR / FEEDER_CLAUDE_CONFIG: other locations, for tests
const DATA_DIR = process.env.FEEDER_MCP_DIR || join(homedir(), 'Library', 'Application Support', 'feeder-mcp')
const STATE_FILE = join(DATA_DIR, 'mac-app.json')
const CLAUDE_CONFIG = process.env.FEEDER_CLAUDE_CONFIG || join(homedir(), 'Library', 'Application Support', 'Claude', 'claude_desktop_config.json')
const KEYCHAIN = ['-s', 'feeder-mcp', '-a', 'sync']
const SERVER_NAME = 'feeder'

let mcpDir = ''
let nodeBin = process.execPath
let child: ChildProcess | null = null
let lastError: string | null = null

/** Where the MCP server's files are (app Resources/mcp when packaged, web/mcp in development). */
export function configureMcp(dir: string, bin = process.execPath) {
  mcpDir = dir
  nodeBin = bin
}

interface State {
  enabled: boolean
}

async function readState(): Promise<State> {
  try {
    return JSON.parse(await readFile(STATE_FILE, 'utf8')) as State
  } catch {
    return { enabled: false }
  }
}

async function writeState(s: State) {
  await mkdir(DATA_DIR, { recursive: true })
  await writeFile(STATE_FILE, JSON.stringify(s))
}

const entry = (script: string) => join(mcpDir, 'dist', script)
const installed = () => !!mcpDir && existsSync(entry('http.js'))

function childEnv(): NodeJS.ProcessEnv {
  return { ...process.env, ELECTRON_RUN_AS_NODE: '1', HOST: '127.0.0.1', PORT: String(MCP_PORT), FEEDER_MCP_DIR: DATA_DIR }
}

function startChild() {
  if (child || !installed()) return
  lastError = null
  const c = spawn(nodeBin, [entry('http.js')], { env: childEnv(), stdio: ['ignore', 'ignore', 'pipe'] })
  let tail = ''
  c.stderr?.on('data', (d: Buffer) => {
    tail = (tail + d.toString()).slice(-2000)
  })
  c.on('exit', (code) => {
    if (child === c) child = null
    if (code && code !== 0) lastError = tail.trim().split('\n').slice(-3).join('\n') || `MCP server stopped (exit ${code})`
  })
  child = c
}

export function stopMcp() {
  child?.kill()
  child = null
}

async function token(): Promise<string | null> {
  try {
    return (await readFile(join(DATA_DIR, 'http-token'), 'utf8')).trim()
  } catch {
    return null
  }
}

async function saveSecret(syncCode: string, secretKey: string) {
  await run('security', ['add-generic-password', '-U', ...KEYCHAIN, '-w', JSON.stringify({ syncCode, secretKey })])
}

async function deleteSecret() {
  await run('security', ['delete-generic-password', ...KEYCHAIN]).catch(() => undefined)
}

type ClaudeConfig = { mcpServers?: Record<string, { command: string; args?: string[]; env?: Record<string, string> }> } & Record<string, unknown>

async function readClaude(): Promise<ClaudeConfig | null> {
  try {
    return JSON.parse(await readFile(CLAUDE_CONFIG, 'utf8')) as ClaudeConfig
  } catch {
    return existsSync(dirname(CLAUDE_CONFIG)) ? {} : null
  }
}

/** Adds (or updates) the "feeder" entry in Claude Desktop's config. Other entries are left alone. */
async function addToClaudeDesktop(): Promise<boolean> {
  const cfg = await readClaude()
  if (!cfg) return false
  if (existsSync(CLAUDE_CONFIG)) await copyFile(CLAUDE_CONFIG, `${CLAUDE_CONFIG}.bak-feeder`).catch(() => undefined)
  cfg.mcpServers = { ...(cfg.mcpServers ?? {}), [SERVER_NAME]: { command: nodeBin, args: [entry('index.js')], env: { ELECTRON_RUN_AS_NODE: '1' } } }
  await writeFile(CLAUDE_CONFIG, JSON.stringify(cfg, null, 2))
  return true
}

/** Removes the "feeder" entry, only when it's the one this app wrote (runs this app's binary). */
async function removeFromClaudeDesktop() {
  const cfg = await readClaude()
  const e = cfg?.mcpServers?.[SERVER_NAME]
  if (!cfg || !e || e.env?.ELECTRON_RUN_AS_NODE !== '1') return
  delete cfg.mcpServers![SERVER_NAME]
  await writeFile(CLAUDE_CONFIG, JSON.stringify(cfg, null, 2))
}

async function health(): Promise<{ items: number; last_sync: string } | null> {
  try {
    const r = await fetch(`http://127.0.0.1:${MCP_PORT}/health`, { signal: AbortSignal.timeout(1500) })
    return r.ok ? ((await r.json()) as { items: number; last_sync: string }) : null
  } catch {
    return null
  }
}

export async function mcpStatus() {
  const state = await readState()
  const cfg = await readClaude()
  const h = state.enabled ? await health() : null
  const t = state.enabled ? await token() : null
  return {
    available: installed(),
    enabled: state.enabled,
    running: !!h,
    starting: state.enabled && !h && !!child,
    error: lastError,
    items: h?.items ?? null,
    lastSync: h?.last_sync ?? null,
    url: t ? `http://127.0.0.1:${MCP_PORT}/mcp/${t}` : null,
    claudeDesktop: cfg === null ? 'not-installed' : cfg.mcpServers?.[SERVER_NAME] ? 'added' : 'not-added',
    claudeCodeCommand: t ? `claude mcp add --transport http feeder http://127.0.0.1:${MCP_PORT}/mcp/${t}` : null,
  }
}

export async function enableMcp(syncCode: string, secretKey: string) {
  if (!installed()) throw new Error('The MCP server is not part of this build of Feeder.')
  if (!/^[0-9a-f]{64}$/i.test(syncCode) || !secretKey.includes(':')) throw new Error('Join sync first: the MCP server reads your library through sync.')
  await saveSecret(syncCode, secretKey)
  await writeState({ enabled: true })
  startChild()
  await addToClaudeDesktop().catch(() => false)
  return mcpStatus()
}

export async function disableMcp() {
  await writeState({ enabled: false })
  stopMcp()
  await removeFromClaudeDesktop().catch(() => undefined)
  await deleteSecret()
  return mcpStatus()
}

/** On app start: run the server again if the switch was left on. */
export async function resumeMcp() {
  if (!(await readState()).enabled) return
  startChild()
  // Keep Claude Desktop pointing at this copy of the app (it may have moved or been updated)
  if ((await readClaude())?.mcpServers?.[SERVER_NAME]?.env?.ELECTRON_RUN_AS_NODE === '1') await addToClaudeDesktop().catch(() => false)
}
