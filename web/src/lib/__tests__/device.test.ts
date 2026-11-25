import { describe, expect, it } from 'vitest'
import { agoText, autoDeviceName } from '../device'

describe('device names', () => {
  it('guesses a name from the browser', () => {
    expect(autoDeviceName('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36')).toBe('Chrome on Mac')
    expect(autoDeviceName('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Feeder/1.0 Chrome/130.0 Electron/33.0 Safari/537.36')).toBe('Mac app')
    expect(autoDeviceName('Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0')).toBe('Firefox on Windows')
  })

  it('says how long ago', () => {
    expect(agoText(0, 30_000)).toBe('just now')
    expect(agoText(0, 5 * 60_000)).toBe('5 min ago')
    expect(agoText(0, 3 * 3_600_000)).toBe('3h ago')
    expect(agoText(0, 2 * 86_400_000)).toBe('2d ago')
  })
})
