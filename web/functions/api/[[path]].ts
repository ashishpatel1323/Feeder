import { handle, type Env } from '../../server/handler'

// Cloudflare Pages Function: every /api/* request goes to the shared handler.
export const onRequest = (context: { request: Request; env: Env }) => handle(context.request, context.env)
