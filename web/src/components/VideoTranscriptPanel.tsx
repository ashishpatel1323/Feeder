/**
 * The YouTube video's transcript under its chapter headings (option B, same as Android's full
 * screen panel), with Search and Copy: ChapterTranscript, the same view audiobooks use. Chapters
 * come from the description's time stamps; no chapters = 5-minute blocks.
 */
import { useEffect, useMemo, useState } from 'react'
import { descriptionChapters, transcriptSections } from '../lib/transcriptSections'
import { useVideoTranscript } from '../lib/youtubeTranscript'
import { ChapterTranscript } from './ChapterTranscript'
import type { YTPlayer } from './YouTubeFrame'

export { formatCueTime } from './ChapterTranscript'

export function VideoTranscriptPanel({ videoId, description, player }: { videoId: string; description: string | null; player: YTPlayer | null }) {
  const t = useVideoTranscript(videoId, true)
  const chapters = useMemo(() => descriptionChapters(description), [description])
  const sections = useMemo(() => transcriptSections(t.cues, chapters), [t.cues, chapters])
  const [positionMs, setPositionMs] = useState(0)
  useEffect(() => {
    if (!player) return
    const tick = window.setInterval(() => {
      try {
        setPositionMs(Math.round(player.getCurrentTime() * 1000))
      } catch {
        // player gone
      }
    }, 250)
    return () => clearInterval(tick)
  }, [player])
  const seek = (ms: number) => {
    try {
      player?.seekTo(ms / 1000, true)
      player?.playVideo()
      setPositionMs(ms)
    } catch {
      // player gone
    }
  }

  let status = null
  if (t.status === 'loading') status = <p className="transcript-status">Getting the captions…</p>
  else if (t.status === 'unavailable') status = <p className="transcript-status">Transcripts of YouTube videos need the Feeder Mac app, which gets the captions with yt-dlp.</p>
  else if (t.status === 'none') status = <p className="transcript-status">This video has no captions.</p>
  else if (t.status === 'failed') status = <p className="transcript-status">Couldn't get the captions: {t.error}</p>

  return (
    <ChapterTranscript
      cues={t.cues}
      sections={sections}
      positionMs={positionMs}
      onSeek={seek}
      status={status}
      resetKey={videoId}
      title="YouTube video"
      ready={t.status === 'ready'}
    />
  )
}
