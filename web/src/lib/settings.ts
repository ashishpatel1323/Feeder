import type { HomeGroup, HomeLayout, HomeSize, HomeSort } from './home'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, setKv } from './db'

export type Grouping = 'none' | 'date' | 'feed' | 'tag' | 'length' | 'site'
export type Sorting = 'newest' | 'oldest' | 'shortest' | 'longest'
/** The Show tabs above the list: a single choice. */
export type ShowFilter = 'unread' | 'all' | 'saved' | 'downloaded'

export interface Settings {
  grouping: Grouping
  sorting: Sorting
  show: ShowFilter
  /** Today chip: only items from today. */
  today: boolean
  collapsedGroups: string[]
  snippetLines: number
  showThumbnails: boolean
  markReadOnOpen: boolean
  refreshMinutes: number
  theme: 'system' | 'light' | 'dark' | 'paper'
  textSize: number
  /** Bold the start of every word in the reader ("fast reading"). */
  fastReading: boolean
  readAloudRate: number
  readAloudVoice: string
  readAloudAutoContinue: boolean
  /** Speed reading in the player: show the current word big, with a focus letter. */
  readAloudShowWord: boolean
  /** Run the natural (Kokoro) voice on the GPU where WebGPU exists: faster, bigger download. */
  /** Replaces readAloudKokoroGpu (off by default), so saved settings pick up the new default. */
  readAloudKokoroUseGpu: boolean
  /** Ebook chapters after the one being read aloud to speak ahead with the natural voice. */
  bookAudioAhead: number
  /** Delete a chapter's prepared audio once reading moves past it. */
  bookAudioDeletePlayed: boolean
  /** Mac app: speak the phone's next ebook chapters with the natural voice into Dropbox (macBookAudio.ts). */
  macBookAudio: boolean
  /** Audiobook transcripts: chapters to transcribe ahead of where the book plays (WHOLE_BOOK = 1000: all) */
  transcribeAhead: number
  summaryProvider: 'none' | 'openai'
  /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1 or http://localhost:11434/v1 for Ollama. */
  summaryBaseUrl: string
  summaryApiKey: string
  summaryModel: string
  /** Offline mode: use only what is saved here, no network (offline.ts), even with internet. */
  offlineMode: boolean
  /** Home page (feed tiles): how tiles are grouped, sorted and sized. */
  homeGroup: HomeGroup
  homeSort: HomeSort
  homeSize: HomeSize
  homeLayout: HomeLayout
  /** Folders folded in the Home list view. */
  homeFolded: string[]
}

export const DEFAULT_SETTINGS: Settings = {
  grouping: 'none',
  sorting: 'newest',
  show: 'unread',
  today: false,
  collapsedGroups: [],
  snippetLines: 2,
  showThumbnails: true,
  markReadOnOpen: true,
  refreshMinutes: 30,
  theme: 'system',
  textSize: 18,
  fastReading: false,
  readAloudRate: 1,
  readAloudVoice: '',
  readAloudAutoContinue: true,
  readAloudShowWord: false,
  // On by default; Kokoro falls back to the processor where WebGPU is missing
  readAloudKokoroUseGpu: true,
  bookAudioAhead: 3,
  bookAudioDeletePlayed: true,
  macBookAudio: true,
  transcribeAhead: 1,
  summaryProvider: 'none',
  summaryBaseUrl: 'http://localhost:11434/v1',
  summaryApiKey: '',
  summaryModel: 'llama3.2',
  offlineMode: false,
  homeGroup: 'folders',
  homeSort: 'latest',
  homeSize: 'm',
  homeLayout: 'tiles',
  homeFolded: [],
}

const KEY = 'settings'

const GROUPINGS: Grouping[] = ['none', 'date', 'feed', 'tag', 'length', 'site']
const SHOWS: ShowFilter[] = ['unread', 'all', 'saved', 'downloaded']

/**
 * Merges stored settings over the defaults and migrates the old list filters:
 * the chips Unread only / Saved / Today / Short reads / Read became Show (Unread, All, Saved)
 * plus a Today chip, and Group by Status went away.
 */
export function normalizeSettings(stored: Record<string, unknown> | undefined): Settings {
  const { filters, readAloudKokoroGpu: _oldGpu, ...rest } = (stored ?? {}) as Partial<Settings> & { filters?: unknown; readAloudKokoroGpu?: unknown }
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

/** No connection, or Offline mode switched on: nothing should go to the network. */
export async function offlineNow(): Promise<boolean> {
  if (typeof navigator !== 'undefined' && !navigator.onLine) return true
  return (await getSettings()).offlineMode
}

export async function updateSettings(patch: Partial<Settings>): Promise<void> {
  const current = await getSettings()
  await setKv(KEY, { ...current, ...patch })
}
