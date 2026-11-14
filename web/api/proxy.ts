// Vercel entry point. vercel.json rewrites every /api/* request here.
import { handle } from '../server/handler'

export const config = { runtime: 'edge' }

export default function handler(request: Request): Promise<Response> {
  return handle(request, {
    ACCESS_TOKEN: process.env.ACCESS_TOKEN,
    SYNC_SERVER: process.env.SYNC_SERVER,
  })
}
