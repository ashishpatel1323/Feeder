/**
 * Podcast transcripts (same rules as the Android app's TranscriptFormats.kt): the Podcasting 2.0
 * `<podcast:transcript>` files a feed links to, in WebVTT, SubRip or Podcast Index JSON. Formats
 * without timing (HTML, plain text) can't follow playback and are not read.
 */

export interface TranscriptCue {
  startMs: number
  endMs: number
  text: string
  speaker?: string
}

export interface TranscriptLink {
  url: string
  type: string | null
  language: string | null
  rel: string | null
}

type Kind = 'vtt' | 'srt' | 'json'

/** The cue playing at `positionMs`: the last one that started at or before it. -1 before the first. */
export function cueIndexAt(cues: TranscriptCue[], positionMs: number): number {
  let low = 0
  let high = cues.length - 1
  let found = -1
  while (low <= high) {
    const mid = (low + high) >>> 1
    if (cues[mid].startMs <= positionMs) {
      found = mid
      low = mid + 1
    } else high = mid - 1
  }
  return found
}

function kindOf(type: string | null | undefined, url?: string | null): Kind | null {
  const t = (type ?? '').toLowerCase().split(';')[0].trim()
  const ending = (url ?? '').split('?')[0].split('.').pop()?.toLowerCase() ?? ''
  if (t === 'text/vtt' || ending === 'vtt') return 'vtt'
  if (t === 'application/srt' || t === 'application/x-subrip' || t === 'text/srt' || ending === 'srt') return 'srt'
  if (t === 'application/json' || ending === 'json') return 'json'
  return null
}

const TIMING = /((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})/
const VOICE = /<v(?:\.[^ >]*)?\s+([^>]*)>/
const TAG = /<[^>]+>/g

function sniff(content: string): Kind | null {
  const head = content.replace(/^[﻿\s]+/, '')
  if (head.startsWith('WEBVTT')) return 'vtt'
  if (head.startsWith('{')) return 'json'
  if (TIMING.test(head.slice(0, 500))) return 'srt'
  return null
}

/** True for a link this can follow playback with. */
export function isTimedLink(link: TranscriptLink): boolean {
  return kindOf(link.type, link.url) !== null
}

export function parseTranscript(content: string, type?: string | null, url?: string | null): TranscriptCue[] {
  const kind = kindOf(type, url) ?? sniff(content)
  const cues = kind === 'json' ? parseJson(content) : kind ? parseBlocks(content) : []
  return cues.filter((c) => c.text.trim()).sort((a, b) => a.startMs - b.startMs)
}

function timeMs(value: string): number {
  const parts = value.replace(',', '.').split(':')
  const seconds = Number(parts[parts.length - 1])
  const minutes = parts.length >= 2 ? Number(parts[parts.length - 2]) : 0
  const hours = parts.length >= 3 ? Number(parts[parts.length - 3]) : 0
  return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000)
}

function decodeEntities(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

/** "SPEAKER_00" (speaker detection without names) reads as "Speaker 1". */
export function speakerName(raw: string | null | undefined): string | undefined {
  const name = (raw ?? '').trim()
  if (!name) return undefined
  const generic = /^speaker_(\d+)$/i.exec(name)
  return generic ? `Speaker ${Number(generic[1]) + 1}` : name
}

/** VTT and SRT are blocks split by blank lines, each with a timing line and its text. */
function parseBlocks(content: string): TranscriptCue[] {
  const cues: TranscriptCue[] = []
  for (const block of content.replace(/\r\n?/g, '\n').split(/\n\s*\n/)) {
    const lines = block.split('\n')
    const timingIndex = lines.findIndex((l) => TIMING.test(l))
    if (timingIndex < 0) continue
    const match = TIMING.exec(lines[timingIndex])!
    const raw = lines.slice(timingIndex + 1).join(' ').trim()
    const speaker = speakerName(VOICE.exec(raw)?.[1])
    const text = decodeEntities(raw.replace(TAG, '')).replace(/\s+/g, ' ').trim()
    cues.push({ startMs: timeMs(match[1]), endMs: timeMs(match[2]), text, ...(speaker ? { speaker } : {}) })
  }
  return cues
}

/** `{"version": "1.0.0", "segments": [{"speaker": "…", "startTime": 0.5, "endTime": 1.2, "body": "…"}]}` */
function parseJson(content: string): TranscriptCue[] {
  let root: unknown
  try {
    root = JSON.parse(content.replace(/^﻿/, ''))
  } catch {
    return []
  }
  const segments = Array.isArray(root) ? root : Array.isArray((root as { segments?: unknown })?.segments) ? (root as { segments: unknown[] }).segments : []
  const parsed: TranscriptCue[] = []
  for (const s of segments as Record<string, unknown>[]) {
    if (typeof s?.startTime !== 'number' || typeof s.body !== 'string') continue
    const end = typeof s.endTime === 'number' ? s.endTime : s.startTime
    const speaker = speakerName(typeof s.speaker === 'string' ? s.speaker : null)
    parsed.push({ startMs: Math.round(s.startTime * 1000), endMs: Math.round(end * 1000), text: s.body.trim(), ...(speaker ? { speaker } : {}) })
  }
  return joinWords(parsed)
}

/**
 * Some JSON transcripts have one segment per word. Those are joined into lines of about a
 * sentence (a pause, a new speaker, a sentence end or about 12 words starts a new line).
 */
function joinWords(segments: TranscriptCue[]): TranscriptCue[] {
  if (segments.length < 20) return segments
  const average = segments.reduce((n, s) => n + s.text.split(' ').length, 0) / segments.length
  if (average > 2) return segments
  const lines: TranscriptCue[] = []
  let current: TranscriptCue | null = null
  let words = 0
  for (const s of segments) {
    const newLine = !current || s.speaker !== current.speaker || s.startMs - current.endMs > 1200 || words >= 12 || /[.?!]$/.test(current.text)
    if (newLine) {
      if (current) lines.push(current)
      current = { ...s }
      words = 1
    } else {
      current = { ...current!, endMs: s.endMs, text: `${current!.text} ${s.text}` }
      words++
    }
  }
  if (current) lines.push(current)
  return lines
}

/** Finds an episode's transcripts in its feed's XML, by guid, else by its audio file's URL. */
export function findTranscriptLinks(feedXml: string, guid: string | null, enclosureUrl: string | null): TranscriptLink[] {
  const doc = new DOMParser().parseFromString(feedXml, 'application/xml')
  const items = [...Array.from(doc.getElementsByTagName('item')), ...Array.from(doc.getElementsByTagName('entry'))]
  const childText = (e: Element, name: string) => Array.from(e.children).find((c) => c.localName === name || c.tagName === name)?.textContent?.trim()
  const enclosures = (e: Element) =>
    Array.from(e.children)
      .filter((c) => c.localName === 'enclosure' || (c.localName === 'link' && c.getAttribute('rel') === 'enclosure'))
      .map((c) => c.getAttribute('url') ?? c.getAttribute('href') ?? '')
  const bare = (u: string) => u.trim().replace(/^https?:\/\//, '').split('?')[0]
  const g = guid?.trim()
  const item =
    (g ? items.find((i) => childText(i, 'guid') === g || childText(i, 'id') === g) : undefined) ??
    (enclosureUrl ? items.find((i) => enclosures(i).some((u) => u === enclosureUrl || bare(u) === bare(enclosureUrl))) : undefined)
  if (!item) return []
  return Array.from(item.getElementsByTagName('*'))
    .filter((e) => e.localName === 'transcript' && (e.prefix === 'podcast' || e.namespaceURI?.includes('podcastindex')))
    .map((e) => ({
      url: (e.getAttribute('url') ?? '').trim(),
      type: e.getAttribute('type')?.trim() || null,
      language: e.getAttribute('language')?.trim() || null,
      rel: e.getAttribute('rel')?.trim() || null,
    }))
    .filter((l) => l.url)
}

/** The best transcript to follow playback with: captions first, then VTT, SRT, JSON. */
export function bestTranscriptLink(links: TranscriptLink[]): TranscriptLink | null {
  const score = (l: TranscriptLink) => {
    const k = kindOf(l.type, l.url)
    return (l.rel === 'captions' ? 0 : 10) + (k === 'vtt' ? 0 : k === 'srt' ? 1 : 2)
  }
  return links.filter(isTimedLink).sort((a, b) => score(a) - score(b))[0] ?? null
}

/** One place the search text appears: in cue `cue`, characters `start` to `end` of its text. */
export interface TranscriptMatch {
  cue: number
  start: number
  end: number
}

/** Every place `query` appears in the transcript, ignoring case and accents. */
export function findMatches(cues: TranscriptCue[], query: string): TranscriptMatch[] {
  const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
  const q = fold(query.trim())
  if (!q) return []
  const matches: TranscriptMatch[] = []
  cues.forEach((cue, i) => {
    // Folding only drops accent marks, so positions are mapped back through the original text
    const original = cue.text
    const map: number[] = []
    let folded = ''
    for (let j = 0; j < original.length; j++) {
      const f = fold(original[j])
      for (let k = 0; k < f.length; k++) map.push(j)
      folded += f
    }
    for (let at = folded.indexOf(q); at >= 0; at = folded.indexOf(q, at + q.length)) {
      matches.push({ cue: i, start: map[at], end: map[at + q.length - 1] + 1 })
    }
  })
  return matches
}

function clock(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const mm = Math.floor(s / 60) % 60
  const ss = String(s % 60).padStart(2, '0')
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(mm).padStart(2, '0')}:${ss}` : `${mm}:${ss}`
}

/** The whole transcript as plain text for the clipboard: a time on each line, speakers when they change. */
export function transcriptPlainText(cues: TranscriptCue[], title?: string | null): string {
  const lines: string[] = []
  if (title) lines.push(title, '')
  cues.forEach((cue, i) => {
    if (cue.speaker && cue.speaker !== cues[i - 1]?.speaker) {
      if (lines.length && lines[lines.length - 1] !== '') lines.push('')
      lines.push(`${cue.speaker}:`)
    }
    lines.push(`[${clock(cue.startMs)}] ${cue.text}`)
  })
  return lines.join('\n') + '\n'
}
