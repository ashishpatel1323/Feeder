import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import { db, feedDisplayTitle, type Feed, type Item } from './lib/db'
import { applyFilters, buildGroups, selectItems, type Selection } from './lib/listquery'
import { useSettings } from './lib/settings'
import { refreshFeeds } from './lib/refresh'
import { getSyncState, syncNow } from './lib/sync'
import { fetchOgImages, refreshHnScores } from './lib/extras'
import { readAloud } from './lib/readaloud'
import { Sidebar } from './components/Sidebar'
import { ArticleList } from './components/ArticleList'
import { Reader } from './components/Reader'
import { AddFeedDialog, EditFeedDialog, SettingsDialog } from './components/Dialogs'

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
  const [dialog, setDialog] = useState<Dialog>(null)
  const [refreshing, setRefreshing] = useState<string | null>(null)
  const [toastMsg, setToastMsg] = useState<string | null>(null)
  const [autoplay, setAutoplay] = useState(false)
  const toastTimer = useRef<number | null>(null)

  const toast = useCallback((msg: string) => {
    setToastMsg(msg)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToastMsg(null), 4000)
  }, [])

  // Theme
  useEffect(() => {
    const root = document.documentElement
    if (settings.theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', settings.theme)
  }, [settings.theme])

  useEffect(() => {
    readAloud.voiceURI = settings.readAloudVoice
    if (readAloud.state.rate !== settings.readAloudRate && readAloud.state.status === 'idle') readAloud.setRate(settings.readAloudRate)
  }, [settings.readAloudVoice, settings.readAloudRate])

  const groups = useMemo(() => {
    const selected = selectItems(items, feeds, selection)
    // Keep the open article in the list even after it's marked read
    const filtered = applyFilters(selected, feeds, settings.filters, openId ?? undefined)
    return buildGroups(filtered, feeds, settings.grouping, settings.sorting)
  }, [items, feeds, selection, settings.filters, settings.grouping, settings.sorting, openId])

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

  // Send read marks shortly after they change
  const pendingMarks = items.filter((i) => i.read && !i.readSynced).length
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
          if (openItem) db.items.update(openItem.id, { saved: openItem.saved ? 0 : 1 })
          break
        case 'm':
          if (openItem) db.items.update(openItem.id, { read: openItem.read ? 0 : 1, readTime: openItem.read ? null : Date.now(), readSynced: 0 })
          break
        case 'o':
          if (openItem?.link) window.open(openItem.link, '_blank', 'noopener')
          break
        case 'r':
          if (openItem) readAloud.state.status === 'idle' ? readAloud.play() : readAloud.stop()
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
  }, [dialog, visibleOrder, openId, nextId, prevId, goNext, goPrev, open, openItem, pane])

  const markAllRead = async () => {
    const ids = groups.flatMap((g) => g.items).filter((i) => !i.read).map((i) => i.id)
    if (!ids.length) return
    await db.items.bulkUpdate(ids.map((key) => ({ key, changes: { read: 1, readTime: Date.now(), readSynced: 0 } })))
    toast(`Marked ${ids.length} as read`)
  }

  const syncLabel = syncState
    ? syncState.lastError
      ? 'Sync problem, see Settings'
      : syncState.lastSyncAt
        ? `Synced ${new Date(syncState.lastSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
        : 'Sync on'
    : 'Not syncing'

  return (
    <div className={`app pane-${pane}${drawer ? ' drawer-open' : ''}`}>
      <div className="drawer-scrim" onClick={() => setDrawer(false)} />
      <Sidebar
        feeds={feedList}
        items={items}
        selection={selection}
        onSelect={(s) => {
          setSelection(s)
          setDrawer(false)
          setPane('list')
        }}
        onEditFeed={(url) => setDialog({ kind: 'edit', url })}
        onAddFeed={() => setDialog({ kind: 'add' })}
        onRefresh={fullRefresh}
        onSettings={() => setDialog({ kind: 'settings' })}
        refreshing={refreshing}
        syncLabel={syncLabel}
      />
      <ArticleList
        title={selectionTitle(selection, feeds)}
        groups={groups}
        feeds={feeds}
        settings={settings}
        selectedId={openId}
        onOpen={(item: Item) => open(item.id, visibleOrder)}
        onMenu={() => setDrawer(true)}
        onMarkAllRead={markAllRead}
      />
      {openItem ? (
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
          <p>Select an article. Keyboard: J / K next and previous, R read aloud, S save, M unread, O open original.</p>
        </section>
      )}
      {dialog?.kind === 'settings' ? <SettingsDialog settings={settings} onClose={() => setDialog(null)} toast={toast} /> : null}
      {dialog?.kind === 'add' ? <AddFeedDialog onClose={() => setDialog(null)} toast={toast} /> : null}
      {dialog?.kind === 'edit' ? <EditFeedDialog url={dialog.url} onClose={() => setDialog(null)} toast={toast} /> : null}
      {toastMsg ? (
        <div className="toast" role="status">
          {toastMsg}
        </div>
      ) : null}
    </div>
  )
}
