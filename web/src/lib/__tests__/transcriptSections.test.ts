import { describe, expect, it } from 'vitest'
import { descriptionChapters, sectionIndexAt, transcriptSections } from '../transcriptSections'

const cue = (sec: number, text = `line at ${sec}`) => ({ startMs: sec * 1000, endMs: sec * 1000 + 4000, text })

describe('transcriptSections', () => {
  it('puts lines under their chapter', () => {
    const s = transcriptSections([cue(0), cue(5), cue(62), cue(70), cue(130)], [
      { startMs: 60_000, title: 'Two' },
      { startMs: 0, title: 'One' },
      { startMs: 120_000, title: 'Three' },
    ])
    expect(s.map((x) => [x.title, x.firstCue, x.endCue])).toEqual([
      ['One', 0, 2],
      ['Two', 2, 4],
      ['Three', 4, 5],
    ])
    expect(sectionIndexAt(s, 125_000)).toBe(2)
  })
  it('gives a short video without chapters no headings', () => {
    expect(transcriptSections([cue(0), cue(200)], [])).toEqual([])
  })
  it('splits a long video without chapters into 5-minute blocks', () => {
    const s = transcriptSections([cue(0, 'Welcome back to the channel, today we look at telescopes'), cue(100), cue(310, 'Next part'), cue(900, 'Much later')], [])
    expect(s.map((x) => x.startMs)).toEqual([0, 300_000, 900_000])
    expect(s[0].title).toBe('Welcome back to the channel, today we…')
  })
})

describe('descriptionChapters', () => {
  it('reads timestamp lines', () => {
    const d = 'Great episode\n\n00:00 Intro\n1:42 - Launch\n(1:02:03) Wrap up\nThanks!'
    expect(descriptionChapters(d)).toEqual([
      { startMs: 0, title: 'Intro' },
      { startMs: 102_000, title: 'Launch' },
      { startMs: 3_723_000, title: 'Wrap up' },
    ])
  })
  it('needs a list starting at 0:00 with three entries', () => {
    expect(descriptionChapters('1:00 A\n2:00 B\n3:00 C')).toEqual([])
    expect(descriptionChapters('0:00 A\n2:00 B')).toEqual([])
  })
})
