import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, FEED_ORDER_KEY, feedDisplayTitle, getKv, hideItems, moveFeedToTag, opensInBrowser, placeInOrder, setKv, unhideItems, type Feed, type Item } from './lib/db'
import type { ToastAction } from './lib/toast'
import { applyShow, applyToday, buildGroups, selectItems, showCounts, type Selection } from './lib/listquery'
import { getSettings, offlineNow, updateSettings, useSettings } from './lib/settings'
import { addFeed, refreshFeeds } from './lib/refresh'
import { addSharedLink, findLink, SHARED_FEED, SHARED_FEED_URL, undoSharedLink } from './lib/share'
import { getSyncState, syncNow } from './lib/sync2'
import { PLAYLISTS_KEY } from './lib/playlists'
import { fetchOgImages } from './lib/extras'
import { readAloud } from './lib/readaloud'
import { cleanupOffline, isOnline, setForcedOffline } from './lib/offline'
import { Sidebar } from './components/Sidebar'
import { ArticleList } from './components/ArticleList'
import { Reader } from './components/Reader'
import { FlipMode } from './components/FlipMode'
import { DownloadsPage } from './components/Downloads'
import { AddFeedDialog, EditFeedDialog, SettingsDialog } from './components/Dialogs'
import { ManageFeeds } from './components/ManageFeeds'
import { FeedPage } from './components/FeedPage'
import { HighlightsPage } from './components/Highlights'
import { HomePage } from './components/Home'
import { DiscoverPage } from './components/Discover'
import { PlaylistsPage } from './components/Playlists'
import { QueuePlayer } from './components/QueuePlayer'
import { AddToPlaylistMenu } from './components/AddToPlaylist'
import { playQueue, useQueue } from './lib/playqueue'
import { combinedSubject, ShareDialog, type ShareSubject } from './components/ShareDialog'
import { articlePicture, deleteHighlight } from './lib/highlights'
import type { Highlight } from './lib/db'
import { CommandPalette, SearchView, useSearchData, type OpenOptions, type PaletteAction } from './components/Search'
import { queryTerms, type SearchHit, type SearchScope } from './lib/search'
import { RESUME_BACK, saveVideoPos } from './lib/videopos'
import { kindOf } from './lib/playlists'
import { ZimPage, type ZimJump } from './components/ZimPage'
import { listBooks as listZimBooks, rememberPath as rememberZimPath } from './lib/zim/library'
import { parseZimItemId } from './lib/zim/marks'
import { EbooksPage } from './components/EbooksPage'
import { AudiobooksPage, AudiobookBar } from './components/AudiobooksPage'
import { useAudiobookPlayer } from './lib/audiobooks/player'
import { bookIdOfItem } from './lib/ebooks/library'
import { DROPBOX_TS_KEY } from './lib/dropbox'
import { feedsViewIsLatest, FeedsSwitch, type FeedsView, LibraryPage, rememberFeedsView, TabBar, useTabShortcuts, type Tab } from './components/Tabs'
import { useVideoJobs, useVideoSavingAvailable } from './lib/offline'
import { createItemReuser } from './lib/itemreuse'

const NAV_COLLAPSED_KEY = 'feeder.navCollapsed'
const NO_ITEMS: Item[] = []
/** The Manage feeds page has its own address so it can be bookmarked and Back works. */
const FEEDS_HASH = '#/feeds'
const isFeedsHash = () => window.location.hash === FEEDS_HASH
/** The Highlights page, also bookmarkable. */
const HIGHLIGHTS_HASH = '#/highlights'
const isHighlightsHash = () => window.location.hash === HIGHLIGHTS_HASH
/** Home: every feed as a tile. It is the start page, so an address with no page in it opens Home. */
const HOME_HASH = '#/home'
const isHomeHash = () => window.location.hash === HOME_HASH
/** Discover: find new feeds, podcasts and YouTube channels. */
const DISCOVER_HASH = '#/discover'
const LIBRARY_HASH = '#/library'
const isLibraryHash = () => window.location.hash === LIBRARY_HASH
const isDiscoverHash = () => window.location.hash === DISCOVER_HASH
/** ZIM books: #/zim for the library, #/zim/<book> to read one. */
const ZIM_HASH = '#/zim'
const zimFromHash = (): string | null => {
  const h = window.location.hash
  if (h === ZIM_HASH) return ''
  if (!h.startsWith(ZIM_HASH + '/')) return null
  try {
    return decodeURIComponent(h.slice(ZIM_HASH.length + 1))
  } catch {
    return ''
  }
}
/** Ebooks: #/books for the library, #/books/<id> to read one, #/books/<id>@<cfi> at a place. */
const BOOKS_HASH = '#/books'
const bookFromHash = (): { id: string; at: string | null } | null => {
  const h = window.location.hash
  if (h === BOOKS_HASH) return { id: '', at: null }
  if (!h.startsWith(BOOKS_HASH + '/')) return null
  try {
    const rest = decodeURIComponent(h.slice(BOOKS_HASH.length + 1))
    const cut = rest.indexOf('@')
    return cut < 0 ? { id: rest, at: null } : { id: rest.slice(0, cut), at: rest.slice(cut + 1) || null }
  } catch {
    return { id: '', at: null }
  }
}
/** Audiobooks: #/audiobooks for the library, #/audiobooks/<id> for one book's player. */
const AUDIOBOOKS_HASH = '#/audiobooks'
const audiobookFromHash = (): string | null => {
  const h = window.location.hash
  if (h === AUDIOBOOKS_HASH) return ''
  if (!h.startsWith(AUDIOBOOKS_HASH + '/')) return null
  try {
    return decodeURIComponent(h.slice(AUDIOBOOKS_HASH.length + 1))
  } catch {
    return ''
  }
}
const DOWNLOADS_HASH = '#/downloads'
const isDownloadsHash = () => window.location.hash === DOWNLOADS_HASH
/** Playlists: #/playlists for all of them, #/playlist/<id> for one. */
const PLAYLISTS_HASH = '#/playlists'
const PLAYLIST_HASH = '#/playlist/'
const playlistFromHash = (): string | null => {
  const h = window.location.hash
  if (h === PLAYLISTS_HASH) return ''
  if (!h.startsWith(PLAYLIST_HASH)) return null
  try {
    return decodeURIComponent(h.slice(PLAYLIST_HASH.length))
  } catch {
    return ''
  }
}
/** One feed's own page (stats and settings), opened by clicking a feed name: #/feed/<encoded url>. */
const FEED_HASH = '#/feed/'
const feedFromHash = (): string | null => {
  const h = window.location.hash
  if (!h.startsWith(FEED_HASH)) return null
  try {
    return decodeURIComponent(h.slice(FEED_HASH.length)) || null
  } catch {
    return null
  }
}

type Dialog = { kind: 'settings' } | { kind: 'add' } | { kind: 'edit'; url: string } | null

function selectionTitle(s: Selection, feeds: Map<string, Feed>): string {
  switch (s.kind) {
    case 'all':
      return 'Latest'
    case 'saved':
      return 'Saved'
    case 'tag':
      return s.tag
    case 'feed':
      return feedDisplayTitle(feeds.get(s.url)) || 'Feed'
  }
}

export default function App() {
  const settings = useSettings()
  const feedList = useLiveQuery(() => db.feeds.toArray(), []) ?? []
  // One copy of each article across re-reads: the list is read again on every change (itemreuse.ts)
  const [reuseItems] = useState(createItemReuser)
  const items = useLiveQuery(() => db.items.toArray().then(reuseItems), []) ?? NO_ITEMS
  const syncState = useLiveQuery(() => getSyncState(), [])
  // Shared links belong to a built-in source that is not a real feed (lib/share.ts)
  const feeds = useMemo(() => new Map([...feedList.map((f) => [f.url, f] as const), [SHARED_FEED_URL, SHARED_FEED] as const]), [feedList])

  const [selection, setSelection] = useState<Selection>({ kind: 'all' })
  const [openId, setOpenId] = useState<string | null>(null)
  // The list order when an article was opened, so next/previous and swipes follow it (#19)
  const [snapshot, setSnapshot] = useState<string[]>([])
  const [pane, setPane] = useState<'list' | 'reader'>('list')
  const [drawer, setDrawer] = useState(false)
  // Desktop only: shrink the feed sidebar to a slim icon rail. Remembered per browser.
  const [navCollapsed, setNavCollapsed] = useState(() => {
    try {
      return localStorage.getItem(NAV_COLLAPSED_KEY) === '1'
    } catch {
      return false
    }
  })
  const toggleNav = useCallback(() => {
    setNavCollapsed((c) => {
      try {
        localStorage.setItem(NAV_COLLAPSED_KEY, c ? '0' : '1')
      } catch {
        /* private mode */
      }
      return !c
    })
  }, [])
  const [dialog, setDialog] = useState<Dialog>(null)
  const [managing, setManaging] = useState(isFeedsHash)
  const [highlightsPage, setHighlightsPage] = useState(isHighlightsHash)
  const [downloadsPage, setDownloadsPage] = useState(isDownloadsHash)
  // A fresh start opens the Feeds tab on the view used last: the tiles (Home) or Latest
  const [homePage, setHomePage] = useState(() => isHomeHash() || (!window.location.hash && !feedsViewIsLatest()))
  const [discoverPage, setDiscoverPage] = useState(isDiscoverHash)
  const [libraryPage, setLibraryPage] = useState(isLibraryHash)
  /** null = not on the ZIM tab; '' = the ZIM library; else the book being read. */
  const [zimPage, setZimPage] = useState<string | null>(zimFromHash)
  const [zimJump, setZimJump] = useState<ZimJump | null>(null)
  /** null = not on the Ebooks tab; id '' = the library; else the book being read. */
  const [bookPage, setBookPage] = useState<{ id: string; at: string | null } | null>(bookFromHash)
  /** null = not on the Audiobooks tab; '' = the library; else the book whose player is open. */
  const [audiobookPage, setAudiobookPage] = useState<string | null>(audiobookFromHash)
  // Start on Home: give it its own address, so Back from the article list returns to it
  useEffect(() => {
    if (!window.location.hash && !feedsViewIsLatest()) history.replaceState(null, '', window.location.pathname + window.location.search + HOME_HASH)
  }, [])
  const [feedPage, setFeedPage] = useState<string | null>(feedFromHash)
  /** null = not on Playlists; '' = all playlists; else one playlist's id. */
  const [playlistPage, setPlaylistPage] = useState<string | null>(playlistFromHash)
  const queue = useQueue()
  const audiobook = useAudiobookPlayer()
  // Whether the feed page was opened from inside the app, so its Back button can go back there
  const feedPageFromApp = useRef(false)
  const [focusHighlight, setFocusHighlight] = useState<string | null>(null)
  // Global search: the ⌘K palette, the results view in the list pane, and what to mark in the reader
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [searchView, setSearchView] = useState<{ query: string; scope: SearchScope } | null>(null)
  const [searchOrder, setSearchOrder] = useState<string[]>([])
  const [searchFocus, setSearchFocus] = useState<{ terms: string[]; query: string; at: Record<string, number | null> } | null>(null)
  const [pageShare, setPageShare] = useState<ShareSubject | null>(null)
  const allHighlights = useLiveQuery(() => db.highlights.toArray(), []) ?? []
  useEffect(() => {
    const onHash = () => {
      setManaging(isFeedsHash())
      setHighlightsPage(isHighlightsHash())
      setDownloadsPage(isDownloadsHash())
      setFeedPage(feedFromHash())
      setHomePage(isHomeHash())
      setDiscoverPage(isDiscoverHash())
      setLibraryPage(isLibraryHash())
      setZimPage(zimFromHash())
      setBookPage(bookFromHash())
      setAudiobookPage(audiobookFromHash())
      setPlaylistPage(playlistFromHash())
    }
    window.addEventListener('hashchange', onHash)
    window.addEventListener('popstate', onHash)
    return () => {
      window.removeEventListener('hashchange', onHash)
      window.removeEventListener('popstate', onHash)
    }
  }, [])
  const showManage = useCallback((on: boolean) => {
    if (on !== isFeedsHash()) {
      if (on) window.location.hash = FEEDS_HASH
      else if (isFeedsHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setManaging(on)
    if (on) setHomePage(false)
    if (on) setHighlightsPage(false)
    if (on) setDownloadsPage(false)
    if (on) {
      setFeedPage(null)
      feedPageFromApp.current = false
    }
    if (on) setPlaylistPage(null)
    if (on) setDiscoverPage(false)
    if (on) setLibraryPage(false)
    if (on) setZimPage(null)
    if (on) setBookPage(null)
    if (on) setAudiobookPage(null)
  }, [])
  const showHighlights = useCallback((on: boolean) => {
    if (on !== isHighlightsHash()) {
      if (on) window.location.hash = HIGHLIGHTS_HASH
      else if (isHighlightsHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setHighlightsPage(on)
    if (on) setHomePage(false)
    if (on) setManaging(false)
    if (on) setDownloadsPage(false)
    if (on) {
      setFeedPage(null)
      feedPageFromApp.current = false
    }
    if (on) setPlaylistPage(null)
    if (on) setDiscoverPage(false)
    if (on) setLibraryPage(false)
    if (on) setZimPage(null)
    if (on) setBookPage(null)
    if (on) setAudiobookPage(null)
  }, [])
  const showDownloads = useCallback((on: boolean) => {
    if (on !== isDownloadsHash()) {
      if (on) window.location.hash = DOWNLOADS_HASH
      else if (isDownloadsHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setDownloadsPage(on)
    if (on) {
      setHomePage(false)
      setManaging(false)
      setHighlightsPage(false)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
    if (on) setPlaylistPage(null)
    if (on) setDiscoverPage(false)
    if (on) setLibraryPage(false)
    if (on) setZimPage(null)
    if (on) setBookPage(null)
    if (on) setAudiobookPage(null)
  }, [])
  const showHome = useCallback((on: boolean) => {
    if (on !== isHomeHash()) {
      if (on) window.location.hash = HOME_HASH
      else if (isHomeHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setHomePage(on)
    if (on) {
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
    if (on) setPlaylistPage(null)
    if (on) setDiscoverPage(false)
    if (on) setLibraryPage(false)
    if (on) setZimPage(null)
    if (on) setBookPage(null)
    if (on) setAudiobookPage(null)
  }, [])
  const showDiscover = useCallback((on: boolean) => {
    if (on !== isDiscoverHash()) {
      if (on) window.location.hash = DISCOVER_HASH
      else if (isDiscoverHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setDiscoverPage(on)
    if (on) {
      setHomePage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setFeedPage(null)
      feedPageFromApp.current = false
      setPlaylistPage(null)
      setLibraryPage(false)
      setZimPage(null)
      setBookPage(null)
      setAudiobookPage(null)
    }
  }, [])
  const showLibrary = useCallback((on: boolean) => {
    if (on !== isLibraryHash()) {
      if (on) window.location.hash = LIBRARY_HASH
      else if (isLibraryHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setLibraryPage(on)
    if (on) {
      setZimPage(null)
      setBookPage(null)
      setAudiobookPage(null)
      setHomePage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setDiscoverPage(false)
      setFeedPage(null)
      feedPageFromApp.current = false
      setPlaylistPage(null)
    }
  }, [])
  /** Opens the ZIM library ('') or one book; null leaves it. */
  const showZim = useCallback((id: string | null) => {
    const want = id === null ? null : id ? `${ZIM_HASH}/${encodeURIComponent(id)}` : ZIM_HASH
    if (want && window.location.hash !== want) window.location.hash = want
    else if (!want && zimFromHash() !== null) history.pushState(null, '', window.location.pathname + window.location.search)
    setZimPage(id)
    if (id !== null) {
      setBookPage(null)
      setAudiobookPage(null)
      setHomePage(false)
      setDiscoverPage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setLibraryPage(false)
      setPlaylistPage(null)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
  }, [])
  /** Opens the Ebooks library ('') or one book, optionally at a CFI; null leaves it. */
  const showBooks = useCallback((id: string | null, at?: string | null) => {
    const want = id === null ? null : id ? `${BOOKS_HASH}/${encodeURIComponent(id + (at ? `@${at}` : ''))}` : BOOKS_HASH
    if (want && window.location.hash !== want) window.location.hash = want
    else if (!want && bookFromHash() !== null) history.pushState(null, '', window.location.pathname + window.location.search)
    setBookPage(id === null ? null : { id, at: at ?? null })
    if (id !== null) {
      setZimPage(null)
      setAudiobookPage(null)
      setHomePage(false)
      setDiscoverPage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setLibraryPage(false)
      setPlaylistPage(null)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
  }, [])
  /** Opens the Audiobooks library ('') or one book's player; null leaves it. */
  const showAudiobooks = useCallback((id: string | null) => {
    const want = id === null ? null : id ? `${AUDIOBOOKS_HASH}/${encodeURIComponent(id)}` : AUDIOBOOKS_HASH
    if (want && window.location.hash !== want) window.location.hash = want
    else if (!want && audiobookFromHash() !== null) history.pushState(null, '', window.location.pathname + window.location.search)
    setAudiobookPage(id)
    if (id !== null) {
      setBookPage(null)
      setZimPage(null)
      setHomePage(false)
      setDiscoverPage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setLibraryPage(false)
      setPlaylistPage(null)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
  }, [])
  /** Opens Playlists ('' = all of them) or one playlist; null leaves it. */
  const showPlaylists = useCallback((id: string | null) => {
    const want = id === null ? null : id ? PLAYLIST_HASH + encodeURIComponent(id) : PLAYLISTS_HASH
    if (want && window.location.hash !== want) window.location.hash = want
    else if (!want && playlistFromHash() !== null) history.pushState(null, '', window.location.pathname + window.location.search)
    setPlaylistPage(id)
    if (id !== null) {
      setHomePage(false)
      setDiscoverPage(false)
      setManaging(false)
      setHighlightsPage(false)
      setDownloadsPage(false)
      setLibraryPage(false)
      setZimPage(null)
      setBookPage(null)
      setAudiobookPage(null)
      setFeedPage(null)
      feedPageFromApp.current = false
    }
  }, [])
  const openFeedPage = useCallback((url: string) => {
    // Shared links have no feed page: show them in Saved
    if (url === SHARED_FEED_URL) {
      setSelection({ kind: 'saved' })
      setPane('list')
      return
    }
    feedPageFromApp.current = true
    window.location.hash = FEED_HASH + encodeURIComponent(url)
    setFeedPage(url)
    setManaging(false)
    setDiscoverPage(false)
    setHighlightsPage(false)
    setPlaylistPage(null)
    setLibraryPage(false)
    setZimPage(null)
    setBookPage(null)
    setAudiobookPage(null)
  }, [])
  /** Leaves the feed page for the article list. */
  const leaveFeedPage = useCallback(() => {
    feedPageFromApp.current = false
    if (feedFromHash()) history.pushState(null, '', window.location.pathname + window.location.search)
    setFeedPage(null)
  }, [])
  /** Back: to wherever the feed page was opened from (Manage feeds, the reader…). */
  const closeFeedPage = useCallback(() => {
    if (feedPageFromApp.current) {
      feedPageFromApp.current = false
      history.back()
      return
    }
    leaveFeedPage()
  }, [leaveFeedPage])
  const [refreshing, setRefreshing] = useState<string | null>(null)
  const [toastMsg, setToastMsg] = useState<{ msg: string; action?: ToastAction } | null>(null)
  const [autoplay, setAutoplay] = useState(false)
  // Flip mode: the list order and the article to start on, while it's open
  const [flip, setFlip] = useState<{ queue: string[]; start: string | null } | null>(null)
  const toastTimer = useRef<number | null>(null)

  const toast = useCallback((msg: string, action?: ToastAction) => {
    setToastMsg({ msg, action })
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToastMsg(null), action ? 7000 : 4000)
  }, [])

  // A Dropbox sign-in that failed on the way back from Dropbox's page
  useEffect(() => {
    const err = sessionStorage.getItem('feeder.dropboxError')
    if (err) {
      sessionStorage.removeItem('feeder.dropboxError')
      toast(`Dropbox: ${err}`)
    }
  }, [toast])

  // Theme
  useEffect(() => {
    const root = document.documentElement
    if (settings.theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', settings.theme)
  }, [settings.theme])

  useEffect(() => {
    readAloud.setVoice(settings.readAloudVoice)
    readAloud.kokoroGpu = settings.readAloudKokoroUseGpu
    if (readAloud.state.rate !== settings.readAloudRate && readAloud.state.status === 'idle') readAloud.setRate(settings.readAloudRate)
  }, [settings.readAloudVoice, settings.readAloudRate, settings.readAloudKokoroUseGpu])

  const [groups, counts] = useMemo(() => {
    // Keep the open article in the list even after it's marked read
    const keep = openId ?? undefined
    const base = applyToday(selectItems(items, feeds, selection), settings.today, keep)
    const filtered = applyShow(base, settings.show, keep)
    return [buildGroups(filtered, feeds, settings.grouping, settings.sorting), showCounts(base)] as const
  }, [items, feeds, selection, settings.show, settings.today, settings.grouping, settings.sorting, openId])

  const visibleOrder = useMemo(() => {
    const collapsed = new Set(settings.collapsedGroups)
    return groups.filter((g) => settings.grouping === 'none' || !collapsed.has(`${settings.grouping}:${g.key}`)).flatMap((g) => g.items.map((i) => i.id))
  }, [groups, settings.collapsedGroups, settings.grouping])

  const openItem = items.find((i) => i.id === openId) ?? null
  const pos = openId ? snapshot.indexOf(openId) : -1
  const prevId = pos > 0 ? snapshot[pos - 1] : null
  const nextId = pos >= 0 && pos < snapshot.length - 1 ? snapshot[pos + 1] : null
  const nextTitle = nextId ? (items.find((i) => i.id === nextId)?.title ?? null) : null

  const open = useCallback(
    (id: string, order?: string[]) => {
      setOpenId(id)
      if (order) setSnapshot(order)
      setPane('reader')
      if (settings.markReadOnOpen) {
        db.items.get(id).then((it) => {
          if (it && !it.read) db.items.update(id, { read: 1, readTime: Date.now(), readSynced: 0 })
        })
      }
    },
    [settings.markReadOnOpen],
  )

  // The playlist the open article came from: Back in the reader returns to it, not to the list
  const [readerFrom, setReaderFrom] = useState<string | null>(null)
  const leaveReader = useCallback(() => {
    setPane('list')
    if (readerFrom) {
      setReaderFrom(null)
      showPlaylists(readerFrom)
    }
  }, [readerFrom, showPlaylists])

  const goPrev = useCallback(() => prevId && open(prevId), [prevId, open])
  const goNext = useCallback(() => nextId && open(nextId), [nextId, open])

  const exitFlip = useCallback(() => setFlip(null), [])
  const startFlip = useCallback(() => {
    if (!visibleOrder.length) return toast('No articles to flip through here')
    setFlip({ queue: visibleOrder, start: openId && visibleOrder.includes(openId) ? openId : visibleOrder[0] })
  }, [visibleOrder, openId, toast])

  useEffect(() => {
    readAloud.onError = toast
    playQueue.onMessage = toast
  }, [toast])

  // Read aloud: auto-continue and media keys
  useEffect(() => {
    // Flip mode turns its own pages when reading ends
    if (flip) return
    // A playlist is playing: it decides what comes next, also for the media keys
    if (queue.kind) {
      readAloud.onFinished = (finishedId) => void playQueue.articleFinished(finishedId)
      readAloud.onMediaNext = () => void playQueue.next()
      readAloud.onMediaPrevious = () => playQueue.prev()
      return
    }
    readAloud.onFinished = (finishedId) => {
      if (!settings.readAloudAutoContinue) return
      const i = snapshot.indexOf(finishedId)
      const next = i >= 0 ? snapshot[i + 1] : undefined
      if (next) {
        setAutoplay(true)
        open(next)
      }
    }
    const wasPlaying = () => readAloud.state.status === 'playing'
    readAloud.onMediaNext = nextId
      ? () => {
          setAutoplay(wasPlaying())
          open(nextId)
        }
      : null
    readAloud.onMediaPrevious = prevId
      ? () => {
          setAutoplay(wasPlaying())
          open(prevId)
        }
      : null
  }, [snapshot, settings.readAloudAutoContinue, open, nextId, prevId, flip, queue.kind])

  // Sync and refresh
  // Kept the same function across renders: it changed with every progress step of a refresh,
  // and each new copy held on to the render before it (and that render's articles)
  const refreshingNow = useRef(false)
  const fullRefresh = useCallback(async () => {
    // Offline or Offline mode: nothing to fetch; read marks wait and go out later
    if (refreshingNow.current || (await offlineNow())) return
    refreshingNow.current = true
    try {
      setRefreshing('sync')
      await syncNow().catch((e) => toast(`Sync: ${e instanceof Error ? e.message : e}`))
      const added = await refreshFeeds(undefined, (pr) => setRefreshing(`${pr.done}/${pr.total}`))
      setRefreshing('sync')
      await syncNow().catch(() => undefined)
      if (added) toast(`${added} new article${added === 1 ? '' : 's'}`)
      const recent = await db.items.where('sortTime').above(Date.now() - 3 * 86_400_000).toArray()
      const ogFeeds = new Set((await db.feeds.toArray()).filter((f) => f.fetchOgImages).map((f) => f.url))
      if (ogFeeds.size) fetchOgImages(recent.filter((i) => ogFeeds.has(i.feedUrl)), 40).catch(() => undefined)
    } finally {
      refreshingNow.current = false
      setRefreshing(null)
    }
  }, [toast])

  const fullRefreshRef = useRef(fullRefresh)
  fullRefreshRef.current = fullRefresh

  useEffect(() => {
    fullRefreshRef.current()
    const minutes = settings.refreshMinutes
    const timer = minutes > 0 ? window.setInterval(() => fullRefreshRef.current(), minutes * 60_000) : null
    let hiddenAt = 0
    const onVis = () => {
      if (document.hidden) hiddenAt = Date.now()
      else if (hiddenAt && Date.now() - hiddenAt > 5 * 60_000) fullRefreshRef.current()
    }
    document.addEventListener('visibilitychange', onVis)
    return () => {
      if (timer) clearInterval(timer)
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [settings.refreshMinutes])

  // Offline mode switched off: send what changed meanwhile, then fetch new articles
  const offlineMode = settings.offlineMode
  const wasOffline = useRef(false)
  useEffect(() => {
    setForcedOffline(offlineMode)
    if (wasOffline.current && !offlineMode) void syncNow().catch(() => undefined).then(() => fullRefreshRef.current())
    wasOffline.current = offlineMode
  }, [offlineMode])

  // Back online: send what changed while offline, then fetch new articles
  useEffect(() => {
    const onOnline = () => {
      if (isOnline()) void syncNow().catch(() => undefined).then(() => fullRefreshRef.current())
    }
    window.addEventListener('online', onOnline)
    void cleanupOffline().catch(() => undefined)
    return () => window.removeEventListener('online', onOnline)
  }, [])

  // Send read marks, saves and deletions shortly after they change
  const deletedCount = useLiveQuery(() => db.hidden.count(), []) ?? 0
  const pendingMarks = items.filter((i) => !i.readSynced).length + deletedCount
  useEffect(() => {
    if (!pendingMarks || !syncState || !isOnline()) return
    const t = window.setTimeout(() => syncNow().catch(() => undefined), 5000)
    return () => clearTimeout(t)
  }, [pendingMarks, syncState])

  // Playlists, highlights and where episodes and videos were left: sent a few seconds after they change (so
  // pausing sends the spot right away), and at least every minute while something keeps playing,
  // so another device can carry on from here
  const playbackSig = useLiveQuery(async () => {
    const lists = await db.kv.get(PLAYLISTS_KEY)
    let last = 0
    await db.videoPos.each((p) => void (last = Math.max(last, p.ts)))
    // Highlights too: new, recolored, noted or deleted
    let hl = 0
    await db.highlights.each((h) => void (hl = Math.max(hl, h.modifiedAt || h.createdAt)))
    // Ebook positions, and the Dropbox sign-in (signing in or out here)
    let book = 0
    await db.bookPos.each((p) => void (book = Math.max(book, p.ts)))
    const dropboxTs = (await db.kv.get(DROPBOX_TS_KEY))?.value ?? 0
    // Audiobook positions, and history events not sent yet
    let audio = 0
    await db.audioPos.each((p) => void (audio = Math.max(audio, p.ts)))
    const history = await db.history.where('synced').equals(0).count()
    return `${last}|${hl}|${await db.highlights.count()}|${JSON.stringify(lists?.value ?? null)}|${book}|${dropboxTs}|${audio}|${history}`
  }, [])
  const firstUnsent = useRef(0)
  useEffect(() => {
    if (!playbackSig || !syncState || !isOnline()) return
    const now = Date.now()
    if (!firstUnsent.current) firstUnsent.current = now
    const send = () => {
      firstUnsent.current = 0
      void syncNow().catch(() => undefined)
    }
    if (now - firstUnsent.current > 60_000) return send()
    const t = window.setTimeout(send, 4000)
    return () => clearTimeout(t)
  }, [playbackSig, syncState])

  // Back to this window: pick up what other devices did meanwhile (where an episode was left,
  // playlist edits), without waiting for the next refresh
  useEffect(() => {
    let awaySince = 0
    const away = () => void (awaySince = awaySince || Date.now())
    const back = () => {
      if (awaySince && Date.now() - awaySince > 15_000 && isOnline()) void syncNow().catch(() => undefined)
      awaySince = 0
    }
    const onVis = () => (document.hidden ? away() : back())
    document.addEventListener('visibilitychange', onVis)
    window.addEventListener('blur', away)
    window.addEventListener('focus', back)
    return () => {
      document.removeEventListener('visibilitychange', onVis)
      window.removeEventListener('blur', away)
      window.removeEventListener('focus', back)
    }
  }, [])

  // og:images for the visible list
  useEffect(() => {
    const visible = groups.flatMap((g) => g.items).slice(0, 30).filter((i) => feeds.get(i.feedUrl)?.fetchOgImages)
    if (visible.length && isOnline()) fetchOgImages(visible).catch(() => undefined)
  }, [groups, feeds, offlineMode])

  // Keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (dialog || e.ctrlKey || e.metaKey || e.altKey || /INPUT|TEXTAREA|SELECT/.test(t.tagName)) return
      // Any other modal (e.g. the Sort & group sheet) owns the keyboard while open
      if (document.querySelector('[aria-modal="true"]') || t.closest?.('[aria-modal="true"]')) return
      if (playlistPage !== null) {
        if (e.key === 'Escape' && playlistPage) showPlaylists('')
        return
      }
      if (homePage && !managing && !highlightsPage && !feedPage && !downloadsPage) return
      if (managing || highlightsPage || feedPage || downloadsPage) {
        if (e.key === 'Escape') {
          if (feedPage) closeFeedPage()
          showManage(false)
          showHighlights(false)
          showDownloads(false)
          e.preventDefault()
        }
        return
      }
      const order = searchView ? searchOrder : visibleOrder
      const startFromList = () => {
        const i = openId ? order.indexOf(openId) : -1
        return i
      }
      switch (e.key) {
        case 'j':
        case 'ArrowRight':
          if (nextId) goNext()
          else if (!openId && order.length) open(order[0], order)
          else {
            const i = startFromList()
            if (i >= 0 && i < order.length - 1) open(order[i + 1], order)
          }
          break
        case 'k':
        case 'ArrowLeft':
          if (prevId) goPrev()
          break
        case 's':
          if (openItem) db.items.update(openItem.id, { saved: openItem.saved ? 0 : 1, readSynced: 0 })
          break
        case 'm':
          if (openItem) db.items.update(openItem.id, { read: openItem.read ? 0 : 1, readTime: openItem.read ? null : Date.now(), readSynced: 0 })
          break
        case 'o':
          if (openItem?.link) window.open(openItem.link, '_blank', 'noopener')
          break
        case 'b':
          void getSettings().then((s) => updateSettings({ fastReading: !s.fastReading }))
          break
        case 'r':
          if (openItem) readAloud.state.status === 'idle' ? readAloud.play() : readAloud.stop()
          break
        case '[':
          toggleNav()
          break
        case 'f':
          startFlip()
          break
        case 'Escape':
          if (pane === 'reader') leaveReader()
          else if (searchView) setSearchView(null)
          break
        default:
          return
      }
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dialog, visibleOrder, openId, nextId, prevId, goNext, goPrev, open, openItem, pane, toggleNav, managing, showManage, highlightsPage, showHighlights, downloadsPage, showDownloads, feedPage, closeFeedPage, startFlip, homePage, playlistPage, showPlaylists, leaveReader, searchView, searchOrder])

  const markAllRead = async () => {
    const ids = groups.flatMap((g) => g.items).filter((i) => !i.read).map((i) => i.id)
    if (!ids.length) return
    await db.items.bulkUpdate(ids.map((key) => ({ key, changes: { read: 1, readTime: Date.now(), readSynced: 0 } })))
    toast(`Marked ${ids.length} as read`)
  }

  /** Deletes the read articles in the list for good, on every device. Saved ones stay. */
  const deleteRead = async () => {
    const gone = groups.flatMap((g) => g.items).filter((i) => i.read && !i.saved)
    if (!gone.length) return toast('No read articles to delete (saved ones are kept)')
    if (!confirm(`Delete ${gone.length} read article${gone.length === 1 ? '' : 's'} on all your devices? Saved articles are kept.`)) return
    await hideItems(gone.map((i) => i.id))
    syncNow().catch(() => undefined)
    toast(`Deleted ${gone.length}`, { label: 'Undo', run: () => void unhideItems(gone).then(() => syncNow().catch(() => undefined)) })
  }

  /** Right-click menu and selection bar in the list: the same change as the reader's button, synced to other devices. */
  const setReadFromList = async (list: Item[], read: boolean) => {
    const now = Date.now()
    const ids = list.filter((i) => !!i.read !== read).map((i) => i.id)
    if (!ids.length) return
    await db.items.bulkUpdate(ids.map((key) => ({ key, changes: { read: read ? 1 : 0, readTime: read ? now : null, readSynced: 0 } })))
    syncNow().catch(() => undefined)
    if (ids.length > 1) toast(`Marked ${ids.length} as ${read ? 'read' : 'unread'}`)
  }

  /** Delete from the list (one article or all selected): deleted for good on every device, with Undo. */
  const deleteFromList = async (list: Item[]) => {
    if (!list.length) return
    const gone = new Set(list.map((i) => i.id))
    if (openId && gone.has(openId)) {
      const after = snapshot.slice(snapshot.indexOf(openId) + 1).find((id) => !gone.has(id))
      if (after) open(after)
      else setOpenId(null)
    }
    await hideItems([...gone])
    syncNow().catch(() => undefined)
    const msg = list.length === 1 ? 'Article deleted on all devices' : `${list.length} articles deleted on all devices`
    toast(msg, { label: 'Undo', run: () => void unhideItems(list).then(() => syncNow().catch(() => undefined)) })
  }

  /** Saves a pasted or typed link (web page or YouTube video) to Saved. True when it was added. */
  const addLink = useCallback(
    async (text: string): Promise<boolean> => {
      try {
        const r = await addSharedLink(text)
        if (r.kind === 'feed') {
          toast(`That link is a feed${r.title ? `: ${r.title}` : ''}`, {
            label: 'Subscribe',
            run: () =>
              void addFeed(r.url)
                .then(() => toast('Subscribed'))
                .catch((e) => toast(e instanceof Error ? e.message : String(e))),
          })
          return false
        }
        const name = r.item.title.length > 60 ? `${r.item.title.slice(0, 57)}…` : r.item.title
        toast(r.existed ? `Already in Saved, moved to the top: ${name}` : `Saved: ${name}`, r.existed ? undefined : { label: 'Undo', run: () => void undoSharedLink(r.item, false).then(() => syncNow().catch(() => undefined)) })
        syncNow().catch(() => undefined)
        return true
      } catch (e) {
        toast(`Could not add that link: ${e instanceof Error ? e.message : e}`)
        return false
      }
    },
    [toast],
  )

  // Pasting a link anywhere (not into a text box) saves it
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const t = e.target as HTMLElement | null
      if (t && (t.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]') || t.isContentEditable)) return
      const text = e.clipboardData?.getData('text') ?? ''
      if (!text || text.length > 2000 || !/https?:\/\//i.test(text) || !findLink(text)) return
      e.preventDefault()
      toast('Adding link to Saved…')
      void addLink(text)
    }
    window.addEventListener('paste', onPaste)
    return () => window.removeEventListener('paste', onPaste)
  }, [addLink, toast])

  /** Drag and drop in the sidebar and on the Manage feeds page. */
  const moveFeed = async (url: string, tag: string, place?: { beforeUrl: string | null; folderUrls: string[] }) => {
    if (place) {
      const order = await getKv<string[]>(FEED_ORDER_KEY, [])
      await setKv(FEED_ORDER_KEY, placeInOrder(order, place.folderUrls, url, place.beforeUrl))
    }
    if (!(await moveFeedToTag(url, tag))) return void syncNow().catch(() => undefined)
    syncNow().catch(() => undefined)
    toast(tag ? `Moved to ${tag}` : 'Moved out of its folder')
  }

  /** Clicking an article: feeds set to open in the browser go straight to the original page. */
  const openFromList = (item: Item) => {
    if (item.link && opensInBrowser(feeds.get(item.feedUrl))) {
      window.open(item.link, '_blank', 'noopener')
      if (settings.markReadOnOpen && !item.read) db.items.update(item.id, { read: 1, readTime: Date.now(), readSynced: 0 })
      return
    }
    setReaderFrom(null)
    if (!searchView) setSearchFocus(null)
    open(item.id, visibleOrder)
  }

  /** The picture for a share from the Highlights page; older highlights didn't save one from the article text. */
  const pictureFor = async (h: Highlight) => {
    if (h.imageUrl) return h.imageUrl
    const it = await db.items.get(h.itemId)
    return it ? articlePicture(it.imageUrl, it.fullHtml || it.contentHtml) : null
  }

  /** From the Highlights page: open the article and scroll to the highlight. */
  const openHighlight = async (h: Highlight) => {
    const zimAt = parseZimItemId(h.itemId)
    if (zimAt) {
      if (!(await listZimBooks().then((r) => r.books.some((b) => b.id === zimAt.book), () => false))) {
        toast('That ZIM book is not on this device')
        return
      }
      rememberZimPath(zimAt.book, zimAt.path)
      setZimJump({ path: zimAt.path, n: Date.now(), highlight: h.id })
      showZim(zimAt.book)
      return
    }
    const bookId = bookIdOfItem(h.itemId)
    if (bookId) {
      if (!(await db.books.get(bookId))) {
        toast('That book is no longer in your Ebooks')
        return
      }
      showBooks(bookId, h.cfi ?? null)
      return
    }
    if (!(await db.items.get(h.itemId))) {
      if (h.itemLink) window.open(h.itemLink, '_blank', 'noopener')
      else toast('That article is no longer in Feeder')
      return
    }
    showHighlights(false)
    setFocusHighlight(h.id)
    open(h.itemId, visibleOrder.includes(h.itemId) ? visibleOrder : [h.itemId])
  }

  /** Leaves whatever page is open (Home, Playlists, Highlights…) so the list and reader show. */
  const leavePages = () => {
    showPlaylists(null)
    showManage(false)
    showHighlights(false)
    showDownloads(false)
    showHome(false)
    showDiscover(false)
    showLibrary(false)
    showZim(null)
    showBooks(null)
    showAudiobooks(null)
    if (feedPage) leaveFeedPage()
    setFlip(null)
  }

  /** Shows one list of articles (as a sidebar entry does). */
  const selectList = (s: Selection) => {
    setSelection(s)
    setDrawer(false)
    setPane('list')
    setReaderFrom(null)
    setSearchView(null)
    showManage(false)
    showHighlights(false)
    showDownloads(false)
    showHome(false)
    showDiscover(false)
    showLibrary(false)
    showPlaylists(null)
    if (feedPage) leaveFeedPage()
  }

  const searchActive = paletteOpen || !!searchView
  const searchData = useSearchData(items, feeds, allHighlights, settings.readAloudRate, searchActive)

  // Opening another page closes the results view
  const anyPage = managing || highlightsPage || !!feedPage || downloadsPage || homePage || discoverPage || libraryPage || zimPage !== null || bookPage !== null || audiobookPage !== null || playlistPage !== null
  useEffect(() => {
    if (anyPage) setSearchView(null)
  }, [anyPage])

  /** Opens a search result: an item in the reader (marked, at its transcript line), a feed's articles, a playlist… */
  const openHit = (hit: SearchHit, o: OpenOptions) => {
    setDrawer(false)
    if (hit.type === 'item') {
      const item = hit.item
      if (item.link && opensInBrowser(feeds.get(item.feedUrl))) return void window.open(item.link, '_blank', 'noopener')
      leavePages()
      setReaderFrom(null)
      const terms = queryTerms(o.query)
      // The transcript's own search looks for one phrase: the longest word finds the line most surely
      const phrase = terms.length > 1 ? [...terms].sort((a, b) => b.length - a.length)[0] : o.query.trim()
      setSearchFocus({ terms, query: phrase, at: o.at })
      if (o.play && hit.atMs != null) {
        const name = feedDisplayTitle(feeds.get(item.feedUrl)) || 'Podcast'
        void saveVideoPos(item.id, hit.atMs / 1000 + RESUME_BACK, item.duration ?? 0).then(() => playQueue.start([item.id], { name }))
      }
      open(item.id, o.order.includes(item.id) ? o.order : [item.id])
    } else if (hit.type === 'highlight') {
      leavePages()
      setSearchFocus(null)
      void openHighlight(hit.highlight)
    } else if (hit.type === 'book') {
      leavePages()
      setSearchFocus(null)
      showBooks(hit.book.id)
    } else if (hit.type === 'zim') {
      leavePages()
      setSearchFocus(null)
      // A book that isn't open yet opens at its last page: make that this one
      rememberZimPath(hit.bookId, hit.path)
      setZimJump({ path: hit.path, n: Date.now() })
      showZim(hit.bookId)
    } else if (hit.type === 'feed') selectList({ kind: 'feed', url: hit.feed.url })
    else if (hit.type === 'folder') selectList({ kind: 'tag', tag: hit.tag })
    else {
      setSearchView(null)
      showPlaylists(hit.playlist.id)
    }
  }

  /** "Jump back in": an episode carries on in the bottom player and opens; a video opens where it was left. */
  const resumeItem = (item: Item) => {
    leavePages()
    setReaderFrom(null)
    setSearchFocus(null)
    if (kindOf(item, searchData.ctx) === 'podcast') playQueue.start([item.id], { name: feedDisplayTitle(feeds.get(item.feedUrl)) || 'Podcast' })
    open(item.id, [item.id])
  }

  const showAllResults = (query: string, scope: SearchScope) => {
    leavePages()
    setSearchView({ query, scope })
    setPane('list')
  }

  const darkNow = settings.theme === 'dark' || (settings.theme === 'system' && typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches)
  // Only things that are easy to undo: nothing here deletes or marks things read
  const paletteActions: PaletteAction[] = [
    { id: 'add', label: 'Add a feed', icon: 'add', keywords: 'subscribe new rss podcast youtube channel url', run: () => setDialog({ kind: 'add' }) },
    { id: 'refresh', label: 'Refresh all feeds', icon: 'refresh', keywords: 'update sync fetch reload new', run: () => void fullRefresh() },
    {
      id: 'discover',
      label: 'Discover new feeds',
      icon: 'explore',
      keywords: 'find browse topics podcasts channels',
      run: () => {
        if (feedPage) leaveFeedPage()
        showDiscover(true)
      },
    },
    { id: 'theme', label: darkNow ? 'Switch to light theme' : 'Switch to dark theme', icon: darkNow ? 'lightMode' : 'darkMode', keywords: 'theme dark light mode appearance night', run: () => void updateSettings({ theme: darkNow ? 'light' : 'dark' }) },
    { id: 'offline', label: settings.offlineMode ? 'Go back online' : 'Go offline', icon: 'offline', keywords: 'offline mode online airplane downloaded', run: () => void updateSettings({ offlineMode: !settings.offlineMode }) },
    { id: 'home', label: 'Go to Feeds: By feed', icon: 'gridView', keywords: 'home start tiles feeds g f', run: () => showFeedsView('byFeed') },
    { id: 'all', label: 'Go to Feeds: Latest', icon: 'viewAgenda', keywords: 'all articles unread list everything', run: () => showFeedsView('latest') },
    { id: 'audiobooks', label: 'Go to Audiobooks', icon: 'headphones', keywords: 'audiobooks listen m4b dropbox g a', run: () => openTab('audiobooks') },
    { id: 'library', label: 'Go to Library', icon: 'library', keywords: 'saved highlights downloads g l', run: () => openTab('library') },
    { id: 'saved', label: 'Go to Saved', icon: 'bookmark', keywords: 'bookmarks read later', run: () => selectList({ kind: 'saved' }) },
    { id: 'playlists', label: 'Go to Playlists', icon: 'queue', keywords: 'queue listen', run: () => showPlaylists('') },
    { id: 'highlights', label: 'Go to Highlights', icon: 'highlight', keywords: 'notes quotes', run: () => showHighlights(true) },
    { id: 'manage', label: 'Manage feeds', icon: 'tune', keywords: 'folders organize edit rename', run: () => showManage(true) },
    { id: 'settings', label: 'Settings', icon: 'settings', keywords: 'preferences options sync voice', run: () => setDialog({ kind: 'settings' }) },
  ]

  // ⌘K / Ctrl+K anywhere, and / when not typing, open the palette
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((o) => !o)
        return
      }
      if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || dialog) return
      const t = e.target as HTMLElement
      if (/INPUT|TEXTAREA|SELECT/.test(t.tagName) || t.isContentEditable || document.querySelector('[aria-modal="true"]')) return
      e.preventDefault()
      setPaletteOpen(true)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dialog])

  const onPlaylists = playlistPage !== null

  // The tab the screen belongs to, for the sidebar and the bottom bar
  const currentTab: Tab | null = onPlaylists
    ? 'playlists'
    : zimPage !== null
      ? 'zim'
    : bookPage !== null
      ? 'books'
    : audiobookPage !== null
      ? 'audiobooks'
      : libraryPage || highlightsPage || downloadsPage || (selection.kind === 'saved' && !anyPage && !searchView)
        ? 'library'
        : searchView && !anyPage
          ? null
          : 'feeds'
  const feedsLatest = currentTab === 'feeds' && !homePage && !managing && !feedPage && selection.kind === 'all'
  /** Latest | By feed | Discover: every feed's articles, the tiles (both remembered for the Feeds tab), or Discover. */
  const showFeedsView = (view: FeedsView) => {
    if (view === 'discover') {
      leavePages()
      setSearchView(null)
      setDrawer(false)
      showDiscover(true)
      return
    }
    const latest = view === 'latest'
    rememberFeedsView(latest)
    if (latest) selectList({ kind: 'all' })
    else {
      leavePages()
      setSearchView(null)
      setDrawer(false)
      showHome(true)
    }
  }
  const openTab = (t: Tab) => {
    setDrawer(false)
    setSearchView(null)
    if (t === 'feeds') return showFeedsView(feedsViewIsLatest() ? 'latest' : 'byFeed')
    leavePages()
    if (t === 'discover') showDiscover(true)
    else if (t === 'playlists') showPlaylists('')
    else if (t === 'zim') showZim('')
    else if (t === 'books') showBooks('')
    else if (t === 'audiobooks') showAudiobooks('')
    else showLibrary(true)
  }
  const openTabRef = useRef(openTab)
  openTabRef.current = openTab
  const onTabKey = useCallback((t: Tab) => openTabRef.current(t), [])
  useTabShortcuts(onTabKey, !!dialog || paletteOpen || !!flip)
  const videoJobs = useVideoJobs()
  const canSaveVideos = useVideoSavingAvailable()
  const downloadCount = items.filter((i) => i.offlineVideo).length + Object.keys(videoJobs).length
  const savedCount = items.filter((i) => i.saved).length
  const syncLabel = syncState
    ? syncState.lastError
      ? 'Sync problem, see Settings'
      : syncState.lastSyncAt
        ? `Synced ${new Date(syncState.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
        : 'Sync on'
    : 'Not syncing'

  return (
    <div className={`app pane-${pane}${drawer ? ' drawer-open' : ''}${navCollapsed ? ' nav-collapsed' : ''}${managing || highlightsPage || feedPage || downloadsPage || homePage || discoverPage || libraryPage || zimPage !== null || bookPage !== null || audiobookPage !== null || onPlaylists ? ' page-feeds' : ''}${queue.kind || (audiobook.bookId && audiobookPage !== audiobook.bookId) ? ' has-queue' : ''}`}>
      <div className="drawer-scrim" onClick={() => setDrawer(false)} />
      <Sidebar
        feeds={feedList}
        items={items}
        selection={selection}
        onSelect={selectList}
        searching={!!searchView && !anyPage}
        onSearch={() => {
          setDrawer(false)
          setPaletteOpen(true)
        }}
        showingPlaylist={playlistPage}
        onPlaylists={(id) => {
          setDrawer(false)
          if (feedPage) leaveFeedPage()
          showPlaylists(id)
        }}
        currentTab={currentTab}
        onTab={openTab}
        showingList={(currentTab === 'feeds' || currentTab === 'library') && !homePage && !managing && !feedPage && !libraryPage}
        onEditFeed={(url) => setDialog({ kind: 'edit', url })}
        onMoveFeed={moveFeed}
        onAddFeed={() => setDialog({ kind: 'add' })}
        onManageFeeds={() => {
          setDrawer(false)
          showManage(!managing)
        }}
        managing={managing}
        onRefresh={fullRefresh}
        onSettings={() => setDialog({ kind: 'settings' })}
        refreshing={refreshing}
        syncLabel={syncLabel}
        collapsed={navCollapsed}
        onToggleCollapsed={toggleNav}
      />
      {searchView && !anyPage ? (
        <SearchView
          query={searchView.query}
          scope={searchView.scope}
          onQuery={(query) => setSearchView((v) => (v ? { ...v, query } : v))}
          onScope={(scope) => setSearchView((v) => (v ? { ...v, scope } : v))}
          data={searchData}
          selectedId={openId}
          onOpen={openHit}
          onClose={() => setSearchView(null)}
          onMenu={() => setDrawer(true)}
          onDelete={deleteFromList}
          onOpenFeed={openFeedPage}
          onOrder={setSearchOrder}
        />
      ) : onPlaylists ? (
        <PlaylistsPage
          playlistId={playlistPage || null}
          items={items}
          feeds={feeds}
          readRate={settings.readAloudRate}
          onRoute={(id) => showPlaylists(id ?? '')}
          onOpenArticle={(id, order) => {
            const it = items.find((i) => i.id === id)
            if (it?.link && opensInBrowser(feeds.get(it.feedUrl))) return void window.open(it.link, '_blank', 'noopener')
            setReaderFrom(playlistPage || null)
            showPlaylists(null)
            open(id, order)
          }}
          onFlip={(order) => setFlip({ queue: order, start: order[0] })}
          onMenu={() => setDrawer(true)}
          onBack={() => showPlaylists(null)}
          toast={toast}
        />
      ) : downloadsPage ? (
        <DownloadsPage
          items={items}
          feeds={feeds}
          onBack={() => showDownloads(false)}
          onMenu={() => setDrawer(true)}
          onOpenArticle={(id) => {
            showDownloads(false)
            open(id, [id])
          }}
          toast={toast}
        />
      ) : highlightsPage ? (
        <HighlightsPage
          highlights={allHighlights}
          onBack={() => showHighlights(false)}
          onMenu={() => setDrawer(true)}
          onOpenArticle={openHighlight}
          onShare={async (h) => setPageShare({ text: h.text, note: h.note, color: h.color, itemTitle: h.itemTitle, feedTitle: h.feedTitle, itemLink: h.itemLink, imageUrl: await pictureFor(h) })}
          onShareMany={async (hs) => setPageShare(combinedSubject(hs, { itemTitle: hs[0].itemTitle, feedTitle: hs[0].feedTitle, itemLink: hs[0].itemLink, imageUrl: await pictureFor(hs[0]) }))}
          onDelete={async (h) => {
            await deleteHighlight(h.id)
            toast('Highlight deleted')
          }}
          toast={toast}
        />
      ) : feedPage ? (
        <FeedPage
          url={feedPage}
          feed={feeds.get(feedPage)}
          items={items}
          onBack={closeFeedPage}
          onShowArticles={(url) => {
            setSelection({ kind: 'feed', url })
            setPane('list')
            leaveFeedPage()
          }}
          toast={toast}
        />
      ) : managing ? (
        <ManageFeeds
          onOpenFeed={openFeedPage}
          feeds={feedList}
          items={items}
          onBack={() => showManage(false)}
          onEditFeed={(url) => setDialog({ kind: 'edit', url })}
          onMoveFeed={moveFeed}
          toast={toast}
        />
      ) : bookPage !== null ? (
        <EbooksPage book={bookPage.id || null} at={bookPage.at} settings={settings} onOpen={(id, at) => showBooks(id ?? '', at)} onMenu={() => setDrawer(true)} toast={toast} />
      ) : audiobookPage !== null ? (
        <AudiobooksPage book={audiobookPage || null} onOpen={(id) => showAudiobooks(id ?? '')} onMenu={() => setDrawer(true)} toast={toast} />
      ) : zimPage !== null ? (
        <ZimPage book={zimPage || null} jump={zimJump} settings={settings} onOpen={(id) => showZim(id ?? '')} onMenu={() => setDrawer(true)} toast={toast} />
      ) : libraryPage ? (
        <LibraryPage
          savedCount={savedCount}
          highlightCount={allHighlights.length}
          downloadCount={downloadCount}
          showDownloads={canSaveVideos || downloadCount > 0}
          onSaved={() => selectList({ kind: 'saved' })}
          onHighlights={() => showHighlights(true)}
          onDownloads={() => showDownloads(true)}
          onSettings={() => setDialog({ kind: 'settings' })}
          onManageFeeds={() => showManage(true)}
          onSearch={() => setPaletteOpen(true)}
          onMenu={() => setDrawer(true)}
        />
      ) : discoverPage ? (
        <DiscoverPage feeds={feedList} onOpenFeed={openFeedPage} onMenu={() => setDrawer(true)} toast={toast} feedsSwitch={<FeedsSwitch view="discover" onChange={showFeedsView} />} />
      ) : homePage ? (
        <HomePage
          feeds={feedList}
          items={items}
          settings={settings}
          onOpenFeed={openFeedPage}
          onShowFolder={(tag) => {
            setSelection({ kind: 'tag', tag })
            setPane('list')
            showHome(false)
          }}
          onAddFeed={() => setDialog({ kind: 'add' })}
          onRefresh={fullRefresh}
          refreshing={refreshing}
          onArrange={() => showManage(true)}
          onDiscover={() => showDiscover(true)}
          onMenu={() => setDrawer(true)}
          onSearch={() => setPaletteOpen(true)}
          feedsSwitch={<FeedsSwitch view="byFeed" onChange={showFeedsView} />}
          toast={toast}
        />
      ) : (
        <ArticleList
        title={selectionTitle(selection, feeds)}
        groups={groups}
        counts={counts}
        feeds={feeds}
        settings={settings}
        selectedId={openId}
        onOpen={openFromList}
        onMenu={() => setDrawer(true)}
        onMarkAllRead={markAllRead}
        onDeleteRead={deleteRead}
        onSetRead={setReadFromList}
        onDelete={deleteFromList}
        onFlip={startFlip}
        onOpenFeed={openFeedPage}
        onAddLink={selection.kind === 'saved' ? addLink : undefined}
        onSearch={() => setPaletteOpen(true)}
        feedsSwitch={feedsLatest ? <FeedsSwitch view="latest" onChange={showFeedsView} /> : null}
        />
      )}
      {managing || highlightsPage || feedPage || downloadsPage || homePage || discoverPage || libraryPage || zimPage !== null || bookPage !== null || audiobookPage !== null || onPlaylists || flip ? null : openItem ? (
        <Reader
          item={openItem}
          feed={feeds.get(openItem.feedUrl)}
          settings={settings}
          hasPrev={!!prevId}
          hasNext={!!nextId}
          nextTitle={nextTitle}
          onPrev={goPrev}
          onNext={goNext}
          onBack={leaveReader}
          fromPlaylist={!!readerFrom}
          autoplay={autoplay}
          onAutoplayConsumed={() => setAutoplay(false)}
          toast={toast}
          focusHighlight={focusHighlight}
          onFocusConsumed={() => setFocusHighlight(null)}
          onOpenFeed={openFeedPage}
          searchFocus={searchFocus && openItem.id in searchFocus.at ? { terms: searchFocus.terms, query: searchFocus.query, atMs: searchFocus.at[openItem.id] } : null}
        />
      ) : (
        <section className="reader-pane empty-reader">
          <p>Select an article. Keyboard: ⌘K or / search everything, G then F, D, P, L, B or Z switch tabs, J / K next and previous, F flip mode, R read aloud, S save, M unread, O open original, [ hide or show feeds.</p>
        </section>
      )}
      {dialog?.kind === 'settings' ? <SettingsDialog
          settings={settings}
          onClose={() => setDialog(null)}
          toast={toast}
          onManageFeeds={() => {
            setDialog(null)
            showManage(true)
          }}
        /> : null}
      {dialog?.kind === 'add' ? <AddFeedDialog onClose={() => setDialog(null)} toast={toast} /> : null}
      {dialog?.kind === 'edit' ? <EditFeedDialog url={dialog.url} onClose={() => setDialog(null)} toast={toast} /> : null}
      {pageShare ? <ShareDialog subject={pageShare} onClose={() => setPageShare(null)} toast={toast} /> : null}
      {flip ? (
        <FlipMode
          queue={flip.queue}
          startId={flip.start}
          items={items}
          feeds={feeds}
          rate={settings.readAloudRate}
          onExit={exitFlip}
          onOpenInReader={(id) => {
            setFlip(null)
            // Flip mode may have been opened from a playlist page, which would hide the reader
            setReaderFrom(playlistPage || null)
            if (playlistPage != null) showPlaylists(null)
            open(id, flip.queue)
          }}
          toast={toast}
        />
      ) : null}
      <QueuePlayer
        items={items}
        feeds={feeds}
        readRate={settings.readAloudRate}
        onReadRate={(r) => void updateSettings({ readAloudRate: r })}
        onOpenArticle={(id) => {
          showPlaylists(null)
          showManage(false)
          showHighlights(false)
          showDownloads(false)
          showHome(false)
          showDiscover(false)
          if (feedPage) leaveFeedPage()
          open(id, queue.ids)
        }}
      />
      {!queue.kind && audiobook.bookId && audiobookPage !== audiobook.bookId ? <AudiobookBar onOpen={(id) => { leavePages(); setSearchView(null); showAudiobooks(id) }} /> : null}
      {flip ? null : <TabBar current={currentTab} onTab={openTab} />}
      <AddToPlaylistMenu toast={toast} />
      {paletteOpen ? (
        <CommandPalette data={searchData} actions={paletteActions} onClose={() => setPaletteOpen(false)} onOpen={openHit} onShowAll={showAllResults} onResume={resumeItem} />
      ) : null}
      {toastMsg ? (
        <div className="toast" role="status">
          {toastMsg.msg}
          {toastMsg.action ? (
            <button
              className="toast-action"
              onClick={() => {
                toastMsg.action!.run()
                setToastMsg(null)
              }}
            >
              {toastMsg.action.label}
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
