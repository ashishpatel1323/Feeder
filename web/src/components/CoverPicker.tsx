import { useEffect, useState } from 'react'
import { proxiedUrl } from '../lib/api'
import { searchCovers, setCover, type CoverCandidate } from '../lib/shelfMarks'
import { Modal } from './Dialogs'
import { Icon } from './Icon'

/**
 * "Cover from the Internet" (as in the Voice app): searches for [query] (title and author, editable)
 * and puts the picked picture on the book [markKey] on every device. Same as Android's CoverPickerDialog.
 */
export function CoverPicker({ markKey, query, audiobook, onClose, toast }: { markKey: string; query: string; audiobook: boolean; onClose: () => void; toast: (m: string) => void }) {
  const [text, setText] = useState(query)
  const [searched, setSearched] = useState(query)
  const [results, setResults] = useState<CoverCandidate[] | null>(null)
  const [applying, setApplying] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)
  useEffect(() => {
    let live = true
    setResults(null)
    setSearching(true)
    void searchCovers(searched, audiobook, (some) => live && some.length && setResults(some)).then((r) => {
      if (!live) return
      setResults(r)
      setSearching(false)
    })
    return () => {
      live = false
    }
  }, [searched, audiobook])
  const pick = async (c: CoverCandidate) => {
    setApplying(c.url)
    const ok = await setCover(markKey, c.url)
    setApplying(null)
    if (ok) {
      toast('Cover changed')
      onClose()
    } else toast('Couldn’t get that picture. Try another one.')
  }
  return (
    <Modal title="Cover from the Internet" onClose={onClose} wide>
      <form
        className="cover-search"
        onSubmit={(e) => {
          e.preventDefault()
          setSearched(text)
        }}
      >
        <input type="search" value={text} onChange={(e) => setText(e.target.value)} aria-label="Title and author" placeholder="Title and author" autoFocus />
        <button type="submit" className="mf-btn">
          <Icon name="search" size={18} />
          Search
        </button>
      </form>
      {applying ? <progress className="cover-progress" /> : null}
      {searching && results ? <p className="muted small">Looking for more covers…</p> : null}
      {results === null ? (
        <p className="muted">Looking for covers…</p>
      ) : !results.length ? (
        <p className="muted">No covers found. Try other words.</p>
      ) : (
        <div className="cover-grid">
          {results.map((c) => (
            <button key={c.url} type="button" className="cover-choice" title={c.label} disabled={!!applying} onClick={() => void pick(c)}>
              <img src={proxiedUrl(c.thumb)} alt={c.label} loading="lazy" />
            </button>
          ))}
        </div>
      )}
    </Modal>
  )
}
