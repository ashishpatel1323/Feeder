/**
 * The one delete confirmation used everywhere in the web app (Android has the same dialog in
 * ui/compose/components/ConfirmDeleteDialog.kt). Title like "Delete playlist?", the item named in
 * bold, then a line saying where it is deleted, Cancel and a red destructive button.
 *
 *   if (!(await confirmDelete({ title: 'Delete playlist?', itemName: p.name, scope: 'all-devices' }))) return
 *
 * Built with plain DOM so it can be awaited from any handler, inside or outside React.
 */
import { getSyncState } from './sync2'

/** Where the delete takes effect. */
export type DeleteScope = 'all-devices' | 'this-device' | 'dropbox'

export interface ConfirmDeleteOptions {
  /** "Delete playlist?" */
  title: string
  /** Shown in bold: the item's name, or "3 items" for bulk deletes (see itemCount). */
  itemName?: string
  scope: DeleteScope
  /** Destructive button label. Default "Delete". */
  confirmLabel?: 'Delete' | 'Remove' | 'Unsubscribe' | 'Clear' | string
  /** Optional sentence after the scope line, e.g. what stays behind. */
  extra?: string
  /** Dropbox scope only: false when there is no copy on this device. Default true. */
  keepsLocalCopy?: boolean
}

export const itemCount = (n: number, noun = 'item', plural = `${noun}s`) => `${n} ${n === 1 ? noun : plural}`

export async function scopeText(scope: DeleteScope, keepsLocalCopy = true): Promise<string> {
  if (scope === 'this-device') return 'Only removes it from this device. Other devices keep it.'
  if (scope === 'dropbox') return keepsLocalCopy ? 'Deletes the file from Dropbox. The copy on this device stays.' : 'Deletes the file from Dropbox, for all your devices.'
  let syncOn = true
  try {
    syncOn = (await getSyncState()) != null
  } catch {
    /* unknown: keep the cross-device line */
  }
  return syncOn ? 'This deletes it on all your synced devices.' : 'This deletes it.'
}

let open: Promise<boolean> | null = null

export function confirmDelete(opts: ConfirmDeleteOptions): Promise<boolean> {
  // One at a time: a second request while one is showing waits for (and shares) its answer.
  if (open) return open
  open = show(opts).finally(() => {
    open = null
  })
  return open
}

async function show(opts: ConfirmDeleteOptions): Promise<boolean> {
  const scope = await scopeText(opts.scope, opts.keepsLocalCopy)
  return new Promise<boolean>((resolve) => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    const backdrop = document.createElement('div')
    backdrop.className = 'modal-backdrop confirm-delete-backdrop'

    const dialog = document.createElement('div')
    dialog.className = 'modal confirm-delete'
    dialog.setAttribute('role', 'alertdialog')
    dialog.setAttribute('aria-modal', 'true')
    const titleId = `cd-title-${Date.now()}`
    const bodyId = `${titleId}-body`
    dialog.setAttribute('aria-labelledby', titleId)
    dialog.setAttribute('aria-describedby', bodyId)

    const h2 = document.createElement('h2')
    h2.id = titleId
    h2.textContent = opts.title

    const body = document.createElement('div')
    body.id = bodyId
    body.className = 'confirm-delete-body'
    if (opts.itemName) {
      const name = document.createElement('p')
      name.className = 'confirm-delete-name'
      name.textContent = opts.itemName
      body.appendChild(name)
    }
    const scopeP = document.createElement('p')
    scopeP.className = 'confirm-delete-scope'
    scopeP.textContent = opts.extra ? `${scope} ${opts.extra}` : scope
    body.appendChild(scopeP)

    const actions = document.createElement('div')
    actions.className = 'confirm-delete-actions'
    const cancel = document.createElement('button')
    cancel.type = 'button'
    cancel.className = 'mf-btn ghost'
    cancel.textContent = 'Cancel'
    const ok = document.createElement('button')
    ok.type = 'button'
    ok.className = 'mf-btn ghost confirm-delete-ok'
    ok.textContent = opts.confirmLabel ?? 'Delete'
    actions.append(cancel, ok)

    dialog.append(h2, body, actions)
    backdrop.appendChild(dialog)
    document.body.appendChild(backdrop)

    const done = (answer: boolean) => {
      window.removeEventListener('keydown', onKey, true)
      backdrop.remove()
      previouslyFocused?.focus?.()
      resolve(answer)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        done(false)
      } else if (e.key === 'Tab') {
        // Keep focus inside the two buttons.
        e.preventDefault()
        ;(document.activeElement === cancel ? ok : cancel).focus()
      } else {
        // Don't let app keyboard shortcuts (j/k, g-chords…) fire behind the dialog.
        e.stopPropagation()
      }
    }
    window.addEventListener('keydown', onKey, true)
    cancel.addEventListener('click', () => done(false))
    ok.addEventListener('click', () => done(true))
    backdrop.addEventListener('mousedown', (e) => {
      if (e.target === backdrop) done(false)
    })
    // Cancel gets focus so Enter never deletes by accident.
    cancel.focus()
  })
}

/** Highlights: the start of the highlighted text stands in for a name. */
export function confirmDeleteHighlight(text: string): Promise<boolean> {
  const t = text.replace(/\s+/g, ' ').trim()
  return confirmDelete({ title: 'Delete highlight?', itemName: `“${t.length > 90 ? `${t.slice(0, 90)}…` : t}”`, scope: 'all-devices', extra: 'Its note goes too.' })
}
