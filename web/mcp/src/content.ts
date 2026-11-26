/**
 * Turns an item into searchable chunks: the article text (full text when the feed only gives a
 * teaser), or the transcript of a podcast episode or YouTube video.
 */
import { execFile } from 'node:child_process'
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import type { Db } from './db.js'
import { fetchFullText, fetchText } from './feeds.js'
import { articleVideoId, itemKind, type ItemKind } from './kinds.js'
import { chunksOf, hasDocument, putDocument, type StoredChunk } from './store.js'
import { chunkBlocks, chunkCues, htmlToBlocks, textToBlocks } from './text.js'
import { parseTranscript, type TranscriptCue } from './vendor/transcriptFormats.js'

const run = promisify(execFile)

export interface ItemRow {
  id: string
  feed_url: string
  guid: string
  title: string
  link: string | null
  author: string | null
  sort_time: number
  content_html: string
  snippet: string
  word_count: number
  enclosure_url: string | null
  duration: number | null
  full_html: string | null
  full_word_count: number | null
  transcript_url: string | null
  transcript_type: string | null
  transcript_json: string | null
  transcript_source: string | null
  stub: number
}

export const getItem = (db: Db, id: string) => db.prepare('SELECT * FROM items WHERE id = ?').get(id) as ItemRow | undefined

export const kindOfRow = (i: ItemRow): ItemKind => itemKind(i)

export type Prefer = 'auto' | 'full_text' | 'feed' | 'transcript'

export interface ItemText {
  docId: string
  source: 'article' | 'podcast' | 'youtube'
  /** Where the text came from: feed, full_text, transcript:feed, transcript:youtube, snippet */
  origin: string
  chunks: StoredChunk[]
  note?: string
}

const SHORT_WORDS = 200

/** Makes (once) and returns the chunks for an item. */
export async function itemText(db: Db, item: ItemRow, prefer: Prefer = 'auto'): Promise<ItemText> {
  const kind = kindOfRow(item)
  const wantTranscript = prefer === 'transcript' || (prefer === 'auto' && kind !== 'article')
  if (wantTranscript) {
    const t = await transcriptText(db, item, kind)
    if (t) return t
    if (prefer === 'transcript') {
      const a = await articleText(db, item, 'feed')
      return { ...a, note: noTranscriptNote(kind) }
    }
    const a = await articleText(db, item, 'auto')
    return { ...a, note: noTranscriptNote(kind) }
  }
  return articleText(db, item, prefer)
}

const noTranscriptNote = (kind: ItemKind) =>
  kind === 'podcast'
    ? 'No transcript: the feed links none for this episode. Showing the show notes instead.'
    : kind === 'youtube'
      ? 'No captions found for this video (or yt-dlp is missing). Showing the description instead.'
      : 'No transcript.'

async function articleText(db: Db, item: ItemRow, prefer: Prefer): Promise<ItemText> {
  const docId = `item:${item.id}`
  const source = kindOfRow(item)
  let html: string | null = null
  let origin = 'feed'
  if (prefer !== 'feed') {
    if (item.full_html) {
      html = item.full_html
      origin = 'full_text'
    } else if (item.link && source === 'article' && (prefer === 'full_text' || item.stub || item.word_count < SHORT_WORDS)) {
      html = await fetchFullText(db, item.id, item.link)
      if (html) origin = 'full_text'
    }
  }
  if (!html) {
    html = item.content_html
    origin = 'feed'
  }
  let blocks = htmlToBlocks(html)
  if (!blocks.length && item.snippet) {
    blocks = textToBlocks(item.snippet)
    origin = 'snippet'
  }
  const chunks = chunkBlocks(blocks, { item: item.id })
  // The document keeps whichever text was made last; full text replaces the teaser
  if (!hasDocument(db, docId) || origin !== 'feed' || prefer === 'feed') putDocument(db, docId, source === 'article' ? 'article' : source, item.id, item.title, chunks)
  return { docId, source, origin, chunks: chunksOf(db, docId) }
}

async function transcriptText(db: Db, item: ItemRow, kind: ItemKind): Promise<ItemText | null> {
  const docId = `transcript:${item.id}`
  if (hasDocument(db, docId)) return { docId, source: kind === 'youtube' ? 'youtube' : 'podcast', origin: `transcript:${item.transcript_source ?? 'feed'}`, chunks: chunksOf(db, docId) }
  let cues: TranscriptCue[] | null = item.transcript_json ? (JSON.parse(item.transcript_json) as TranscriptCue[]) : null
  let origin = item.transcript_source ?? ''
  if (!cues && kind === 'podcast' && item.transcript_url) {
    try {
      const { text, type } = await fetchText(item.transcript_url)
      cues = parseTranscript(text, item.transcript_type ?? type, item.transcript_url)
      // Plain text or HTML transcripts have no times: one cue per paragraph
      if (!cues.length && text.trim()) {
        const plain = /html/i.test(item.transcript_type ?? type) ? htmlToBlocks(text).map((b) => b.text) : text.split(/\n\s*\n/)
        cues = plain.filter((t) => t.trim()).map((t) => ({ startMs: 0, endMs: 0, text: t.trim() }))
      }
      origin = 'feed'
    } catch {
      cues = null
    }
  }
  if (!cues && kind === 'youtube') {
    const vid = articleVideoId(item)
    if (vid) {
      cues = await youtubeCaptions(vid)
      origin = 'youtube'
    }
  }
  if (!cues?.length) return null
  db.prepare('UPDATE items SET transcript_json = ?, transcript_source = ? WHERE id = ?').run(JSON.stringify(cues), origin, item.id)
  const timed = cues.some((c) => c.startMs > 0)
  const chunks = timed ? chunkCues(cues, { item: item.id }) : chunkBlocks(cues.map((c) => ({ level: 0, text: c.text })), { item: item.id })
  putDocument(db, docId, kind === 'youtube' ? 'youtube' : 'podcast', item.id, item.title, chunks)
  return { docId, source: kind === 'youtube' ? 'youtube' : 'podcast', origin: `transcript:${origin}`, chunks: chunksOf(db, docId) }
}

function ytDlp(): string | null {
  if (process.env.YTDLP && existsSync(process.env.YTDLP)) return process.env.YTDLP
  const home = homedir()
  for (const p of [join(home, '.local/bin/yt-dlp'), join(home, 'homebrew/bin/yt-dlp'), '/opt/homebrew/bin/yt-dlp', '/usr/local/bin/yt-dlp']) if (existsSync(p)) return p
  return null
}

/** English captions of a YouTube video with yt-dlp: the uploader's own first, else the automatic ones. */
export async function youtubeCaptions(videoId: string): Promise<TranscriptCue[] | null> {
  const bin = ytDlp()
  if (!bin) return null
  const dir = mkdtempSync(join(tmpdir(), 'feeder-mcp-subs-'))
  try {
    // "en.*" would also pull every auto-translation into English (en-de, en-fr, …) and hit rate limits
    await run(
      bin,
      ['--skip-download', '--write-subs', '--write-auto-subs', '--sub-langs', 'en,en-orig,en-US,en-GB', '--sub-format', 'vtt', '--ignore-errors', '-o', join(dir, '%(id)s.%(ext)s'), `https://www.youtube.com/watch?v=${videoId}`],
      { timeout: 90_000 },
    ).catch(() => undefined) // a failed extra language still leaves the ones that worked
    const files = readdirSync(dir).filter((f) => f.endsWith('.vtt'))
    // Uploaded captions are named e.g. id.en.vtt; automatic ones id.en-orig.vtt or id.en.vtt too. Prefer the shortest language tag.
    files.sort((a, b) => a.length - b.length)
    if (!files.length) return null
    return dedupeRolling(parseTranscript(readFileSync(join(dir, files[0]), 'utf8'), 'text/vtt'))
  } catch {
    return null
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

/** YouTube's automatic captions repeat the previous line in every cue; keep each line once. */
export function dedupeRolling(cues: TranscriptCue[]): TranscriptCue[] {
  const out: TranscriptCue[] = []
  let last = ''
  for (const c of cues) {
    const lines = c.text
      .replace(/<[^>]+>/g, '')
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean)
    const fresh = lines.filter((l) => l !== last)
    if (!fresh.length) continue
    last = lines[lines.length - 1]
    out.push({ ...c, text: fresh.join(' ') })
  }
  return out
}
