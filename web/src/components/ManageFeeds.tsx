import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useLiveQuery } from 'dexie-react-hooks'
import {
    addEmptyFolder,
    deleteFeeds,
    deleteTag,
    EMPTY_FOLDERS_KEY,
    FEED_ORDER_KEY,
    feedDisplayTitle,
    FOLDER_ORDER_KEY,
    getKv,
    moveFeedsToTag,
    OPEN_WITH_BROWSER,
    OPEN_WITH_READER,
    opensInBrowser,
    placeFolder,
    renameFeed,
    renameTag,
    setKv,
    sortFolder,
    sortFolders,
    updateFeed,
    type Feed,
    type Item,
} from '../lib/db'
import { computeFeedStats, formatRate, noStats, STATS_DAYS, timeAgo, type FeedStats } from '../lib/feedstats'
import { loadClosedFolders, MANAGE_CLOSED_FOLDERS_KEY, saveClosedFolders, toggleFolder } from '../lib/folders'
import { addFeed, exportOpml, importOpml, refreshFeeds } from '../lib/refresh'
import { syncNow } from '../lib/sync2'
import { noteFeedsDeleted } from '../lib/recent'
import { FeedIcon } from './Sidebar'
import { Icon, type IconName } from './Icon'
import { confirmDelete, itemCount } from '../lib/confirmDelete'

const PICK = '\u0000pick'
const NEW_FOLDER = '\u0000new'

type Place = { beforeUrl: string | null; folderUrls: string[] }
type Sort = 'folder' | 'active' | 'least' | 'unread' | 'readLeast' | 'stale' | 'errors'

const SORTS: [Sort, string][] = [
    ['folder', 'Folder order'],
    ['active', 'Most active'],
    ['least', 'Least active'],
    ['unread', 'Most unread'],
    ['readLeast', 'Read the least'],
    ['stale', 'Not updated in 30 days'],
    ['errors', 'Errors'],
]

interface Props {
    feeds: Feed[]
    items: Item[]
    onBack: () => void
    onEditFeed: (url: string) => void
    /** Opens the feed's own page (stats and settings). */
    onOpenFeed: (url: string) => void
    /** The same handler as the sidebar's drag and drop (PR #49). */
    onMoveFeed: (url: string, tag: string, place?: Place) => void | Promise<void>
    toast: (m: string) => void
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e))
const sync = () => syncNow().catch(() => undefined)

function Btn({
    icon,
    label,
    onClick,
    danger,
    on,
    text,
}: {
    icon: IconName
    label: string
    onClick: () => void
    danger?: boolean
    on?: boolean
    text?: string
}) {
    return (
        <button
            type="button"
            className={`mf-ib${danger ? ' danger' : ''}${on ? ' on' : ''}${text ? ' text' : ''}`}
            title={label}
            aria-label={label}
            aria-pressed={on === undefined ? undefined : on}
            onClick={onClick}
        >
            <Icon name={icon} size={18} />
            {text ? <span>{text}</span> : null}
        </button>
    )
}

function Spark({ values }: { values: number[] }) {
    const w = 92
    const h = 22
    const max = Math.max(1, ...values)
    const bw = w / values.length
    return (
        <svg className="mf-spark" width={w} height={h} aria-hidden="true">
            {values.map((v, i) => {
                const bh = Math.max(v ? 2 : 1, (v / max) * h)
                return <rect key={i} x={i * bw} y={h - bh} width={bw - 1} height={bh} rx={0.5} className={v ? 'on' : 'off'} />
            })}
        </svg>
    )
}

export function StatsPanel({ s, feed }: { s: FeedStats; feed: Feed }) {
    const max = Math.max(1, ...s.perDay)
    const wmax = Math.max(1, ...s.byWeekday)
    const readPct = s.recent ? Math.round((s.recentRead / s.recent) * 100) : null
    const first = new Date()
    first.setDate(first.getDate() - (STATS_DAYS - 1))
    const fmt = (d: Date) => d.toLocaleDateString([], { month: 'short', day: 'numeric' })
    const tile = (k: string, v: string, sub?: string) => (
        <div className="mf-tile">
            <div className="mf-k">{k}</div>
            <div className="mf-v">{v}</div>
            {sub ? <div className="mf-sub">{sub}</div> : null}
        </div>
    )
    return (
        <div className="mf-stats">
            <div className="mf-tiles">
                {tile(
                    'Articles per day',
                    s.rate >= 1 ? formatRate(s.rate).replace('/day', '') : formatRate(s.rate),
                    `${STATS_DAYS}-day average · ${s.lastWeek} last week`,
                )}
                {tile(
                    'Last new article',
                    timeAgo(s.lastArticle),
                    feed.lastError ? `Last check failed: ${feed.lastError}` : `Checked ${timeAgo(feed.lastFetched)} · OK`,
                )}
                {tile('You read', readPct === null ? '—' : `${readPct}%`, `${s.recentRead} of ${s.recent} in ${STATS_DAYS} days`)}
                {tile('Unread', String(s.unread), `${s.saved} saved`)}
                {tile('Average length', s.stored ? `${Math.max(1, Math.round(s.avgWords / 220))} min` : '—', s.stored ? `${s.avgWords} words` : undefined)}
                {tile(
                    'In this browser since',
                    s.firstSeen
                        ? new Date(s.firstSeen).toLocaleDateString([], {
                              month: 'short',
                              year: 'numeric',
                          })
                        : '—',
                    `${s.stored} articles stored`,
                )}
            </div>
            <div className="mf-charts">
                <div>
                    <div className="mf-k">New articles, last {STATS_DAYS} days</div>
                    <svg className="mf-bars" viewBox={`0 0 ${STATS_DAYS * 10} 70`} preserveAspectRatio="none" aria-hidden="true">
                        {s.perDay.map((v, i) => (
                            <rect key={i} x={i * 10} y={66 - (v / max) * 62} width={8} height={(v / max) * 62} rx={1} />
                        ))}
                        <line x1={0} x2={STATS_DAYS * 10} y1={66.5} y2={66.5} />
                    </svg>
                    <div className="mf-axis">
                        <span>{fmt(first)}</span>
                        <span>Today</span>
                    </div>
                </div>
                <div>
                    <div className="mf-k">When it posts</div>
                    <div className="mf-dow">
                        {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => (
                            <div key={i} title={`${s.byWeekday[i]} articles`}>
                                <span
                                    style={{
                                        height: `${(s.byWeekday[i] / wmax) * 48 + (s.byWeekday[i] ? 2 : 0)}px`,
                                    }}
                                />
                                <em>{d}</em>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    )
}

export function ManageFeeds(p: Props) {
    const feedOrder = useLiveQuery(() => getKv<string[]>(FEED_ORDER_KEY, []), []) ?? []
    const folderOrder = useLiveQuery(() => getKv<string[]>(FOLDER_ORDER_KEY, []), []) ?? []
    const emptyFolders = useLiveQuery(() => getKv<string[]>(EMPTY_FOLDERS_KEY, []), []) ?? []

    const [filter, setFilter] = useState('')
    const [sort, setSort] = useState<Sort>('folder')
    /** Collapsed folder cards ("" is No folder), remembered in this browser like the sidebar's folders. */
    const [closedTags, setClosedTags] = useState<string[]>(() => loadClosedFolders(globalThis.localStorage, MANAGE_CLOSED_FOLDERS_KEY))
    useEffect(() => saveClosedFolders(closedTags, globalThis.localStorage, MANAGE_CLOSED_FOLDERS_KEY), [closedTags])
    const [selected, setSelected] = useState<Set<string>>(new Set())
    const [renaming, setRenaming] = useState<{
        url: string
        value: string
    } | null>(null)
    const [renamingTag, setRenamingTag] = useState<{
        tag: string
        value: string
    } | null>(null)
    const [deletingTag, setDeletingTag] = useState<string | null>(null)
    const [statsOpen, setStatsOpen] = useState<Set<string>>(new Set())
    const [addUrl, setAddUrl] = useState('')
    const [addTag, setAddTag] = useState('')
    const [adding, setAdding] = useState(false)
    const [newFolder, setNewFolder] = useState<string | null>(null)
    const addInput = useRef<HTMLInputElement>(null)
    const fileInput = useRef<HTMLInputElement>(null)

    // Drag and drop
    const [dragUrl, setDragUrl] = useState<string | null>(null)
    const [dragTag, setDragTag] = useState<string | null>(null)
    const [dropTag, setDropTag] = useState<string | null>(null)
    const [dropAt, setDropAt] = useState<{
        url: string
        after: boolean
    } | null>(null)
    const endDrag = () => {
        setDragUrl(null)
        setDragTag(null)
        setDropTag(null)
        setDropAt(null)
    }

    const stats = useMemo(() => computeFeedStats(p.items), [p.items])
    const statsOf = (url: string) => stats.get(url) ?? noStats()

    const q = filter.trim().toLowerCase()
    const matches = (f: Feed) => !q || feedDisplayTitle(f).toLowerCase().includes(q) || f.url.toLowerCase().includes(q)

    const allTags = useMemo(() => {
        const set = new Set(p.feeds.map((f) => f.tag).filter(Boolean))
        for (const t of emptyFolders) set.add(t)
        return sortFolders([...set], folderOrder)
    }, [p.feeds, emptyFolders, folderOrder])

    /** Folder cards in folder order; "" (no folder) last. */
    const folders = useMemo(() => {
        const byTag = new Map<string, Feed[]>(allTags.map((t) => [t, []]))
        byTag.set('', [])
        for (const f of p.feeds) byTag.get(f.tag)?.push(f)
        return [...byTag.entries()].map(([tag, list]) => [tag, sortFolder(list, feedOrder)] as const)
    }, [p.feeds, allTags, feedOrder])

    /**
     * With a sort other than Folder order the folder cards stay; feeds are sorted inside each one
     * (and Not updated / Errors only keep the matching feeds).
     */
    const shownFolders = useMemo(() => {
        const s = (f: Feed) => stats.get(f.url) ?? noStats()
        const readRate = (f: Feed) => (s(f).recent ? s(f).recentRead / s(f).recent : 2)
        const by: Record<Sort, ((a: Feed, b: Feed) => number) | null> = {
            folder: null,
            active: (a, b) => s(b).rate - s(a).rate,
            least: (a, b) => s(a).rate - s(b).rate,
            unread: (a, b) => s(b).unread - s(a).unread,
            readLeast: (a, b) => readRate(a) - readRate(b),
            stale: (a, b) => (s(a).lastArticle ?? 0) - (s(b).lastArticle ?? 0),
            errors: null,
        }
        const keep = (f: Feed) => matches(f) && (sort !== 'stale' || s(f).recent === 0) && (sort !== 'errors' || !!f.lastError)
        const cmp = by[sort]
        return folders.map(([tag, list]) => {
            const shown = list.filter(keep)
            return [tag, list, cmp ? [...shown].sort(cmp) : shown] as const
        })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sort, folders, stats, q])

    /** A search or a filtering sort opens every folder so no match is hidden. */
    const filtering = !!q || sort === 'stale' || sort === 'errors'
    const isClosed = (tag: string) => !filtering && closedTags.includes(tag)
    /** Folder cards on screen right now, for Expand all / Collapse all. */
    const cardTags = folders.filter(([tag, list]) => tag || list.length).map(([tag]) => tag)
    /** Select all only picks feeds you can see, never ones inside a collapsed folder. */
    const visibleUrls = shownFolders.flatMap(([tag, , shown]) => (isClosed(tag) ? [] : shown.map((f) => f.url)))
    const allVisibleSelected = visibleUrls.length > 0 && visibleUrls.every((u) => selected.has(u))

    const toggleSel = (url: string) =>
        setSelected((s) => {
            const n = new Set(s)
            if (n.has(url)) n.delete(url)
            else n.add(url)
            return n
        })

    // ------------------------------------------------------------------ actions

    const submitAdd = async (e: React.FormEvent) => {
        e.preventDefault()
        if (!addUrl.trim()) return
        setAdding(true)
        try {
            await addFeed(addUrl, addTag)
            sync()
            p.toast(addTag ? `Feed added to ${addTag}` : 'Feed added')
            setAddUrl('')
        } catch (err) {
            p.toast(errText(err))
        } finally {
            setAdding(false)
        }
    }

    const startAddTo = (tag: string) => {
        setAddTag(tag)
        addInput.current?.focus()
        addInput.current?.scrollIntoView({
            block: 'center',
            behavior: 'smooth',
        })
    }

    const saveRename = async () => {
        if (!renaming) return
        if (await renameFeed(renaming.url, renaming.value)) {
            sync()
            p.toast('Feed renamed')
        }
        setRenaming(null)
    }

    const saveTagRename = async () => {
        if (!renamingTag) return
        const { tag, value } = renamingTag
        const to = value.trim()
        setRenamingTag(null)
        if (!to || to === tag) return
        const merged = allTags.includes(to)
        const n = await renameTag(tag, to)
        if (n) sync()
        p.toast(merged ? `Merged ${tag} into ${to}` : `Renamed to ${to}`)
    }

    const removeTag = async (tag: string, mode: 'keep' | 'unsubscribe') => {
        const count = p.feeds.filter((f) => f.tag === tag).length
        const ok = await confirmDelete(
            mode === 'keep'
                ? { title: 'Delete folder?', itemName: tag, scope: 'all-devices', extra: count ? `Its ${itemCount(count, 'feed')} move to No folder.` : undefined }
                : { title: 'Delete folder and its feeds?', itemName: `${tag} (${itemCount(count, 'feed')})`, scope: 'all-devices', confirmLabel: 'Unsubscribe', extra: 'The feeds and their articles go too.' },
        )
        if (!ok) return
        setDeletingTag(null)
        if (mode === 'unsubscribe') await noteFeedsDeleted(p.feeds.filter((f) => f.tag === tag).map((f) => f.url))
        const n = await deleteTag(tag, mode)
        if (n) sync()
        p.toast(
            mode === 'keep'
                ? `Deleted ${tag}${n ? `; ${n} feed${n === 1 ? '' : 's'} moved to No folder` : ''}`
                : `Deleted ${tag} and ${n} feed${n === 1 ? '' : 's'}`,
        )
    }

    const unsubscribe = async (urls: string[]) => {
        if (!urls.length) return
        const what = urls.length === 1 ? feedDisplayTitle(p.feeds.find((f) => f.url === urls[0])) : itemCount(urls.length, 'feed')
        if (!(await confirmDelete({ title: urls.length === 1 ? 'Unsubscribe from feed?' : 'Unsubscribe from feeds?', itemName: what, scope: 'all-devices', confirmLabel: 'Unsubscribe', extra: 'Its articles are deleted too.' }))) return
        await noteFeedsDeleted(urls)
        await deleteFeeds(urls)
        sync()
        setSelected((s) => new Set([...s].filter((u) => !urls.includes(u))))
        p.toast(urls.length === 1 ? 'Unsubscribed' : `Unsubscribed from ${urls.length} feeds`)
    }

    const moveSelected = async (tag: string) => {
        const n = await moveFeedsToTag([...selected], tag)
        if (n) sync()
        p.toast(n ? `Moved ${n} feed${n === 1 ? '' : 's'} to ${tag || 'No folder'}` : 'Already there')
        setSelected(new Set())
    }

    const setFeed = async (url: string, patch: Partial<Feed>) => {
        if (await updateFeed(url, patch)) sync()
    }

    const createFolder = async () => {
        const name = (newFolder ?? '').trim()
        if (!name) return setNewFolder(null)
        if (await addEmptyFolder(name)) p.toast(`Folder ${name} added. Drag feeds into it or pick it in a feed's folder list.`)
        else p.toast(`There is already a folder called ${name}`)
        setNewFolder(null)
    }

    /** The folder dropdown also offers "+ New folder…". */
    const pickFolder = async (url: string, value: string) => {
        let tag = value
        if (value === NEW_FOLDER) {
            const name = prompt('New folder name')?.trim()
            if (!name) return
            tag = name
        }
        await p.onMoveFeed(url, tag)
    }

    const doExport = async () => {
        const blob = new Blob([await exportOpml()], { type: 'text/x-opml' })
        const a = document.createElement('a')
        a.href = URL.createObjectURL(blob)
        a.download = 'feeder.opml'
        a.click()
        URL.revokeObjectURL(a.href)
    }

    const doImport = async (file: File) => {
        try {
            const n = await importOpml(await file.text())
            p.toast(`Imported ${n} new feed${n === 1 ? '' : 's'}`)
            if (n) {
                sync()
                refreshFeeds().catch(() => undefined)
            }
        } catch (err) {
            p.toast(`Import failed: ${errText(err)}`)
        }
    }

    // ------------------------------------------------------------------ drag and drop

    /** Folder card or header as a drop target: a feed moves to the end of it, a folder goes before it. */
    const folderDrop = (tag: string) => ({
        onDragOver: (e: React.DragEvent) => {
            if (!dragUrl && dragTag === null) return
            if (dragTag !== null && !tag) return
            e.preventDefault()
            e.stopPropagation()
            e.dataTransfer.dropEffect = 'move'
            if (dropTag !== tag) setDropTag(tag)
            if (dropAt) setDropAt(null)
        },
        onDrop: async (e: React.DragEvent) => {
            e.preventDefault()
            e.stopPropagation()
            if (dragUrl) {
                const list = folders.find(([t]) => t === tag)?.[1] ?? []
                await p.onMoveFeed(dragUrl, tag, {
                    beforeUrl: null,
                    folderUrls: list.map((f) => f.url),
                })
            } else if (dragTag !== null && tag && dragTag !== tag) {
                await setKv(FOLDER_ORDER_KEY, placeFolder(allTags, dragTag, tag))
            }
            endDrag()
        },
    })

    const folderOptions = (
        <>
            {allTags.map((t) => (
                <option key={t} value={t}>
                    {t}
                </option>
            ))}
            <option value="">No folder</option>
            <option value={NEW_FOLDER}>+ New folder…</option>
        </>
    )

    const feedRow = (f: Feed, i: number, list: Feed[], inFolder: boolean) => {
        const s = statsOf(f.url)
        const title = feedDisplayTitle(f)
        const isRenaming = renaming?.url === f.url
        const open = statsOpen.has(f.url)
        const openWith = opensInBrowser(f) ? OPEN_WITH_BROWSER : f.openArticlesWith === OPEN_WITH_READER ? OPEN_WITH_READER : ''
        const cls = [
            'mf-row',
            selected.has(f.url) ? 'sel' : '',
            isRenaming ? 'editing' : '',
            dragUrl === f.url ? 'dragging' : '',
            dropAt?.url === f.url && dragUrl !== f.url ? (dropAt.after ? 'drop-after' : 'drop-before') : '',
        ]
            .filter(Boolean)
            .join(' ')
        const fullSwitch = (
            <label className="mf-switch" title="Fetch the full article instead of the text in the feed">
                <input type="checkbox" checked={f.fullTextByDefault} onChange={(e) => setFeed(f.url, { fullTextByDefault: e.target.checked })} />
                <span aria-hidden="true" />
                <em>Full article</em>
            </label>
        )
        return (
            <Fragment key={f.url}>
                <div
                    className={cls}
                    draggable={inFolder && !isRenaming}
                    onDragStart={(e) => {
                        e.dataTransfer.effectAllowed = 'move'
                        e.dataTransfer.setData('text/plain', f.url)
                        setDragUrl(f.url)
                    }}
                    onDragEnd={endDrag}
                    onDragOver={
                        inFolder
                            ? (e) => {
                                  if (!dragUrl) return
                                  e.preventDefault()
                                  e.stopPropagation()
                                  e.dataTransfer.dropEffect = 'move'
                                  const r = e.currentTarget.getBoundingClientRect()
                                  const after = e.clientY > r.top + r.height / 2
                                  if (dropAt?.url !== f.url || dropAt.after !== after) setDropAt({ url: f.url, after })
                                  if (dropTag !== null) setDropTag(null)
                              }
                            : undefined
                    }
                    onDrop={
                        inFolder
                            ? async (e) => {
                                  if (!dragUrl) return
                                  e.preventDefault()
                                  e.stopPropagation()
                                  const after = dropAt?.url === f.url ? dropAt.after : false
                                  const beforeUrl = after ? (list[i + 1]?.url ?? null) : f.url
                                  if (dragUrl !== f.url)
                                      await p.onMoveFeed(dragUrl, f.tag, {
                                          beforeUrl,
                                          folderUrls: list.map((x) => x.url),
                                      })
                                  endDrag()
                              }
                            : undefined
                    }
                >
                    <span
                        className={`mf-handle${inFolder ? '' : ' off'}`}
                        title={inFolder ? 'Drag to reorder or move to another folder' : 'Switch to Folder order to reorder by dragging'}
                    >
                        <Icon name="dragHandle" size={18} />
                    </span>
                    <input type="checkbox" className="mf-cb" checked={selected.has(f.url)} onChange={() => toggleSel(f.url)} aria-label={`Select ${title}`} />
                    <FeedIcon feed={f} />
                    <div className="mf-title">
                        {isRenaming ? (
                            <input
                                className="mf-input"
                                autoFocus
                                value={renaming.value}
                                placeholder={f.title || f.url}
                                onChange={(e) =>
                                    setRenaming({
                                        url: f.url,
                                        value: e.target.value,
                                    })
                                }
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter') saveRename()
                                    if (e.key === 'Escape') setRenaming(null)
                                }}
                            />
                        ) : (
                            <button type="button" className="mf-name mf-namelink" title="Open this feed's page: stats and settings" onClick={() => p.onOpenFeed(f.url)}>
                                {title}
                            </button>
                        )}
                        <div className="mf-url">
                            {f.url}
                            {isRenaming && f.title ? ` · original title “${f.title}”` : ''}
                        </div>
                    </div>
                    <select className="mf-select mf-folder" value={f.tag} onChange={(e) => pickFolder(f.url, e.target.value)} aria-label="Folder">
                        {folderOptions}
                    </select>
                    <div className="mf-open">
                        <select
                            className="mf-select"
                            value={openWith}
                            onChange={(e) =>
                                setFeed(f.url, {
                                    openArticlesWith: e.target.value,
                                })
                            }
                            aria-label="Opens in"
                            title="How articles from this feed open"
                        >
                            <option value="">Default</option>
                            <option value={OPEN_WITH_READER}>Reader</option>
                            <option value={OPEN_WITH_BROWSER}>Browser tab</option>
                        </select>
                        <span className="mf-only-narrow">{fullSwitch}</span>
                    </div>
                    <div className="mf-full">{fullSwitch}</div>
                    <div className="mf-activity">
                        <div className="mf-actline">
                            <Spark values={s.perDay} />
                            <b>{formatRate(s.rate)}</b>
                        </div>
                        <div className={`mf-status${f.lastError ? ' err' : ''}`} title={f.lastError ?? undefined}>
                            {f.lastError ? `Failed: ${f.lastError}` : `Checked ${timeAgo(f.lastFetched)}`}
                        </div>
                    </div>
                    <span className="mf-unread">{s.unread || ''}</span>
                    <div className="mf-acts">
                        {isRenaming ? (
                            <>
                                <Btn icon="check" label="Save name" onClick={saveRename} on />
                                <Btn icon="close" label="Cancel" onClick={() => setRenaming(null)} />
                            </>
                        ) : (
                            <>
                                <Btn
                                    icon="edit"
                                    label={`Rename ${title}`}
                                    onClick={() =>
                                        setRenaming({
                                            url: f.url,
                                            value: f.customTitle || f.title,
                                        })
                                    }
                                />
                                <Btn
                                    icon="chart"
                                    label={open ? 'Hide stats' : 'Show stats'}
                                    on={open}
                                    onClick={() =>
                                        setStatsOpen((o) => {
                                            const n = new Set(o)
                                            if (n.has(f.url)) n.delete(f.url)
                                            else n.add(f.url)
                                            return n
                                        })
                                    }
                                />
                                <Btn icon="tune" label="Feed options (filters, thumbnails…)" onClick={() => p.onEditFeed(f.url)} />
                                <Btn icon="delete" label={`Unsubscribe from ${title}`} danger onClick={() => unsubscribe([f.url])} />
                            </>
                        )}
                    </div>
                </div>
                {open ? <StatsPanel s={s} feed={f} /> : null}
            </Fragment>
        )
    }

    const folderCard = (tag: string, list: Feed[], shown: Feed[]) => {
        if ((q || sort === 'stale' || sort === 'errors') && !shown.length) return null
        if (!tag && !list.length) return null
        const unread = list.reduce((n, f) => n + statsOf(f.url).unread, 0)
        const isRenaming = renamingTag?.tag === tag
        const isDeleting = deletingTag === tag
        const closed = isClosed(tag)
        const name = tag || 'No folder'
        const target = dropTag === tag && ((dragUrl && p.feeds.find((f) => f.url === dragUrl)?.tag !== tag) || (dragTag !== null && dragTag !== tag))
        return (
            <section
                key={`tag:${tag}`}
                className={`mf-card${target ? ' drop-target' : ''}${dragTag === tag ? ' dragging' : ''}${closed ? ' closed' : ''}`}
                {...folderDrop(tag)}
            >
                <div
                    className="mf-fhead"
                    draggable={!!tag && !isRenaming}
                    onDragStart={(e) => {
                        if (!tag) return
                        e.dataTransfer.effectAllowed = 'move'
                        e.dataTransfer.setData('text/plain', tag)
                        setDragTag(tag)
                    }}
                    onDragEnd={endDrag}
                >
                    <button
                        type="button"
                        className="mf-toggle"
                        disabled={filtering}
                        title={filtering ? 'Folders stay open while searching or filtering' : closed ? `Expand ${name}` : `Collapse ${name}`}
                        aria-label={closed ? `Expand ${name}` : `Collapse ${name}`}
                        aria-expanded={!closed}
                        onClick={() => setClosedTags((t) => toggleFolder(t, tag))}
                    >
                        <Icon name={closed ? 'next' : 'expand'} size={18} />
                    </button>
                    {tag ? (
                        <span className="mf-handle" title="Drag to reorder folders">
                            <Icon name="dragHandle" size={18} />
                        </span>
                    ) : null}
                    <Icon name="folder" size={18} />
                    {isRenaming ? (
                        <>
                            <input
                                className="mf-input mf-tagname"
                                autoFocus
                                value={renamingTag.value}
                                onChange={(e) =>
                                    setRenamingTag({
                                        tag,
                                        value: e.target.value,
                                    })
                                }
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter') saveTagRename()
                                    if (e.key === 'Escape') setRenamingTag(null)
                                }}
                            />
                            <Btn icon="check" label="Save folder name" onClick={saveTagRename} on />
                            <Btn icon="close" label="Cancel" onClick={() => setRenamingTag(null)} />
                        </>
                    ) : (
                        <span
                            className={`mf-fname${tag ? '' : ' none'}${filtering ? '' : ' toggle'}`}
                            onClick={filtering ? undefined : () => setClosedTags((t) => toggleFolder(t, tag))}
                        >
                            {name}
                        </span>
                    )}
                    <span className="mf-fcount">
                        {list.length} feed{list.length === 1 ? '' : 's'}
                        {unread ? ` · ${unread} unread` : ''}
                        {tag && !list.length ? ' · only in this browser until a feed is moved in' : ''}
                    </span>
                    <span className="mf-sp" />
                    {isDeleting ? (
                        <span className="mf-confirm">
                            <span>Delete {tag}?</span>
                            <button type="button" onClick={() => removeTag(tag, 'keep')}>
                                {list.length ? 'Keep feeds (move to No folder)' : 'Delete folder'}
                            </button>
                            {list.length ? (
                                <button type="button" className="danger" onClick={() => removeTag(tag, 'unsubscribe')}>
                                    {list.length === 1 ? 'Unsubscribe from its feed' : `Unsubscribe from all ${list.length}`}
                                </button>
                            ) : null}
                            <button type="button" onClick={() => setDeletingTag(null)}>
                                Cancel
                            </button>
                        </span>
                    ) : tag && !isRenaming ? (
                        <>
                            <Btn icon="add" label={`Add a feed to ${tag}`} text="Add feed" onClick={() => startAddTo(tag)} />
                            <Btn icon="edit" label={`Rename ${tag}`} onClick={() => setRenamingTag({ tag, value: tag })} />
                            <Btn icon="delete" label={`Delete ${tag}`} danger onClick={() => setDeletingTag(tag)} />
                        </>
                    ) : null}
                </div>
                {closed ? null : shown.map((f, i) => feedRow(f, i, list, sort === 'folder'))}
                {!closed && !list.length ? <div className="mf-emptyrow">Drop feeds here</div> : null}
            </section>
        )
    }

    return (
        <section className="manage-pane">
            <div className="mf-page">
                <header className="mf-head">
                    <button type="button" className="mf-ib" title="Back to articles (Esc)" aria-label="Back to articles" onClick={p.onBack}>
                        <Icon name="back" size={22} />
                    </button>
                    <h1>Manage feeds</h1>
                    <span className="mf-meta">
                        {allTags.length} folder{allTags.length === 1 ? '' : 's'} · {p.feeds.length} feed{p.feeds.length === 1 ? '' : 's'}
                    </span>
                    <span className="mf-sp" />
                    <button type="button" className="mf-btn" onClick={() => fileInput.current?.click()} title="Import OPML">
                        <Icon name="upload" size={18} />
                        <span>Import OPML</span>
                    </button>
                    <button type="button" className="mf-btn" onClick={doExport} title="Export OPML">
                        <Icon name="download" size={18} />
                        <span>Export OPML</span>
                    </button>
                    <input
                        ref={fileInput}
                        type="file"
                        accept=".opml,.xml,text/xml,application/xml"
                        hidden
                        onChange={(e) => {
                            const file = e.target.files?.[0]
                            if (file) doImport(file)
                            e.target.value = ''
                        }}
                    />
                </header>

                <form className="mf-addbar" onSubmit={submitAdd}>
                    <Icon name="add" size={20} />
                    <input
                        ref={addInput}
                        className="mf-input mf-addurl"
                        value={addUrl}
                        onChange={(e) => setAddUrl(e.target.value)}
                        placeholder="Paste a feed or website address, e.g. theverge.com"
                        aria-label="Feed or website address"
                    />
                    <select className="mf-select" value={addTag} onChange={(e) => setAddTag(e.target.value)} aria-label="Folder for the new feed">
                        <option value="">No folder</option>
                        {allTags.map((t) => (
                            <option key={t} value={t}>
                                {t}
                            </option>
                        ))}
                    </select>
                    <button type="submit" className="mf-btn primary" disabled={adding || !addUrl.trim()}>
                        {adding ? 'Adding…' : 'Add feed'}
                    </button>
                </form>

                <div className="mf-tools">
                    <input
                        className="mf-input mf-search"
                        value={filter}
                        onChange={(e) => setFilter(e.target.value)}
                        placeholder="Filter feeds by name or address"
                        aria-label="Filter feeds"
                    />
                    {newFolder === null ? (
                        <button type="button" className="mf-btn" onClick={() => setNewFolder('')}>
                            <Icon name="newFolder" size={18} />
                            <span>New folder</span>
                        </button>
                    ) : (
                        <span className="mf-newfolder">
                            <input
                                className="mf-input"
                                autoFocus
                                value={newFolder}
                                placeholder="Folder name"
                                onChange={(e) => setNewFolder(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter') createFolder()
                                    if (e.key === 'Escape') setNewFolder(null)
                                }}
                            />
                            <Btn icon="check" label="Create folder" onClick={createFolder} on />
                            <Btn icon="close" label="Cancel" onClick={() => setNewFolder(null)} />
                        </span>
                    )}
                    <span className="mf-sp" />
                    <span className="mf-note">Feed order, folder order and empty folders stay in this browser. Everything else syncs.</span>
                </div>

                <div className="mf-sortbar" role="group" aria-label="Sort feeds">
                    <span>Sort feeds</span>
                    {SORTS.map(([k, label]) => (
                        <button type="button" key={k} className={`mf-chip${sort === k ? ' on' : ''}`} aria-pressed={sort === k} onClick={() => setSort(k)}>
                            {label}
                        </button>
                    ))}
                    {cardTags.length > 1 ? (
                        <span className="mf-foldall">
                            <button
                                type="button"
                                className="mf-btn ghost"
                                disabled={filtering || !closedTags.some((t) => cardTags.includes(t))}
                                onClick={() => setClosedTags((t) => t.filter((x) => !cardTags.includes(x)))}
                            >
                                <Icon name="unfoldMore" size={18} />
                                <span>Expand all</span>
                            </button>
                            <button
                                type="button"
                                className="mf-btn ghost"
                                disabled={filtering || cardTags.every((t) => closedTags.includes(t))}
                                onClick={() => setClosedTags((t) => [...new Set([...t, ...cardTags])])}
                            >
                                <Icon name="unfoldLess" size={18} />
                                <span>Collapse all</span>
                            </button>
                        </span>
                    ) : null}
                </div>

                {selected.size ? (
                    <div className="mf-bulk">
                        <b>{selected.size} selected</b>
                        <span>Move to</span>
                        <select className="mf-select" value={PICK} onChange={(e) => moveSelected(e.target.value)} aria-label="Move selected feeds to">
                            <option value={PICK} disabled>
                                Pick a folder…
                            </option>
                            {allTags.map((t) => (
                                <option key={t} value={t}>
                                    {t}
                                </option>
                            ))}
                            <option value="">No folder</option>
                        </select>
                        <button type="button" className="mf-btn danger" onClick={() => unsubscribe([...selected])}>
                            <Icon name="delete" size={18} />
                            <span>Unsubscribe</span>
                        </button>
                        <button type="button" className="mf-btn ghost" onClick={() => setSelected(new Set())}>
                            Clear
                        </button>
                    </div>
                ) : null}

                <div className="mf-colhead">
                    <span />
                    <input
                        type="checkbox"
                        className="mf-cb"
                        checked={allVisibleSelected}
                        onChange={() => setSelected(allVisibleSelected ? new Set() : new Set(visibleUrls))}
                        aria-label="Select all shown feeds"
                    />
                    <span />
                    <span>Feed</span>
                    <span>Folder</span>
                    <span>Opens in</span>
                    <span className="c">Full article</span>
                    <span>Activity ({STATS_DAYS} days)</span>
                    <span className="r">Unread</span>
                    <span className="r">Actions</span>
                </div>

                {shownFolders.map(([tag, list, shown]) => folderCard(tag, list, shown))}
                {p.feeds.length > 0 && !visibleUrls.length && (sort === 'errors' || sort === 'stale' || q) ? (
                    <p className="mf-nothing">
                        {q ? 'No feeds match.' : sort === 'errors' ? 'No feed has an error right now.' : 'Every feed posted in the last 30 days.'}
                    </p>
                ) : null}
                {!p.feeds.length ? <p className="mf-nothing">No feeds yet. Paste an address above, or import an OPML file.</p> : null}
            </div>
        </section>
    )
}
