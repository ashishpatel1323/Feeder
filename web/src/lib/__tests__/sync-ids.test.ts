import { describe, expect, it } from 'vitest'
import { parseSyncJson } from '../sync'

describe('parseSyncJson', () => {
  it('keeps 64-bit device ids exact', () => {
    const id = '9123456789012345678'
    expect(String(JSON.parse(`{"deviceId":${id}}`).deviceId)).not.toBe(id)
    const res = parseSyncJson<{ syncCode: string; deviceId: string }>(`{"syncCode":"abc","deviceId":${id}}`)
    expect(res.deviceId).toBe(id)
    expect(res.syncCode).toBe('abc')
  })

  it('handles device lists, spacing and negative ids', () => {
    const res = parseSyncJson<{ devices: { deviceId: string; deviceName: string }[] }>(
      '{"devices":[{"deviceId": 8000000000000000001,"deviceName":"a:b:c"},{"deviceId":-5,"deviceName":"x"}]}',
    )
    expect(res.devices.map((d) => d.deviceId)).toEqual(['8000000000000000001', '-5'])
  })

  it('leaves other numbers alone', () => {
    expect(parseSyncJson<{ hash: number }>('{"hash":12345}').hash).toBe(12345)
    expect(parseSyncJson('')).toEqual({})
  })
})
