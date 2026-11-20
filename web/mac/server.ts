/**
 * The Mac app's built-in server: the built web app (dist/), the feed fetcher and sync
 * proxy (server/handler.ts) and yt-dlp video saving (server/ytdl.ts), on 127.0.0.1 only.
 * Bundled into mac/dist/server.cjs by vite.server.config.ts.
 */
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { extname, join, normalize, sep } from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { handle } from '../server/handler'
import { ytdlMiddleware } from '../server/ytdl'

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

export function startServer(root: string, port: number): Promise<number> {
  const server = createServer((req, res) => {
    const run = async () => {
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
