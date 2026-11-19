import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, FEED_ORDER_KEY, feedDisplayTitle, getKv, hideItems, moveFeedToTag, opensInBrowser, placeInOrder, setKv, unhideItems, type Feed, type Item } from './lib/db'
import type { ToastAction } from './lib/toast'
import { applyShow, applyToday, buildGroups, selectItems, showCounts, type Selection } from './lib/listquery'
import { getSettings, updateSettings, useSettings } from './lib/settings'
import { refreshFeeds } from './lib/refresh'
import { getSyncState, syncNow } from './lib/sync2'
import { fetchOgImages, refreshHnScores } from './lib/extras'
import { readAloud } from './lib/readaloud'
import { Sidebar } from './components/Sidebar'
import { ArticleList } from './components/ArticleList'
import { Reader } from './components/Reader'
import { AddFeedDialog, EditFeedDialog, SettingsDialog } from './components/Dialogs'
import { ManageFeeds } from './components/ManageFeeds'

const NAV_COLLAPSED_KEY = 'feeder.navCollapsed'
/** The Manage feeds page has its own address so it can be bookmarked and Back works. */
const FEEDS_HASH = '#/feeds'
const isFeedsHash = () => window.location.hash === FEEDS_HASH

type Dialog = { kind: 'settings' } | { kind: 'add' } | { kind: 'edit'; url: string } | null

function selectionTitle(s: Selection, feeds: Map<string, Feed>): string {
  switch (s.kind) {
    case 'all':
      return 'All articles'
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
  const items = useLiveQuery(() => db.items.toArray(), []) ?? []
  const syncState = useLiveQuery(() => getSyncState(), [])
  const feeds = useMemo(() => new Map(feedList.map((f) => [f.url, f])), [feedList])

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
  useEffect(() => {
    const onHash = () => setManaging(isFeedsHash())
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
      else history.pushState(null, '', window.location.pathname + window.location.search)
    }
    setManaging(on)
  }, [])
  const [refreshing, setRefreshing] = useState<string | null>(null)
  const [toastMsg, setToastMsg] = useState<{ msg: string; action?: ToastAction } | null>(null)
  const [autoplay, setAutoplay] = useState(false)
  const toastTimer = useRef<number | null>(null)

  const toast = useCallback((msg: string, action?: ToastAction) => {
    setToastMsg({ msg, action })
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToastMsg(null), action ? 7000 : 4000)
  }, [])

  // Theme
  useEffect(() => {
    const root = document.documentElement
    if (settings.theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', settings.theme)
  }, [settings.theme])

  useEffect(() => {
    readAloud.setVoice(settings.readAloudVoice)
    readAloud.kokoroGpu = settings.readAloudKokoroGpu
    if (readAloud.state.rate !== settings.readAloudRate && readAloud.state.status === 'idle') readAloud.setRate(settings.readAloudRate)
  }, [settings.readAloudVoice, settings.readAloudRate, settings.readAloudKokoroGpu])

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

  const goPrev = useCallback(() => prevId && open(prevId), [prevId, open])
  const goNext = useCallback(() => nextId && open(nextId), [nextId, open])

  useEffect(() => {
    readAloud.onError = toast
  }, [toast])

  // Read aloud: auto-continue and media keys
  useEffect(() => {
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
  }, [snapshot, settings.readAloudAutoContinue, open, nextId, prevId])

  // Sync and refresh
  const fullRefresh = useCallback(async () => {
    if (refreshing) return
    try {
      setRefreshing('sync')
      await syncNow().catch((e) => toast(`Sync: ${e instanceof Error ? e.message : e}`))
      const added = await refreshFeeds(undefined, (pr) => setRefreshing(`${pr.done}/${pr.total}`))
      setRefreshing('sync')
      await syncNow().catch(() => undefined)
      if (added) toast(`${added} new article${added === 1 ? '' : 's'}`)
      const recent = await db.items.where('sortTime').above(Date.now() - 3 * 86_400_000).toArray()
      refreshHnScores(recent).catch(() => undefined)
      const ogFeeds = new Set((await db.feeds.toArray()).filter((f) => f.fetchOgImages).map((f) => f.url))
      if (ogFeeds.size) fetchOgImages(recent.filter((i) => ogFeeds.has(i.feedUrl)), 40).catch(() => undefined)
    } finally {
      setRefreshing(null)
    }
  }, [refreshing, toast])

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

  // Send read marks, saves and deletions shortly after they change
  const deletedCount = useLiveQuery(() => db.hidden.count(), []) ?? 0
  const pendingMarks = items.filter((i) => !i.readSynced).length + deletedCount
  useEffect(() => {
    if (!pendingMarks || !syncState) return
    const t = window.setTimeout(() => syncNow().catch(() => undefined), 5000)
    return () => clearTimeout(t)
  }, [pendingMarks, syncState])

  // og:images for the visible list
  useEffect(() => {
    const visible = groups.flatMap((g) => g.items).slice(0, 30).filter((i) => feeds.get(i.feedUrl)?.fetchOgImages)
    if (visible.length) fetchOgImages(visible).catch(() => undefined)
  }, [groups, feeds])

  // Keyboard shortcuts
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (dialog || e.ctrlKey || e.metaKey || e.altKey || /INPUT|TEXTAREA|SELECT/.test(t.tagName)) return
      // Any other modal (e.g. the Sort & group sheet) owns the keyboard while open
      if (document.querySelector('[aria-modal="true"]') || t.closest?.('[aria-modal="true"]')) return
      if (managing) {
        if (e.key === 'Escape') {
          showManage(false)
          e.preventDefault()
        }
        return
      }
      const order = visibleOrder
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
        case 'Escape':
          if (pane === 'reader') setPane('list')
          break
        default:
          return
      }
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dialog, visibleOrder, openId, nextId, prevId, goNext, goPrev, open, openItem, pane, toggleNav, managing, showManage])

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
    open(item.id, visibleOrder)
  }

  const syncLabel = syncState
    ? syncState.lastError
      ? 'Sync problem, see Settings'
      : syncState.lastSyncAt
        ? `Synced ${new Date(syncState.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
        : 'Sync on'
    : 'Not syncing'

  return (
    <div className={`app pane-${pane}${drawer ? ' drawer-open' : ''}${navCollapsed ? ' nav-collapsed' : ''}${managing ? ' page-feeds' : ''}`}>
      <div className="drawer-scrim" onClick={() => setDrawer(false)} />
      <Sidebar
        feeds={feedList}
        items={items}
        selection={selection}
        onSelect={(s) => {
          setSelection(s)
          setDrawer(false)
          setPane('list')
          showManage(false)
        }}
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
      {managing ? (
        <ManageFeeds
          feeds={feedList}
          items={items}
          onBack={() => showManage(false)}
          onEditFeed={(url) => setDialog({ kind: 'edit', url })}
          onMoveFeed={moveFeed}
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
        />
      )}
      {managing ? null : openItem ? (
        <Reader
          item={openItem}
          feed={feeds.get(openItem.feedUrl)}
          settings={settings}
          hasPrev={!!prevId}
          hasNext={!!nextId}
          nextTitle={nextTitle}
          onPrev={goPrev}
          onNext={goNext}
          onBack={() => setPane('list')}
          autoplay={autoplay}
          onAutoplayConsumed={() => setAutoplay(false)}
          toast={toast}
        />
      ) : (
        <section className="reader-pane empty-reader">
          <p>Select an article. Keyboard: J / K next and previous, R read aloud, S save, M unread, O open original, [ hide or show feeds.</p>
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
