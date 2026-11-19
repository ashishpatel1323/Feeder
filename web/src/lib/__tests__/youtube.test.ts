import { describe, expect, it } from 'vitest'
import { youtubeEmbedUrl, youtubeVideoId } from '../youtube'
import { parseFeed } from '../feedparse'

describe('youtubeVideoId', () => {
  it('reads watch, short, shorts and live links', () => {
    expect(youtubeVideoId('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ')
    expect(youtubeVideoId('https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s')).toBe('dQw4w9WgXcQ')
    expect(youtubeVideoId('https://youtu.be/dQw4w9WgXcQ?si=abc')).toBe('dQw4w9WgXcQ')
    expect(youtubeVideoId('https://www.youtube.com/shorts/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ')
    expect(youtubeVideoId('https://www.youtube.com/live/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ')
  })
  it('ignores other links', () => {
    expect(youtubeVideoId('https://www.youtube.com/channel/UC123')).toBeNull()
    expect(youtubeVideoId('https://example.com/watch?v=dQw4w9WgXcQ')).toBeNull()
    expect(youtubeVideoId('https://notyoutube.com/watch?v=dQw4w9WgXcQ')).toBeNull()
    expect(youtubeVideoId(null)).toBeNull()
    expect(youtubeVideoId('not a url')).toBeNull()
  })
  it('embeds from youtube.com so the browser sign-in applies', () => {
    expect(youtubeEmbedUrl('dQw4w9WgXcQ')).toMatch(/^https:\/\/www\.youtube\.com\/embed\/dQw4w9WgXcQ/)
  })
})

const YT = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <title>Some Channel</title>
 <link rel="alternate" href="https://www.youtube.com/channel/UC123"/>
 <entry>
  <id>yt:video:dQw4w9WgXcQ</id>
  <yt:videoId>dQw4w9WgXcQ</yt:videoId>
  <title>A video</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
  <author><name>Some Channel</name></author>
  <published>2026-11-18T10:00:00+00:00</published>
  <media:group>
   <media:title>A video</media:title>
   <media:thumbnail url="https://i4.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" width="480" height="360"/>
   <media:description>First line &lt;b&gt;
Second line https://example.com/x

New paragraph</media:description>
  </media:group>
 </entry>
</feed>`

describe('YouTube channel feeds', () => {
  it('link to the video and keep the description as escaped text', () => {
    const a = parseFeed(YT, 'https://www.youtube.com/feeds/videos.xml?channel_id=UC123').articles[0]
    expect(youtubeVideoId(a.link)).toBe('dQw4w9WgXcQ')
    expect(a.imageUrl).toBe('https://i4.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg')
    expect(a.contentHtml).toBe(
      '<p>First line &lt;b&gt;<br>Second line <a href="https://example.com/x">https://example.com/x</a></p><p>New paragraph</p>',
    )
    expect(a.snippet).toContain('First line')
  })
})
