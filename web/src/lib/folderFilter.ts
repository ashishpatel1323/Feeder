/**
 * A folder filter that can pick several folders at once (the one dropdown that replaced rows of
 * folder chips). An empty pick means All. Picks of folders that no longer exist are dropped.
 */

/** Only the picked folders that still exist, in the order of `folders`. */
export function pickedFolders(picked: readonly string[], folders: readonly string[]): string[] {
  const set = new Set(picked)
  return folders.filter((f) => set.has(f))
}

/** Adds or removes one folder (every folder picked still leaves out feeds in no folder, unlike All). */
export function toggleFolder(picked: readonly string[], name: string, folders: readonly string[]): string[] {
  return pickedFolders(picked.includes(name) ? picked.filter((f) => f !== name) : [...picked, name], folders)
}

/** The dropdown's own text: "All folders", the one folder's name, or "3 folders". */
export function folderFilterLabel(picked: readonly string[], allLabel = 'All folders', noun = 'folder'): string {
  if (!picked.length) return allLabel
  if (picked.length === 1) return picked[0]
  return `${picked.length} ${noun}s`
}

/** A saved pick: an array of names, or the single name older versions kept. */
export function readPicked(v: unknown): string[] {
  if (typeof v === 'string') return v ? [v] : []
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}
