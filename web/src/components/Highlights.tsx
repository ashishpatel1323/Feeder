import { useEffect, useMemo, useRef, useState } from 'react'
import type { Highlight, HighlightColor } from '../lib/db'
import { allQuotesText, HIGHLIGHT_COLORS, quoteText } from '../lib/highlights'
import { Icon, IconButton } from './Icon'

/** Docked bar under the article: shown for a fresh selection or when an existing highlight is tapped. */
export function HighlightBar(p: {
  /** The selected or highlighted text, for the preview line. */
  text: string
  existing: Highlight | null
  onSave: (color: HighlightColor, note: string) => void
  onShare: (color: HighlightColor, note: string) => void
  onCopyText: (note: string) => void
  onDelete: () => void
  onClose: () => void
}) {
  const [color, setColor] = useState<HighlightColor>(p.existing?.color ?? 'yellow')
  const [note, setNote] = useState(p.existing?.note ?? '')
  const existingId = p.existing?.id
  useEffect(() => {
    setColor(p.existing?.color ?? 'yellow')
    setNote(p.existing?.note ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [existingId, p.text])

  // An existing highlight saves color changes right away; a note is saved with the button
  const pick = (c: HighlightColor) => {
    setColor(c)
    if (p.existing) p.onSave(c, note)
  }
  const dirtyNote = !!p.existing && note.trim() !== p.existing.note

  return (
    <div className="hl-bar" role="toolbar" aria-label={p.existing ? 'Highlight' : 'New highlight'} onMouseDown={(e) => e.target === e.currentTarget && e.preventDefault()}>
      <div className="hl-bar-top">
        <span className="hl-bar-label">Color</span>
        {HIGHLIGHT_COLORS.map((c) => (
          <button
            key={c.id}
            type="button"
            className={`hl-swatch${color === c.id ? ' on' : ''}`}
            style={{ background: c.swatch }}
            title={c.label}
            aria-label={c.label}
            aria-pressed={color === c.id}
            // Keep the text selected while picking a color
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => pick(c.id)}
          />
        ))}
        <span className="hl-bar-quote" title={p.text}>
          “{p.text}”
        </span>
        <IconButton icon="close" label="Close" onClick={p.onClose} />
      </div>
      <input
        className="hl-note-input"
        placeholder="Add a note (optional)…"
        value={note}
        onChange={(e) => setNote(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') p.onSave(color, note)
          if (e.key === 'Escape') p.onClose()
        }}
      />
      <div className="hl-bar-actions">
        {p.existing ? (
          <button type="button" className="hl-btn primary" onClick={() => p.onSave(color, note)} disabled={!dirtyNote}>
            <Icon name="check" size={20} /> {dirtyNote ? 'Save note' : 'Saved'}
          </button>
        ) : (
          <button type="button" className="hl-btn primary" onMouseDown={(e) => e.preventDefault()} onClick={() => p.onSave(color, note)}>
            <Icon name="highlight" size={20} /> Highlight
          </button>
        )}
        <button type="button" className="hl-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => p.onShare(color, note)}>
          <Icon name="share" size={20} /> Share image
        </button>
        <button type="button" className="hl-btn" onMouseDown={(e) => e.preventDefault()} onClick={() => p.onCopyText(note)}>
          <Icon name="link" size={20} /> Copy text + link
        </button>
        {p.existing ? (
          <button type="button" className="hl-btn danger" onClick={p.onDelete}>
            <Icon name="delete" size={20} /> Delete
          </button>
        ) : null}
      </div>
    </div>
  )
}

/** One highlight with its actions, as shown in the article's list and on the Highlights page. */
export function HighlightCard(p: {
  h: Highlight
  onOpen?: () => void
  onShare: () => void
  onCopy: () => void
  onEdit?: () => void
  onDelete: () => void
  showDate?: boolean
  /** Shown with a checkbox when picking highlights to share together. */
  selected?: boolean
  onToggle?: () => void
}) {
  const { h } = p
  return (
    <div className={`hl-card hl-card-${h.color}${p.selected === false ? ' unselected' : ''}`}>
      {p.onToggle ? (
        <label className="hl-card-check">
          <input type="checkbox" checked={!!p.selected} onChange={p.onToggle} /> Include in shared
        </label>
      ) : null}
      <button type="button" className="hl-card-quote" onClick={p.onOpen} disabled={!p.onOpen} title={p.onOpen ? 'Show in the article' : undefined}>
        {h.text}
      </button>
      {h.note ? <div className="hl-card-note">{h.note}</div> : null}
      <div className="hl-card-foot">
        {p.showDate ? <span className="hl-card-date">{new Date(h.createdAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}</span> : null}
        <span className="spacer" />
        <button type="button" className="hl-mini" onClick={p.onShare} title="Share as image">
          <Icon name="image" size={16} /> Image
        </button>
        <button type="button" className="hl-mini" onClick={p.onCopy} title="Copy the quote and link">
          <Icon name="link" size={16} /> Copy
        </button>
        {p.onEdit ? (
          <button type="button" className="hl-mini" onClick={p.onEdit} title="Change the color or note">
            <Icon name="edit" size={16} /> Edit
          </button>
        ) : null}
        <button type="button" className="hl-mini" onClick={p.onDelete} title="Delete highlight" aria-label="Delete highlight">
          <Icon name="delete" size={16} />
        </button>
      </div>
    </div>
  )
}

/** The open article's highlights: a rail beside the text on wide screens, a sheet on phones. */
export function HighlightsRail(p: {
  highlights: Highlight[]
  onClose: () => void
  onJump: (h: Highlight) => void
  onShare: (h: Highlight) => void
  onEdit: (h: Highlight) => void
  onDelete: (h: Highlight) => void
  onShareMany: (hs: Highlight[]) => void
  toast: (msg: string) => void
}) {
  // Which highlights a combined share includes; all of them unless unticked
  const [off, setOff] = useState<Set<string>>(new Set())
  const picked = p.highlights.filter((h) => !off.has(h.id))
  const allOn = picked.length === p.highlights.length
  const toggle = (id: string) =>
    setOff((o) => {
      const n = new Set(o)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })
  return (
    <>
      <div className="hl-rail-scrim" onClick={p.onClose} />
      <aside className="hl-rail" aria-label="Highlights in this article">
        <div className="hl-rail-head">
          <strong>
            {p.highlights.length} highlight{p.highlights.length === 1 ? '' : 's'}
          </strong>
          <span className="spacer" />
          <IconButton icon="close" label="Hide highlights" onClick={p.onClose} />
        </div>
        {p.highlights.length > 1 ? (
          <div className="hl-rail-share">
            <label className="hl-select-all">
              <input type="checkbox" checked={allOn} onChange={() => setOff(allOn ? new Set(p.highlights.map((h) => h.id)) : new Set())} /> Select all ({picked.length}/
              {p.highlights.length})
            </label>
            <div className="hl-rail-share-btns">
              <button type="button" className="hl-btn primary" disabled={!picked.length} onClick={() => p.onShareMany(picked)}>
                <Icon name="image" size={18} /> Image + link
              </button>
              <button type="button" className="hl-btn" disabled={!picked.length} onClick={() => copyText(allQuotesText(picked), p.toast, `Copied ${picked.length} highlight${picked.length === 1 ? '' : 's'} with the link`)}>
                <Icon name="link" size={18} /> Text + link
              </button>
            </div>
          </div>
        ) : null}
        {p.highlights.length ? (
          p.highlights.map((h) => (
            <HighlightCard
              key={h.id}
              h={h}
              onOpen={() => p.onJump(h)}
              onShare={() => p.onShare(h)}
              onCopy={() => copyText(quoteText(h, h.note), p.toast)}
              onEdit={() => p.onEdit(h)}
              onDelete={() => p.onDelete(h)}
              selected={p.highlights.length > 1 ? !off.has(h.id) : undefined}
              onToggle={p.highlights.length > 1 ? () => toggle(h.id) : undefined}
            />
          ))
        ) : (
          <p className="hl-empty">Select text in the article to highlight it. Your highlights show up here.</p>
        )}
      </aside>
    </>
  )
}

/** Every highlight, newest first, grouped by article. */
export function HighlightsPage(p: {
  highlights: Highlight[]
  onBack: () => void
  onMenu: () => void
  onOpenArticle: (h: Highlight) => void
  onShare: (h: Highlight) => void
  onShareMany: (hs: Highlight[]) => void
  onDelete: (h: Highlight) => void
  toast: (msg: string) => void
}) {
  const [color, setColor] = useState<HighlightColor | 'all' | 'notes'>('all')
  const [query, setQuery] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return p.highlights
      .filter((h) => (color === 'all' ? true : color === 'notes' ? !!h.note : h.color === color))
      .filter((h) => !q || `${h.text}\n${h.note}\n${h.itemTitle}\n${h.feedTitle}`.toLowerCase().includes(q))
      .sort((a, b) => b.createdAt - a.createdAt)
  }, [p.highlights, color, query])

  const groups = useMemo(() => {
    const out: { itemId: string; title: string; source: string; items: Highlight[] }[] = []
    const byItem = new Map<string, (typeof out)[number]>()
    for (const h of shown) {
      let g = byItem.get(h.itemId)
      if (!g) {
        g = { itemId: h.itemId, title: h.itemTitle, source: h.feedTitle, items: [] }
        byItem.set(h.itemId, g)
        out.push(g)
      }
      g.items.push(h)
    }
    return out
  }, [shown])

  const count = (c: HighlightColor) => p.highlights.filter((h) => h.color === c).length

  return (
    <section className="manage-pane hl-page" aria-label="Highlights">
      <div className="mf-page">
        <div className="mf-head">
          <span className="only-narrow">
            <IconButton icon="menu" label="Feeds" onClick={p.onMenu} />
          </span>
          <h1>Highlights</h1>
          <span className="mf-meta">{p.highlights.length}</span>
          <span className="mf-sp" />
          <IconButton icon="close" label="Close highlights" onClick={p.onBack} />
        </div>
        <div className="hl-filters">
          <button type="button" className={`hl-filter${color === 'all' ? ' on' : ''}`} onClick={() => setColor('all')}>
            All {p.highlights.length}
          </button>
          {HIGHLIGHT_COLORS.map((c) => (
            <button key={c.id} type="button" className={`hl-filter${color === c.id ? ' on' : ''}`} onClick={() => setColor(c.id)} title={c.label}>
              <span className="hl-dot" style={{ background: c.swatch }} /> {count(c.id)}
            </button>
          ))}
          <button type="button" className={`hl-filter${color === 'notes' ? ' on' : ''}`} onClick={() => setColor('notes')}>
            <Icon name="note" size={16} /> With notes
          </button>
          <input ref={searchRef} className="hl-search" type="search" placeholder="Search highlights" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {groups.length ? (
          groups.map((g) => (
            <div key={g.itemId} className="hl-group">
              <div className="hl-group-bar">
                <button type="button" className="hl-group-head" onClick={() => p.onOpenArticle(g.items[0])}>
                  <span className="hl-group-title">{g.title || '(no title)'}</span>
                  <span className="hl-group-source">{g.source}</span>
                </button>
                {g.items.length > 1 ? (
                  <span className="hl-group-actions">
                    <button type="button" className="hl-mini" onClick={() => p.onShareMany(byStart(g.items))} title="All of this article's highlights in one image">
                      <Icon name="image" size={16} /> All as image + link
                    </button>
                    <button type="button" className="hl-mini" onClick={() => copyText(allQuotesText(byStart(g.items)), p.toast, `Copied ${g.items.length} highlights with the link`)}>
                      <Icon name="link" size={16} /> All as text + link
                    </button>
                  </span>
                ) : null}
              </div>
              <div className="hl-grid">
                {g.items.map((h) => (
                  <HighlightCard
                    key={h.id}
                    h={h}
                    showDate
                    onOpen={() => p.onOpenArticle(h)}
                    onShare={() => p.onShare(h)}
                    onCopy={() => copyText(quoteText(h, h.note), p.toast)}
                    onDelete={() => p.onDelete(h)}
                  />
                ))}
              </div>
            </div>
          ))
        ) : (
          <p className="hl-empty">
            {p.highlights.length ? 'No highlights match.' : 'No highlights yet. Open an article and select some text to highlight it.'}
          </p>
        )}
      </div>
    </section>
  )
}

/** Reading order within one article. */
const byStart = (hs: Highlight[]) => [...hs].sort((a, b) => a.start - b.start)

export async function copyText(text: string, toast: (msg: string) => void, done = 'Copied quote and link') {
  try {
    await navigator.clipboard.writeText(text)
    toast(done)
  } catch {
    toast("Couldn't copy to the clipboard")
  }
}
