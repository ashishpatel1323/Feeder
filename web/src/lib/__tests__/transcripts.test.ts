import { describe, expect, it } from 'vitest'
import { bestTranscriptLink, cueIndexAt, findTranscriptLinks, parseTranscript } from '../transcriptFormats'
import { chunksToCues, quietestCut } from '../transcripts'

describe('transcript formats', () => {
  it('parses VTT with voices, tags and entities', () => {
    const vtt = 'WEBVTT\n\nNOTE by hand\n\n1\n00:00:00.500 --> 00:00:02.000\n<v Alice>Hello &amp; <i>welcome</i>\n\n00:01:02.250 --> 00:01:05.000 align:start\nSecond line\ncontinues'
    const cues = parseTranscript(vtt, 'text/vtt')
    expect(cues).toEqual([
      { startMs: 500, endMs: 2000, text: 'Hello & welcome', speaker: 'Alice' },
      { startMs: 62250, endMs: 65000, text: 'Second line continues' },
    ])
  })

  it('parses SRT with hours and names generic speakers', () => {
    const srt = '1\r\n01:00:01,000 --> 01:00:03,500\r\nFirst\r\n\r\n2\r\n01:00:04,000 --> 01:00:05,000\r\n<v SPEAKER_01>Second\r\n'
    const cues = parseTranscript(srt, 'application/x-subrip')
    expect(cues.map((c) => c.startMs)).toEqual([3_601_000, 3_604_000])
    expect(cues[1].speaker).toBe('Speaker 2')
  })

  it('parses Podcast Index JSON and joins word-level segments', () => {
    const words = Array.from({ length: 30 }, (_, i) => ({ speaker: 'SPEAKER_00', startTime: i * 0.3, endTime: i * 0.3 + 0.25, body: `w${i}` }))
    const cues = parseTranscript(JSON.stringify({ version: '1.0.0', segments: words }), 'application/json')
    expect(cues).toHaveLength(3)
    expect(cues[0].text).toBe('w0 w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11')
    expect(cues[0].speaker).toBe('Speaker 1')
  })

  it('skips formats without timing', () => {
    expect(parseTranscript('<html><p>No timing</p></html>', 'text/html')).toEqual([])
  })

  it('finds the cue at a position', () => {
    const cues = [
      { startMs: 1000, endMs: 2000, text: 'a' },
      { startMs: 2000, endMs: 3000, text: 'b' },
    ]
    expect(cueIndexAt(cues, 500)).toBe(-1)
    expect(cueIndexAt(cues, 1999)).toBe(0)
    expect(cueIndexAt(cues, 9000)).toBe(1)
  })

  it('finds transcript links in a feed by guid or enclosure', () => {
    const xml = `<rss xmlns:podcast="https://podcastindex.org/namespace/1.0" version="2.0"><channel>
      <item><guid isPermaLink="false">ep-1</guid><enclosure url="https://cdn/ep1.mp3" type="audio/mpeg"/>
        <podcast:transcript url="https://t/ep1.html" type="text/html"/>
        <podcast:transcript url="https://t/ep1.json" type="application/json"/>
        <podcast:transcript url="https://t/ep1.vtt" type="text/vtt" rel="captions"/>
      </item>
      <item><guid>ep-2</guid><enclosure url="https://cdn/ep2.mp3?x=1" type="audio/mpeg"/>
        <podcast:transcript url="https://t/ep2.srt" type="application/srt"/>
      </item></channel></rss>`
    expect(bestTranscriptLink(findTranscriptLinks(xml, 'ep-1', null))?.url).toBe('https://t/ep1.vtt')
    expect(bestTranscriptLink(findTranscriptLinks(xml, 'nope', 'http://cdn/ep2.mp3'))?.url).toBe('https://t/ep2.srt')
    expect(findTranscriptLinks(xml, 'nope', 'https://other/x.mp3')).toEqual([])
  })
})

describe('speech to text helpers', () => {
  it('turns Whisper chunks into cues and drops non-speech markers', () => {
    const cues = chunksToCues(
      [
        { start: 0, end: 2.5, text: ' Hello there.' },
        { start: 2.5, end: 4, text: ' [MUSIC]' },
        { start: 4, end: null, text: ' Last' },
      ],
      60_000,
    )
    expect(cues).toEqual([
      { startMs: 60_000, endMs: 62_500, text: 'Hello there.' },
      { startMs: 64_000, endMs: 66_000, text: 'Last' },
    ])
  })

  it('joins pieces of one sentence', () => {
    const cues = chunksToCues(
      [
        { start: 0, end: 3, text: ' The biggest stocks are names like Microsoft,' },
        { start: 3, end: 4, text: ' and Amazon.' },
        { start: 4.2, end: 6, text: ' Next one.' },
      ],
      0,
    )
    expect(cues.map((c) => c.text)).toEqual(['The biggest stocks are names like Microsoft, and Amazon.', 'Next one.'])
    expect(cues[0].endMs).toBe(4000)
  })

  it('cuts pieces in the quietest moment', () => {
    const samples = new Float32Array(32_000).fill(0.8)
    samples.fill(0, 20_000, 21_600)
    const cut = quietestCut(samples, 16_000, 32_000)
    expect(cut).toBeGreaterThanOrEqual(20_000)
    expect(cut).toBeLessThanOrEqual(21_600)
  })
})
