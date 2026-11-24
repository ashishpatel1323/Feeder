import { useEffect, useRef, useState } from 'react'
import { Icon, IconButton, type IconName } from './Icon'

export type MoreMenuEntry =
  | {
      icon: IconName
      label: string
      onClick: () => void
      /** Keyboard shortcut, shown at the end of the line */
      kbd?: string
      /** On/off entries show a check mark while on */
      checked?: boolean
      danger?: boolean
      disabled?: boolean
      hidden?: boolean
    }
  | 'separator'

/**
 * A ⋮ button with a Material 3 style menu: the home for an item's less used actions, so a bar
 * keeps only its everyday ones. Opens below the button, aligned to its right edge.
 */
export function MoreMenu({ label = 'More', entries }: { label?: string; entries: MoreMenuEntry[] }) {
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null)
  const wrap = useRef<HTMLSpanElement>(null)
  const menu = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!pos) return
    menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
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
    if (r) setPos({ top: r.bottom + 4, right: Math.max(8, window.innerWidth - r.right) })
  }

  // Drop separators that would sit at an edge or next to another once hidden entries are gone
  const shown = entries.filter((e) => e === 'separator' || !e.hidden)
  const tidy = shown.filter((e, i) => e !== 'separator' || (i > 0 && i < shown.length - 1 && shown[i - 1] !== 'separator'))

  return (
    <span ref={wrap} className="more-menu">
      <IconButton icon="moreVert" label={label} title={`${label} actions`} onClick={() => (pos ? setPos(null) : open())} active={!!pos} />
      {pos ? (
        <div className="ctx-backdrop" onPointerDown={(e) => e.target === e.currentTarget && setPos(null)} onWheel={() => setPos(null)}>
          <div
            ref={menu}
            className="ctx-menu more-menu-list"
            role="menu"
            aria-label={label}
            style={{ top: pos.top, right: pos.right, maxHeight: `calc(100vh - ${pos.top + 8}px)` }}
          >
            {tidy.map((e, i) =>
              e === 'separator' ? (
                <div key={`sep${i}`} className="ctx-sep" role="separator" />
              ) : (
                <button
                  key={e.label}
                  type="button"
                  role={e.checked === undefined ? 'menuitem' : 'menuitemcheckbox'}
                  aria-checked={e.checked}
                  className={`ctx-item${e.danger ? ' danger' : ''}`}
                  disabled={e.disabled}
                  onClick={() => {
                    setPos(null)
                    e.onClick()
                  }}
                >
                  <Icon name={e.icon} size={20} />
                  <span className="ctx-label">{e.label}</span>
                  {e.checked ? <Icon name="check" size={18} /> : null}
                  {e.kbd ? <kbd className="ctx-kbd">{e.kbd}</kbd> : null}
                </button>
              ),
            )}
          </div>
        </div>
      ) : null}
    </span>
  )
}
