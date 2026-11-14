import { useLiveQuery } from 'dexie-react-hooks'
import { db, setKv } from './db'

export type Grouping = 'none' | 'date' | 'feed' | 'tag' | 'length' | 'status' | 'site'
export type Sorting = 'newest' | 'oldest' | 'shortest' | 'longest'
export type FilterChip = 'unread' | 'saved' | 'today' | 'short' | 'read'

export interface Settings {
  grouping: Grouping
  sorting: Sorting
  filters: FilterChip[]
  collapsedGroups: string[]
  snippetLines: number
  showThumbnails: boolean
  showHnScore: boolean
  markReadOnOpen: boolean
  refreshMinutes: number
  theme: 'system' | 'light' | 'dark'
  textSize: number
  readAloudRate: number
  readAloudVoice: string
  readAloudAutoContinue: boolean
  /** Run the natural (Kokoro) voice on the GPU where WebGPU exists: faster, bigger download. */
  readAloudKokoroGpu: boolean
  summaryProvider: 'none' | 'openai'
  /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1 or http://localhost:11434/v1 for Ollama. */
  summaryBaseUrl: string
  summaryApiKey: string
  summaryModel: string
}

export const DEFAULT_SETTINGS: Settings = {
  grouping: 'none',
  sorting: 'newest',
  filters: ['unread'],
  collapsedGroups: [],
  snippetLines: 2,
  showThumbnails: true,
  showHnScore: false,
  markReadOnOpen: true,
  refreshMinutes: 30,
  theme: 'system',
  textSize: 18,
  readAloudRate: 1,
  readAloudVoice: '',
  readAloudAutoContinue: true,
  readAloudKokoroGpu: false,
  summaryProvider: 'none',
  summaryBaseUrl: 'http://localhost:11434/v1',
  summaryApiKey: '',
  summaryModel: 'llama3.2',
}

const KEY = 'settings'

export function useSettings(): Settings {
  const stored = useLiveQuery(() => db.kv.get(KEY), [])
  return { ...DEFAULT_SETTINGS, ...((stored?.value as Partial<Settings>) ?? {}) }
}

export async function getSettings(): Promise<Settings> {
  const stored = await db.kv.get(KEY)
  return { ...DEFAULT_SETTINGS, ...((stored?.value as Partial<Settings>) ?? {}) }
}

export async function updateSettings(patch: Partial<Settings>): Promise<void> {
  const current = await getSettings()
  await setKv(KEY, { ...current, ...patch })
}
