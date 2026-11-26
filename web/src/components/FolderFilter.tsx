import { useEffect, useRef, useState } from 'react'
import { folderFilterLabel, toggleFolder } from '../lib/folderFilter'
import { Icon } from './Icon'

/**
 * One filter chip for folders, in place of a row of folder chips: shows "All folders", the one
 * picked folder, or "2 folders", and opens a menu of check boxes. All clears the pick. The menu
 * stays open while folders are ticked; a click outside or Esc closes it.
 */
export function FolderFilter(p: {
  folders: string[]
  picked: string[]
  onChange: (picked: string[]) => void
  /** Words for this screen, e.g. "All" and "group" on the YouTube tab. */
  allLabel?: string
  noun?: string
  label?: string
}) {
  const { folders, picked, onChange, allLabel = 'All folders', noun = 'folder', label = 'Filter by folder' } = p
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const wrap = useRef<HTMLSpanElement>(null)
  const menu = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!pos) return
    menu.current?.querySelector<HTMLButtonElement>('button')?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setPos(null)
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [pos])

  const open = () => {
    const r = wrap.current?.getBoundingClientRect()
    if (r) setPos({ top: r.bottom + 4, left: Math.max(8, Math.min(r.left, window.innerWidth - 268)) })
  }
  const on = picked.length > 0

  return (
    <span ref={wrap} className="folder-filter">
      <button
        type="button"
        className={`chip folder-filter-chip${on ? ' on' : ''}`}
        aria-haspopup="menu"
        aria-expanded={!!pos}
        aria-label={`${label}: ${folderFilterLabel(picked, allLabel, noun)}`}
        title={picked.length > 1 ? picked.join(', ') : undefined}
        onClick={() => (pos ? setPos(null) : open())}
      >
        <Icon name={on ? 'check' : 'folder'} size={16} />
        <span className="folder-filter-text">{folderFilterLabel(picked, allLabel, noun)}</span>
        <Icon name="dropDown" size={18} />
      </button>
      {pos ? (
        <div className="ctx-backdrop" onPointerDown={(e) => e.target === e.currentTarget && setPos(null)} onWheel={() => setPos(null)}>
          <div ref={menu} className="ctx-menu folder-filter-menu" role="menu" aria-label={label} style={{ top: pos.top, left: pos.left, maxHeight: `calc(100vh - ${pos.top + 8}px)` }}>
            <button
              type="button"
              role="menuitemradio"
              aria-checked={!on}
              className="ctx-item"
              onClick={() => {
                onChange([])
                setPos(null)
              }}
            >
              <Icon name={on ? 'checkBoxBlank' : 'checkBox'} size={20} />
              <span className="ctx-label">{allLabel}</span>
            </button>
            <div className="ctx-sep" role="separator" />
            {folders.map((f) => {
              const checked = picked.includes(f)
              return (
                <button key={f} type="button" role="menuitemcheckbox" aria-checked={checked} className={`ctx-item${checked ? ' checked' : ''}`} onClick={() => onChange(toggleFolder(picked, f, folders))}>
                  <Icon name={checked ? 'checkBox' : 'checkBoxBlank'} size={20} />
                  <span className="ctx-label">{f}</span>
                </button>
              )
            })}
            {on ? (
              <>
                <div className="ctx-sep" role="separator" />
                <button type="button" className="ctx-item folder-filter-done" onClick={() => setPos(null)}>
                  <span className="ctx-label">Done</span>
                </button>
              </>
            ) : null}
          </div>
        </div>
      ) : null}
    </span>
  )
}
