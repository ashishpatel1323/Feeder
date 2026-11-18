import { useLiveQuery } from 'dexie-react-hooks'
import { db, setKv } from './db'

export type Grouping = 'none' | 'date' | 'feed' | 'tag' | 'length' | 'site'
export type Sorting = 'newest' | 'oldest' | 'shortest' | 'longest'
/** The Show tabs above the list: a single choice. */
export type ShowFilter = 'unread' | 'all' | 'saved'

export interface Settings {
  grouping: Grouping
  sorting: Sorting
  show: ShowFilter
  /** Today chip: only items from today. */
  today: boolean
  collapsedGroups: string[]
  snippetLines: number
  showThumbnails: boolean
  showHnScore: boolean
  markReadOnOpen: boolean
  refreshMinutes: number
  theme: 'system' | 'light' | 'dark'
  textSize: number
  /** Bold the start of every word in the reader ("fast reading"). */
  fastReading: boolean
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
  show: 'unread',
  today: false,
  collapsedGroups: [],
  snippetLines: 2,
  showThumbnails: true,
  showHnScore: false,
  markReadOnOpen: true,
  refreshMinutes: 30,
  theme: 'system',
  textSize: 18,
  fastReading: false,
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

const GROUPINGS: Grouping[] = ['none', 'date', 'feed', 'tag', 'length', 'site']
const SHOWS: ShowFilter[] = ['unread', 'all', 'saved']

/**
 * Merges stored settings over the defaults and migrates the old list filters:
 * the chips Unread only / Saved / Today / Short reads / Read became Show (Unread, All, Saved)
 * plus a Today chip, and Group by Status went away.
 */
export function normalizeSettings(stored: Record<string, unknown> | undefined): Settings {
  const { filters, ...rest } = (stored ?? {}) as Partial<Settings> & { filters?: unknown }
  const s: Settings = { ...DEFAULT_SETTINGS, ...rest }
  if (Array.isArray(filters) && !(stored && 'show' in stored)) {
    const old = filters as string[]
    s.show = old.includes('saved') ? 'saved' : old.includes('unread') && !old.includes('read') ? 'unread' : 'all'
    s.today = old.includes('today')
  }
  if (!SHOWS.includes(s.show)) s.show = DEFAULT_SETTINGS.show
  if (!GROUPINGS.includes(s.grouping)) s.grouping = 'date'
  s.today = Boolean(s.today)
  return s
}

export function useSettings(): Settings {
  const stored = useLiveQuery(() => db.kv.get(KEY), [])
  return normalizeSettings(stored?.value as Record<string, unknown> | undefined)
}

export async function getSettings(): Promise<Settings> {
  const stored = await db.kv.get(KEY)
  return normalizeSettings(stored?.value as Record<string, unknown> | undefined)
}

export async function updateSettings(patch: Partial<Settings>): Promise<void> {
  const current = await getSettings()
  await setKv(KEY, { ...current, ...patch })
}
