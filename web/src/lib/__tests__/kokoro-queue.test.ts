import { beforeEach, describe, expect, it, vi } from 'vitest'

// A fake worker that records what it was asked to generate and answers on demand
const sent: { id: number; text: string }[] = []
let fake: { onmessage: ((e: { data: unknown }) => void) | null } | null = null
class FakeWorker {
  onmessage: ((e: { data: unknown }) => void) | null = null
  onerror = null
  constructor() {
    fake = this
  }
  postMessage(m: { type: string; id: number; text: string }) {
    if (m.type === 'generate') sent.push({ id: m.id, text: m.text })
  }
  terminate() {}
}
vi.stubGlobal('Worker', FakeWorker)

const answer = (id: number) => fake!.onmessage!({ data: { type: 'audio', id, samples: new Float32Array(1), sampleRate: 24000 } })

describe('kokoro request queue', () => {
  beforeEach(() => {
    sent.length = 0
    vi.resetModules()
  })

  it('sends one request at a time, urgent and prioritized ones first, and drops waiting ones', async () => {
    const { kokoro } = await import('../kokoro')
    const a = kokoro.generate('a', 'v')
    const b = kokoro.generate('b', 'v')
    const c = kokoro.generate('c', 'v')
    const d = kokoro.generate('d', 'v', true)
    expect(sent.map((s) => s.text)).toEqual(['a'])

    kokoro.prioritize([c.id])
    const dropped = kokoro.dropWaiting(new Set([c.id, d.id]))
    expect(dropped).toEqual([b.id])
    await expect(b.audio).rejects.toThrow('Dropped')

    answer(a.id)
    await a.audio
    expect(sent.map((s) => s.text)).toEqual(['a', 'c'])
    answer(c.id)
    expect(sent.map((s) => s.text)).toEqual(['a', 'c', 'd'])
    answer(d.id)
    await expect(d.audio).resolves.toMatchObject({ sampleRate: 24000 })
  })

  it('keeps background work (chapters prepared ahead) behind reading and never drops it', async () => {
    const { kokoro } = await import('../kokoro')
    const first = kokoro.generate('first', 'v')
    const bg = kokoro.generate('bg', 'v', false, true)
    const live = kokoro.generate('live', 'v')
    expect(kokoro.dropWaiting(new Set([live.id]))).toEqual([])
    answer(first.id)
    await first.audio
    expect(sent.map((s) => s.text)).toEqual(['first', 'live'])
    answer(live.id)
    expect(sent.map((s) => s.text)).toEqual(['first', 'live', 'bg'])
    answer(bg.id)
    await expect(bg.audio).resolves.toMatchObject({ sampleRate: 24000 })
  })
})
