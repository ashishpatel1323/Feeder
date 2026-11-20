import { describe, expect, it } from 'vitest'
import { cleanVtt } from '../../../server/ytdl'
import { articleVideoId } from '../youtube'

describe('videos in articles', () => {
  it('finds a YouTube player embedded in the feed text when the link is not YouTube', () => {
    expect(articleVideoId({ link: 'https://youtu.be/dQw4w9WgXcQ', contentHtml: '' })).toBe('dQw4w9WgXcQ')
    expect(
      articleVideoId({ link: 'https://blog.example.com/post', contentHtml: '<p>Watch:</p><iframe src="//www.youtube-nocookie.com/embed/jNQXAC9IVRw?rel=0&amp;x=1"></iframe>' }),
    ).toBe('jNQXAC9IVRw')
    expect(articleVideoId({ link: 'https://blog.example.com/post', contentHtml: '<a href="https://youtube.com/watch?v=jNQXAC9IVRw">a link</a>' })).toBeNull()
  })
})

describe('auto captions', () => {
  it('drops timing tags, hold cues and repeated rolling lines', () => {
    const vtt = `WEBVTT
Kind: captions

00:00:00.000 --> 00:00:02.190 align:start position:0%
Last<00:00:00.400><c> week,</c><00:00:00.800><c> a</c>

00:00:02.190 --> 00:00:02.200 align:start position:0%
Last week, a

00:00:02.200 --> 00:00:04.270 align:start position:0%
Last week, a
developer<00:00:02.600><c> got</c>
`
    expect(cleanVtt(vtt)).toBe('WEBVTT\n\n00:00:00.000 --> 00:00:02.190\nLast week, a\n\n00:00:02.200 --> 00:00:04.270\ndeveloper got\n')
  })
})
