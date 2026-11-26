import { describe, expect, it } from 'vitest'
import { folderFilterLabel, pickedFolders, readPicked, toggleFolder } from '../folderFilter'

describe('folder filter (folderFilter.ts)', () => {
  const folders = ['Health', 'Product', 'Science']
  it('toggles folders in and out, in folder order, dropping ones that are gone', () => {
    expect(toggleFolder([], 'Science', folders)).toEqual(['Science'])
    expect(toggleFolder(['Science'], 'Health', folders)).toEqual(['Health', 'Science'])
    expect(toggleFolder(['Health', 'Science'], 'Health', folders)).toEqual(['Science'])
    expect(toggleFolder(['Gone'], 'Product', folders)).toEqual(['Product'])
    // Every folder picked stays a pick (it leaves out feeds in no folder, unlike All)
    expect(toggleFolder(['Health', 'Product'], 'Science', folders)).toEqual(folders)
    expect(pickedFolders(['Science', 'Gone'], folders)).toEqual(['Science'])
  })
  it('labels the chip', () => {
    expect(folderFilterLabel([])).toBe('All folders')
    expect(folderFilterLabel(['Science'])).toBe('Science')
    expect(folderFilterLabel(['A', 'B'])).toBe('2 folders')
    expect(folderFilterLabel(['A', 'B', 'C'], 'All groups', 'group')).toBe('3 groups')
  })
  it('reads old single picks and new lists', () => {
    expect(readPicked('X')).toEqual(['X'])
    expect(readPicked('')).toEqual([])
    expect(readPicked(['X', 3, 'Y'])).toEqual(['X', 'Y'])
    expect(readPicked(undefined)).toEqual([])
  })
})
