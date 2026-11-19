/** Which sidebar folders are collapsed, kept per browser in localStorage. */
export const CLOSED_FOLDERS_KEY = 'feeder.closedFolders'

export function loadClosedFolders(storage: Pick<Storage, 'getItem'> | undefined = globalThis.localStorage): string[] {
  try {
    const v: unknown = JSON.parse(storage?.getItem(CLOSED_FOLDERS_KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

export function saveClosedFolders(tags: string[], storage: Pick<Storage, 'setItem'> | undefined = globalThis.localStorage) {
  try {
    storage?.setItem(CLOSED_FOLDERS_KEY, JSON.stringify(tags))
  } catch {
    // Private mode or storage full: the state just won't survive a reload
  }
}

/** Collapse or expand one folder. */
export function toggleFolder(closed: string[], tag: string): string[] {
  return closed.includes(tag) ? closed.filter((x) => x !== tag) : [...closed, tag]
}

/** Every named folder collapsed (keeps any closed folders that aren't shown right now). */
export function collapseAllFolders(closed: string[], tags: string[]): string[] {
  return [...new Set([...closed, ...tags.filter(Boolean)])]
}
