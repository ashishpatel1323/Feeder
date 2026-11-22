/**
 * Saving YouTube videos for offline watching with yt-dlp. Runs only inside the local
 * Vite server (`npm run dev` / `npm run preview` on this Mac), never on Vercel: yt-dlp
 * and ffmpeg are programs on this computer.
 *
 *   GET  /api/ytdl/health          { ok, version }
 *   POST /api/ytdl/start?id=VIDEO  starts (or reuses) a download job
 *   GET  /api/ytdl/status?id=VIDEO { state, percent, size, error }
 *   GET  /api/ytdl/file?id=VIDEO   the finished MP4; the temp copy is removed once sent
 *   POST /api/ytdl/subs?id=VIDEO   subtitles (own, else YouTube's auto captions) as WebVTT text
 *   POST /api/ytdl/iina?id=VIDEO[&lang=L]  a saved copy from the browser: with lang, a WebVTT
 *                                  track to keep beside it; without, the MP4, then opens it in IINA
 */
import { spawn } from 'node:child_process'
import { createReadStream, createWriteStream } from 'node:fs'
import { pipeline } from 'node:stream/promises'
import { access, mkdir, mkdtemp, readdir, readFile, rm, stat } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const ID = /^[A-Za-z0-9_-]{11}$/
/** Up to 720p, H.264 first so every browser can play it. */
const FORMAT = 'bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]/b'
const JOB_TTL_MS = 60 * 60_000

interface Job {
  state: 'running' | 'done' | 'error'
  percent: number
  error?: string
  dir: string
  file?: string
  size?: number
  started: number
}

const jobs = new Map<string, Job>()

async function firstExisting(paths: string[]): Promise<string | null> {
  for (const p of paths) {
    try {
      await access(p)
      return p
    } catch {
      // next
    }
  }
  return null
}

const home = homedir()
let tools: Promise<{ ytdlp: string; ffmpeg: string | null }> | null = null
/** yt-dlp and ffmpeg: from YTDLP_PATH / FFMPEG_PATH, the usual install places, else PATH. */
function findTools() {
  tools ??= (async () => {
    const ytdlp =
      (await firstExisting(
        [process.env.YTDLP_PATH ?? '', `${home}/.local/bin/yt-dlp`, '/opt/homebrew/bin/yt-dlp', `${home}/homebrew/bin/yt-dlp`, '/usr/local/bin/yt-dlp'].filter(Boolean),
      )) ?? 'yt-dlp'
    const ffmpeg = await firstExisting(
      [process.env.FFMPEG_PATH ?? '', '/opt/homebrew/bin/ffmpeg', `${home}/homebrew/bin/ffmpeg`, '/usr/local/bin/ffmpeg'].filter(Boolean),
    )
    return { ytdlp, ffmpeg }
  })()
  return tools
}

function run(bin: string, args: string[], onLine?: (line: string) => void): Promise<{ code: number; err: string; out: string }> {
  return new Promise((resolve) => {
    const child = spawn(bin, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let out = ''
    let err = ''
    let buf = ''
    child.stdout.on('data', (d: Buffer) => {
      const s = d.toString()
      out += s
      buf += s
      const lines = buf.split(/\r?\n|\r/)
      buf = lines.pop() ?? ''
      lines.forEach((l) => onLine?.(l))
    })
    child.stderr.on('data', (d: Buffer) => (err += d.toString()))
    child.on('error', (e) => resolve({ code: -1, err: String(e), out }))
    child.on('close', (code) => resolve({ code: code ?? -1, err, out }))
  })
}

function sweep() {
  const now = Date.now()
  for (const [id, job] of jobs) {
    if (job.state !== 'running' && now - job.started > JOB_TTL_MS) {
      jobs.delete(id)
      void rm(job.dir, { recursive: true, force: true })
    }
  }
}

async function startJob(id: string): Promise<Job> {
  sweep()
  const existing = jobs.get(id)
  if (existing && existing.state !== 'error') return existing
  const dir = await mkdtemp(join(tmpdir(), 'feeder-ytdl-'))
  const job: Job = { state: 'running', percent: 0, dir, started: Date.now() }
  jobs.set(id, job)
  const { ytdlp, ffmpeg } = await findTools()
  const args = [
    '-f', FORMAT,
    '--merge-output-format', 'mp4',
    '--no-playlist',
    '--newline',
    '--no-part',
    '-o', join(dir, '%(id)s.%(ext)s'),
    ...(ffmpeg ? ['--ffmpeg-location', ffmpeg] : []),
    `https://www.youtube.com/watch?v=${id}`,
  ]
  // Video then audio: the first stream counts for most of the bar
  let stream = 0
  let lastPct = 0
  void run(ytdlp, args, (line) => {
    const m = line.match(/^\[download\]\s+([\d.]+)%/)
    if (!m) return
    const pct = Number(m[1])
    if (pct < lastPct - 20) stream++
    lastPct = pct
    job.percent = Math.min(99, stream === 0 ? pct * 0.85 : 85 + pct * 0.15)
  }).then(async ({ code, err }) => {
    if (code !== 0) {
      job.state = 'error'
      job.error = code === -1 ? 'yt-dlp is not installed on this Mac' : err.trim().split('\n').pop() || `yt-dlp exited with ${code}`
      return
    }
    const files = (await readdir(dir)).filter((f) => !f.endsWith('.part') && !f.endsWith('.ytdl'))
    const file = files.find((f) => f.endsWith('.mp4')) ?? files[0]
    if (!file) {
      job.state = 'error'
      job.error = 'yt-dlp finished without a video file'
      return
    }
    job.file = join(dir, file)
    job.size = (await stat(job.file)).size
    job.percent = 100
    job.state = 'done'
  })
  return job
}

/** English (any variant) and Hindi; the video's own subtitles win over auto captions per language. */
const SUB_LANGS = 'en.*,hi'
const MAX_TRACKS = 3

/**
 * Cleans YouTube's auto-caption WebVTT: drops word-timing tags and the 10 ms "hold" cues,
 * and the line each rolling cue repeats from the one before, so lines show once.
 */
export function cleanVtt(vtt: string): string {
  const blocks = vtt.replace(/\r/g, '').split(/\n\n+/)
  const out: string[] = ['WEBVTT']
  let prev: string[] = []
  const secs = (t: string) => {
    const p = t.split(':').map(Number)
    return p.length === 3 ? p[0] * 3600 + p[1] * 60 + p[2] : p[0] * 60 + p[1]
  }
  for (const block of blocks) {
    const lines = block.split('\n')
    const ti = lines.findIndex((l) => l.includes('-->'))
    if (ti < 0) continue
    const m = lines[ti].match(/([\d:.]+)\s+-->\s+([\d:.]+)/)
    if (!m) continue
    if (secs(m[2]) - secs(m[1]) < 0.05) continue
    const text = lines
      .slice(ti + 1)
      .map((l) => l.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').trim())
      .filter(Boolean)
    const fresh = text.filter((l) => !prev.includes(l))
    if (!fresh.length) continue
    prev = text
    out.push(`${m[1]} --> ${m[2]}\n${fresh.join('\n')}`)
  }
  return out.join('\n\n') + '\n'
}

async function subtitles(id: string): Promise<{ lang: string; vtt: string }[]> {
  const dir = await mkdtemp(join(tmpdir(), 'feeder-subs-'))
  try {
    const { ytdlp } = await findTools()
    const r = await run(ytdlp, [
      '--skip-download', '--write-subs', '--write-auto-subs',
      '--sub-langs', SUB_LANGS, '--sub-format', 'vtt',
      '--no-playlist', '-o', join(dir, 'v.%(ext)s'),
      `https://www.youtube.com/watch?v=${id}`,
    ])
    const files = (await readdir(dir)).filter((f) => f.endsWith('.vtt'))
    if (!files.length && r.code !== 0) throw new Error(r.err.trim().split('\n').pop() || 'yt-dlp could not get subtitles')
    const tracks = await Promise.all(
      files.map(async (f) => ({ lang: f.slice(2, -4), vtt: cleanVtt(await readFile(join(dir, f), 'utf8')) })),
    )
    // Plain "en" first, then other English variants, then the rest
    const rank = (l: string) => (l === 'en' ? 0 : l.startsWith('en') ? 1 : 2)
    const seen = new Set<string>()
    return tracks
      .filter((t) => t.vtt.length > 20)
      .sort((a, b) => rank(a.lang) - rank(b.lang))
      // "en-orig" is often the same text as "en"
      .filter((t) => !seen.has(t.vtt) && !!seen.add(t.vtt))
      .slice(0, MAX_TRACKS)
  } finally {
    void rm(dir, { recursive: true, force: true })
  }
}

const LANG = /^[A-Za-z0-9-]{1,20}$/

/**
 * Writes a saved copy the browser sends to a temp folder and opens the video in IINA. A
 * subtitle file named <id>.<lang>.vtt beside <id>.mp4 is loaded by IINA on its own.
 */
async function openInIina(req: IncomingMessage, id: string, lang: string | null): Promise<void> {
  const dir = join(tmpdir(), 'feeder-iina')
  await mkdir(dir, { recursive: true })
  const file = join(dir, lang ? `${id}.${lang}.vtt` : `${id}.mp4`)
  const size = Number(req.headers['content-length'] ?? -1)
  const same = await stat(file).then((s) => s.size === size, () => false)
  if (same) req.resume()
  else await pipeline(req, createWriteStream(file))
  if (lang) return
  const r = await run('open', ['-a', 'IINA', file])
  if (r.code !== 0) throw new Error(/Unable to find application/i.test(r.err) ? 'IINA is not installed on this Mac' : r.err.trim() || 'Could not open IINA')
}

const status = (job: Job | undefined) =>
  job ? { state: job.state, percent: Math.round(job.percent), size: job.size ?? 0, error: job.error ?? null } : { state: 'none', percent: 0, size: 0, error: null }

function send(res: ServerResponse, code: number, body: unknown) {
  res.statusCode = code
  res.setHeader('content-type', 'application/json')
  res.setHeader('cache-control', 'no-store')
  res.end(JSON.stringify(body))
}

/** Connect middleware for /api/ytdl/*. Calls next() for every other path. */
export async function ytdlMiddleware(req: IncomingMessage, res: ServerResponse, next: () => void) {
  if (!req.url?.startsWith('/api/ytdl/')) return next()
  const url = new URL(req.url, 'http://localhost')
  const token = process.env.ACCESS_TOKEN
  if (token && req.headers['x-access-token'] !== token && url.searchParams.get('t') !== token) return send(res, 401, { error: 'Access token required' })
  const action = url.pathname.slice('/api/ytdl/'.length)
  try {
    if (action === 'health') {
      const { ytdlp } = await findTools()
      const r = await run(ytdlp, ['--version'])
      return r.code === 0 ? send(res, 200, { ok: true, version: r.out.trim() }) : send(res, 503, { ok: false, error: 'yt-dlp is not installed on this Mac' })
    }
    const id = url.searchParams.get('id') ?? ''
    if (!ID.test(id)) return send(res, 400, { error: 'Bad video id' })
    if (action === 'start' && req.method === 'POST') return send(res, 200, status(await startJob(id)))
    if (action === 'status') return send(res, 200, status(jobs.get(id)))
    if (action === 'subs' && req.method === 'POST') return send(res, 200, { tracks: await subtitles(id) })
    if (action === 'iina' && req.method === 'POST') {
      const lang = url.searchParams.get('lang')
      if (lang !== null && !LANG.test(lang)) return send(res, 400, { error: 'Bad language' })
      await openInIina(req, id, lang)
      return send(res, 200, { ok: true })
    }
    if (action === 'file') {
      const job = jobs.get(id)
      if (!job || job.state !== 'done' || !job.file) return send(res, 404, { error: 'Not ready' })
      res.statusCode = 200
      res.setHeader('content-type', 'video/mp4')
      res.setHeader('content-length', String(job.size ?? 0))
      res.setHeader('cache-control', 'no-store')
      const stream = createReadStream(job.file)
      stream.pipe(res)
      res.on('finish', () => {
        jobs.delete(id)
        void rm(job.dir, { recursive: true, force: true })
      })
      stream.on('error', () => res.destroy())
      return
    }
    return send(res, 404, { error: 'Not found' })
  } catch (e) {
    return send(res, 500, { error: String(e) })
  }
}
