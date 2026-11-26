#!/usr/bin/env node
/**
 * The same MCP server over Streamable HTTP, for remote clients (claude.ai connectors, the
 * Claude mobile app, agents elsewhere).
 *
 *   POST/GET/DELETE /mcp            with  Authorization: Bearer <token>
 *   POST/GET/DELETE /mcp/<token>    for clients that can only take a URL (claude.ai custom connectors)
 *   GET /health
 *
 * Token: FEEDER_MCP_TOKEN, else one generated once and kept in <data dir>/http-token.
 * Listens on HOST (default 127.0.0.1) : PORT (default 8787). Stateless: one MCP server per request.
 */
import './quiet.js'
import './dom.js'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { join } from 'node:path'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { DATA_DIR, DB_PATH, ensureDirs } from './config.js'
import { openDb } from './db.js'
import { startBackground } from './indexer.js'
import { buildServer, statusOf } from './server.js'

ensureDirs()
const db = openDb(DB_PATH)

function loadToken(): string {
  if (process.env.FEEDER_MCP_TOKEN) return process.env.FEEDER_MCP_TOKEN
  const file = join(DATA_DIR, 'http-token')
  if (existsSync(file)) return readFileSync(file, 'utf8').trim()
  const t = randomBytes(32).toString('base64url')
  writeFileSync(file, t + '\n', { mode: 0o600 })
  return t
}

const TOKEN = loadToken()
const tokenBuf = Buffer.from(TOKEN)
const same = (s: string) => {
  const b = Buffer.from(s)
  return b.length === tokenBuf.length && timingSafeEqual(b, tokenBuf)
}

function authorized(req: IncomingMessage, pathToken: string | null): boolean {
  if (pathToken) return same(pathToken)
  const h = req.headers.authorization ?? ''
  const m = h.match(/^Bearer\s+(.+)$/i)
  return !!m && same(m[1].trim())
}

async function readBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const c of req) {
    size += (c as Buffer).length
    if (size > 4 * 1024 * 1024) throw new Error('Body too large')
    chunks.push(c as Buffer)
  }
  if (!chunks.length) return undefined
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

const send = (res: ServerResponse, status: number, body: unknown) => {
  res.writeHead(status, { 'content-type': 'application/json' })
  res.end(JSON.stringify(body))
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://x')
  // CORS for browser-based clients (MCP Inspector)
  res.setHeader('access-control-allow-origin', '*')
  res.setHeader('access-control-allow-headers', 'authorization, content-type, mcp-session-id, mcp-protocol-version, last-event-id')
  res.setHeader('access-control-expose-headers', 'mcp-session-id')
  res.setHeader('access-control-allow-methods', 'GET, POST, DELETE, OPTIONS')
  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    res.end()
    return
  }
  if (url.pathname === '/health') {
    const s = statusOf(db)
    send(res, 200, { ok: true, items: s.items, last_sync: s.sync.last })
    return
  }
  const m = url.pathname.match(/^\/mcp(?:\/([A-Za-z0-9_-]+))?\/?$/)
  if (!m) {
    send(res, 404, { error: 'Not found' })
    return
  }
  if (!authorized(req, m[1] ?? null)) {
    res.setHeader('www-authenticate', 'Bearer')
    send(res, 401, { error: 'Unauthorized' })
    return
  }
  try {
    const body = req.method === 'POST' ? await readBody(req) : undefined
    const mcp = buildServer(db)
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true })
    res.on('close', () => {
      void transport.close()
      void mcp.close()
    })
    await mcp.connect(transport)
    await transport.handleRequest(req, res, body)
  } catch (e) {
    if (!res.headersSent) send(res, 500, { jsonrpc: '2.0', error: { code: -32603, message: String((e as Error).message ?? e) }, id: null })
  }
})

const host = process.env.HOST || '127.0.0.1'
const port = Number(process.env.PORT || 8787)
server.listen(port, host, () => {
  console.error(`[feeder-mcp] HTTP on http://${host}:${port}/mcp (token in ${process.env.FEEDER_MCP_TOKEN ? 'FEEDER_MCP_TOKEN' : join(DATA_DIR, 'http-token')})`)
})
if (process.env.FEEDER_MCP_NO_BACKGROUND !== '1') startBackground(db)
