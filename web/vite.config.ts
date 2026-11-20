import type { Connect, Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { handle } from './server/handler'
import { ytdlMiddleware } from './server/ytdl'

// Serves /api/* during `npm run dev` and `npm run preview` with the same code the Pages Function runs.
function devApi(): Plugin {
  return {
    name: 'feeder-dev-api',
    configureServer(server) {
      // yt-dlp video saving exists only on this local server, not on Vercel
      server.middlewares.use(ytdlMiddleware)
      server.middlewares.use(apiMiddleware)
    },
    configurePreviewServer(server) {
      server.middlewares.use(ytdlMiddleware)
      server.middlewares.use(apiMiddleware)
    },
  }
}

const apiMiddleware: Connect.NextHandleFunction = async (req, res, next) => {
  if (!req.url?.startsWith('/api/')) return next()
  try {
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(chunk as Buffer)
    const body = chunks.length ? Buffer.concat(chunks) : undefined
    const headers = new Headers()
    for (const [k, v] of Object.entries(req.headers)) if (typeof v === 'string') headers.set(k, v)
    const request = new Request(`http://${req.headers.host}${req.url}`, {
      method: req.method,
      headers,
      body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
    })
    const response = await handle(request, {
      ACCESS_TOKEN: process.env.ACCESS_TOKEN,
      SYNC_SERVER: process.env.SYNC_SERVER,
      ALLOW_PRIVATE_HOSTS: process.env.ALLOW_PRIVATE_HOSTS,
      DATABASE_URL: process.env.DATABASE_URL,
      SYNC_MAX_ACCOUNTS: process.env.SYNC_MAX_ACCOUNTS,
      // Without a database, development syncs through the deployed app (SYNC_UPSTREAM=none
      // keeps sync data in memory instead, until the server restarts)
      SYNC_UPSTREAM: process.env.SYNC_UPSTREAM === 'none' ? undefined : (process.env.SYNC_UPSTREAM ?? 'https://feeder-web-snowy.vercel.app'),
      SYNC_MEMORY: process.env.DATABASE_URL || process.env.SYNC_UPSTREAM !== 'none' ? undefined : '1',
    })
    res.statusCode = response.status
    response.headers.forEach((v, k) => res.setHeader(k, v))
    if (!response.body) return void res.end()
    // pipeline closes the response if the upstream transfer fails midway
    await pipeline(Readable.fromWeb(response.body as never), res).catch(() => res.destroy())
  } catch (e) {
    if (res.headersSent) return void res.destroy()
    res.statusCode = 500
    res.end(String(e))
  }
}

export default defineConfig({
  plugins: [react(), devApi()],
  // The natural-voice worker uses import.meta.url to find its WebAssembly files
  worker: { format: 'es' },
  test: {
    environment: 'jsdom',
  },
})
