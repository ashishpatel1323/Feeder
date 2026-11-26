/**
 * A YouTube video's captions as a transcript, from yt-dlp on the Mac (/api/ytdl/subs, the same
 * call that saves subtitles next to downloads). The browser can't get YouTube's captions itself,
 * so on the hosted web app (no yt-dlp) there is no transcript. Kept in memory per video.
 */
import { useEffect, useState } from 'react'
import { videoSavingAvailable, ytdlUrl } from './offline'
import { parseTranscript, type TranscriptCue } from './transcriptFormats'

export type VideoTranscriptStatus = 'loading' | 'ready' | 'none' | 'unavailable' | 'failed'
export interface VideoTranscript {
  status: VideoTranscriptStatus
  cues: TranscriptCue[]
  error?: string
}

const cache = new Map<string, Promise<VideoTranscript>>()

/** English first (the server lists English variants, then Hindi), else the first track. */
export function pickTrack(tracks: { lang: string; vtt: string }[]): { lang: string; vtt: string } | null {
  return tracks.find((t) => /^en/i.test(t.lang)) ?? tracks[0] ?? null
}

async function fetchTranscript(videoId: string): Promise<VideoTranscript> {
  if (!(await videoSavingAvailable())) return { status: 'unavailable', cues: [] }
  const res = await fetch(ytdlUrl('subs', videoId), { method: 'POST' })
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { error?: string } | null
    return { status: 'failed', cues: [], error: body?.error ?? `HTTP ${res.status}` }
  }
  const { tracks } = (await res.json()) as { tracks: { lang: string; vtt: string }[] }
  const track = pickTrack(tracks ?? [])
  const cues = track ? parseTranscript(track.vtt, 'text/vtt') : []
  return cues.length ? { status: 'ready', cues } : { status: 'none', cues: [] }
}

export function useVideoTranscript(videoId: string, enabled: boolean): VideoTranscript {
  const [state, setState] = useState<VideoTranscript>({ status: 'loading', cues: [] })
  useEffect(() => {
    if (!enabled) return
    let live = true
    setState({ status: 'loading', cues: [] })
    let p = cache.get(videoId)
    if (!p) {
      p = fetchTranscript(videoId).catch((e): VideoTranscript => ({ status: 'failed', cues: [], error: e instanceof Error ? e.message : String(e) }))
      cache.set(videoId, p)
      // A failure is tried again next time
      void p.then((r) => r.status === 'failed' && cache.delete(videoId))
    }
    void p.then((r) => live && setState(r))
    return () => {
      live = false
    }
  }, [videoId, enabled])
  return state
}
