import { describe, expect, it } from 'vitest'
import { CLOSED_FOLDERS_KEY, collapseAllFolders, loadClosedFolders, saveClosedFolders, toggleFolder } from '../folders'

const mem = () => {
  const m = new Map<string, string>()
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), m }
}

describe('sidebar folder collapse state', () => {
  it('toggles a single folder', () => {
    expect(toggleFolder([], 'News')).toEqual(['News'])
    expect(toggleFolder(['News', 'Tech'], 'News')).toEqual(['Tech'])
  })

  it('collapses every named folder, ignoring the no-folder group', () => {
    expect(collapseAllFolders(['Old'], ['01 - Daily', '', 'Tech', 'Old'])).toEqual(['Old', '01 - Daily', 'Tech'])
  })

  it('round-trips through storage', () => {
    const s = mem()
    saveClosedFolders(['A', 'B'], s)
    expect(s.m.get(CLOSED_FOLDERS_KEY)).toBe('["A","B"]')
    expect(loadClosedFolders(s)).toEqual(['A', 'B'])
  })

  it('treats missing or corrupt storage as all expanded', () => {
    const s = mem()
    expect(loadClosedFolders(s)).toEqual([])
    s.setItem(CLOSED_FOLDERS_KEY, '{nope')
    expect(loadClosedFolders(s)).toEqual([])
    s.setItem(CLOSED_FOLDERS_KEY, '[1,"x"]')
    expect(loadClosedFolders(s)).toEqual(['x'])
    expect(loadClosedFolders(undefined)).toEqual([])
  })
})
