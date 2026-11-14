import { type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { Readable } from 'node:stream'
import { handle } from './server/handler'

// Serves /api/* during `npm run dev` with the same code the Pages Function runs.
function devApi(): Plugin {
  return {
    name: 'feeder-dev-api',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
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
          })
          res.statusCode = response.status
          response.headers.forEach((v, k) => res.setHeader(k, v))
          if (response.body) Readable.fromWeb(response.body as never).pipe(res)
          else res.end()
        } catch (e) {
          res.statusCode = 500
          res.end(String(e))
        }
      })
    },
  }
}

export default defineConfig({
  plugins: [react(), devApi()],
  test: {
    environment: 'jsdom',
  },
})
