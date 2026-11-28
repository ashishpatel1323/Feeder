/**
 * The Mac app's built-in server: the built web app (dist/), the feed fetcher and sync
 * proxy (server/handler.ts) and yt-dlp video saving (server/ytdl.ts), on 127.0.0.1 only.
 * Bundled into mac/dist/server.cjs by vite.server.config.ts.
 */
import { execFile, spawn } from 'node:child_process'
import { createReadStream } from 'node:fs'
import { access, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { dirname, extname, join, normalize, sep } from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { handle } from '../server/handler'
import { ytdlMiddleware } from '../server/ytdl'
import { configureMcp, disableMcp, enableMcp, mcpStatus, resumeMcp, stopMcp } from './mcp'

export { stopMcp }

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain',
}

async function api(req: IncomingMessage, res: ServerResponse) {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(chunk as Buffer)
  const headers = new Headers()
  for (const [k, v] of Object.entries(req.headers)) if (typeof v === 'string') headers.set(k, v)
  const request = new Request(`http://${req.headers.host}${req.url}`, {
    method: req.method,
    headers,
    body: req.method === 'GET' || req.method === 'HEAD' || !chunks.length ? undefined : Buffer.concat(chunks),
  })
  const response = await handle(request, {
    SYNC_UPSTREAM: process.env.SYNC_UPSTREAM,
    SYNC_SERVER: process.env.SYNC_SERVER,
  })
  res.statusCode = response.status
  response.headers.forEach((v, k) => res.setHeader(k, v))
  if (!response.body) return void res.end()
  await pipeline(Readable.fromWeb(response.body as never), res).catch(() => res.destroy())
}

async function file(root: string, req: IncomingMessage, res: ServerResponse) {
  const path = decodeURIComponent(new URL(req.url ?? '/', 'http://x').pathname)
  let full = normalize(join(root, path))
  if (full !== root && !full.startsWith(root + sep)) {
    res.statusCode = 403
    return void res.end()
  }
  let info = await stat(full).catch(() => null)
  if (info?.isDirectory()) {
    full = join(full, 'index.html')
    info = await stat(full).catch(() => null)
  }
  // Unknown paths get the app (it has client-side routes like #/feeds anyway)
  if (!info) {
    full = join(root, 'index.html')
    info = await stat(full).catch(() => null)
  }
  if (!info) {
    res.statusCode = 404
    return void res.end('Not found')
  }
  res.setHeader('content-type', TYPES[extname(full)] ?? 'application/octet-stream')
  res.setHeader('content-length', String(info.size))
  // Hashed build files never change; everything else is checked each time
  res.setHeader('cache-control', path.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache')
  createReadStream(full).pipe(res)
}

/**
 * /api/mcp/status|enable|disable: the AI agents (MCP) switch in Settings. Only this app's own page
 * may call it (it hands over the sync key and edits Claude Desktop's config), so other origins and
 * simple cross-site posts are refused.
 */
async function mcpApi(req: IncomingMessage, res: ServerResponse, origin: string) {
  const send = (status: number, body: unknown) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    res.end(JSON.stringify(body))
  }
  const sameOrigin = req.headers.origin === origin || (!req.headers.origin && req.headers['sec-fetch-site'] === 'same-origin')
  if (!sameOrigin || req.headers['x-feeder-app'] !== '1') return send(403, { error: 'Forbidden' })
  const path = new URL(req.url ?? '/', 'http://x').pathname
  try {
    if (path === '/api/mcp/status' && req.method === 'GET') return send(200, await mcpStatus())
    if (req.method !== 'POST' || !String(req.headers['content-type']).startsWith('application/json')) return send(405, { error: 'Use POST with JSON' })
    const chunks: Buffer[] = []
    for await (const c of req) chunks.push(c as Buffer)
    const body = chunks.length ? (JSON.parse(Buffer.concat(chunks).toString('utf8')) as { syncCode?: string; secretKey?: string }) : {}
    if (path === '/api/mcp/enable') return send(200, await enableMcp(String(body.syncCode ?? ''), String(body.secretKey ?? '')))
    if (path === '/api/mcp/disable') return send(200, await disableMcp())
    return send(404, { error: 'Not found' })
  } catch (e) {
    return send(400, { error: (e as Error).message ?? String(e) })
  }
}

/**
 * /api/bookaudio/status|aac: chapter audio this Mac makes for the phone (lib/macBookAudio.ts).
 * aac turns a WAV into AAC (.m4a) with macOS's own afconvert, which every phone plays. Only this
 * app's own page may call it.
 */
async function bookAudioApi(req: IncomingMessage, res: ServerResponse, origin: string) {
  const sameOrigin = req.headers.origin === origin || (!req.headers.origin && req.headers['sec-fetch-site'] === 'same-origin')
  if (!sameOrigin || req.headers['x-feeder-app'] !== '1') {
    res.writeHead(403, { 'content-type': 'application/json' })
    return void res.end('{"error":"Forbidden"}')
  }
  const path = new URL(req.url ?? '/', 'http://x').pathname
  if (path === '/api/bookaudio/status') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    return void res.end(JSON.stringify({ available: process.platform === 'darwin' }))
  }
  if (path !== '/api/bookaudio/aac' || req.method !== 'POST') {
    res.writeHead(404, { 'content-type': 'application/json' })
    return void res.end('{"error":"Not found"}')
  }
  const chunks: Buffer[] = []
  for await (const c of req) chunks.push(c as Buffer)
  const dir = await mkdtemp(join(tmpdir(), 'feeder-bookaudio-'))
  try {
    const wav = join(dir, 'in.wav')
    const m4a = join(dir, 'out.m4a')
    await writeFile(wav, Buffer.concat(chunks))
    // 48 kbps mono AAC: about 0.37 MB a minute, the same as the phone's own (SpeechEncoding.kt)
    await new Promise<void>((resolve, reject) =>
      execFile('/usr/bin/afconvert', ['-f', 'm4af', '-d', 'aac', '-b', '48000', wav, m4a], (e) => (e ? reject(e) : resolve())),
    )
    const out = await readFile(m4a)
    res.writeHead(200, { 'content-type': 'audio/mp4', 'content-length': String(out.length) })
    res.end(out)
  } catch (e) {
    res.writeHead(500, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: (e as Error).message ?? String(e) }))
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

/**
 * Claude Code on this Mac, for playlist briefs (lib/macBrief.ts). The app is started from Finder
 * with a bare PATH, so the usual install places are checked, then the login shell is asked.
 */
let claudeBin: Promise<string | null> | null = null
function findClaude(): Promise<string | null> {
  claudeBin ??= (async () => {
    const home = homedir()
    for (const p of [join(home, '.local/bin/claude'), join(home, '.claude/local/claude'), '/opt/homebrew/bin/claude', '/usr/local/bin/claude', join(home, '.npm-global/bin/claude')]) {
      if (await access(p).then(() => true, () => false)) return p
    }
    const shell = process.env.SHELL || '/bin/zsh'
    const found = await new Promise<string>((resolve) =>
      execFile(shell, ['-lc', 'command -v claude'], { timeout: 10_000 }, (e, out) => resolve(e ? '' : String(out).trim().split('\n').pop() ?? '')),
    )
    return found.startsWith('/') ? found : null
  })()
  const p = claudeBin
  // Not found: look again next time (it may get installed while the app is open)
  void p.then((bin) => {
    if (!bin && claudeBin === p) claudeBin = null
  })
  return p
}

/** Longest a brief request may take */
const CLAUDE_TIMEOUT_MS = 10 * 60_000

/** Runs `claude -p` with [prompt] on stdin, in an empty folder, and gives back what it printed. */
async function runClaude(bin: string, prompt: string): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'feeder-brief-'))
  try {
    return await new Promise<string>((resolve, reject) => {
      const env = { ...process.env, PATH: [dirname(bin), '/opt/homebrew/bin', '/usr/local/bin', process.env.PATH ?? '/usr/bin:/bin'].join(':') }
      // Print mode: one answer, no tools needed (the articles are in the prompt)
      const child = spawn(bin, ['-p', '--output-format', 'text'], { cwd: dir, env, stdio: ['pipe', 'pipe', 'pipe'] })
      let out = ''
      let err = ''
      const timer = setTimeout(() => child.kill('SIGTERM'), CLAUDE_TIMEOUT_MS)
      child.stdout.on('data', (d: Buffer) => (out += d.toString('utf8')))
      child.stderr.on('data', (d: Buffer) => (err += d.toString('utf8')))
      child.on('error', (e) => {
        clearTimeout(timer)
        reject(e)
      })
      child.on('close', (code) => {
        clearTimeout(timer)
        if (code === 0 && out.trim()) resolve(out)
        else reject(new Error((err || out).trim().split('\n').slice(-3).join(' ') || `Claude Code stopped (${code ?? 'timed out'})`))
      })
      child.stdin.end(prompt, 'utf8')
    })
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

/**
 * /api/brief/status|claude: playlist briefs written by Claude Code on this Mac (lib/macBrief.ts),
 * with the user's own Claude sign-in, so no API key. Only this app's own page may call it.
 */
async function briefApi(req: IncomingMessage, res: ServerResponse, origin: string) {
  const send = (status: number, body: unknown) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    res.end(JSON.stringify(body))
  }
  const sameOrigin = req.headers.origin === origin || (!req.headers.origin && req.headers['sec-fetch-site'] === 'same-origin')
  if (!sameOrigin || req.headers['x-feeder-app'] !== '1') return send(403, { error: 'Forbidden' })
  const path = new URL(req.url ?? '/', 'http://x').pathname
  const bin = await findClaude()
  if (path === '/api/brief/status') return send(200, { available: !!bin })
  if (path !== '/api/brief/claude' || req.method !== 'POST' || !String(req.headers['content-type']).startsWith('application/json')) return send(404, { error: 'Not found' })
  if (!bin) return send(503, { error: 'Claude Code is not installed on this Mac' })
  const chunks: Buffer[] = []
  for await (const c of req) chunks.push(c as Buffer)
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as { prompt?: unknown }
    if (typeof body.prompt !== 'string' || !body.prompt) return send(400, { error: 'No prompt' })
    return send(200, { text: await runClaude(bin, body.prompt) })
  } catch (e) {
    return send(500, { error: (e as Error).message ?? String(e) })
  }
}

export function startServer(root: string, port: number, opts: { mcpDir?: string; nodeBin?: string } = {}): Promise<number> {
  const origin = `http://127.0.0.1:${port}`
  if (opts.mcpDir) {
    configureMcp(opts.mcpDir, opts.nodeBin)
    void resumeMcp()
  }
  const server = createServer((req, res) => {
    const run = async () => {
      if (req.url?.startsWith('/api/mcp/')) return mcpApi(req, res, origin)
      if (req.url?.startsWith('/api/bookaudio/')) return bookAudioApi(req, res, origin)
      if (req.url?.startsWith('/api/brief/')) return briefApi(req, res, origin)
      if (req.url?.startsWith('/api/ytdl/')) return ytdlMiddleware(req, res, () => undefined)
      if (req.url?.startsWith('/api/')) return api(req, res)
      return file(root, req, res)
    }
    run().catch((e) => {
      if (res.headersSent) return void res.destroy()
      res.statusCode = 500
      res.end(String(e))
    })
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', () => resolve(port))
  })
}
