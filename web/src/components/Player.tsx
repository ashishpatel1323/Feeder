import { readAloud, type ReadAloudState } from '../lib/readaloud'
import { updateSettings, type Settings } from '../lib/settings'
import { Icon, IconButton } from './Icon'

const SPEEDS = [0.8, 1, 1.2, 1.4, 1.6, 1.8, 2]

interface Props {
  state: ReadAloudState
  settings: Settings
  hasPrev: boolean
  hasNext: boolean
  nextTitle: string | null
  onPrev: () => void
  onNext: () => void
}

/** The compact one-bar player (design C from the Android fork), with the up-next strip. */
export function Player({ state, settings, hasPrev, hasNext, nextTitle, onPrev, onNext }: Props) {
  const cycleSpeed = () => {
    const i = SPEEDS.findIndex((s) => s >= state.rate - 0.001)
    const next = SPEEDS[(i + 1) % SPEEDS.length]
    readAloud.setRate(next)
    updateSettings({ readAloudRate: next })
  }
  const progress = state.sentences ? Math.round(((state.sentence + 1) / state.sentences) * 100) : 0

  return (
    <div className="player" role="region" aria-label="Read aloud">
      <div className="player-progress" style={{ width: `${progress}%` }} />
      {nextTitle ? (
        <div className="up-next">
          <span className="up-next-label">Up next</span>
          <span className="up-next-title">{nextTitle}</span>
          <button
            className={`chip small${settings.readAloudAutoContinue ? ' on' : ''}`}
            aria-pressed={settings.readAloudAutoContinue}
            onClick={() => updateSettings({ readAloudAutoContinue: !settings.readAloudAutoContinue })}
            title="Keep reading the next article when this one ends"
          >
            <Icon name="repeat" size={14} /> Auto-continue
          </button>
        </div>
      ) : null}
      <div className="player-bar">
        <IconButton icon="skipPrev" label="Previous article" onClick={onPrev} disabled={!hasPrev} />
        <IconButton icon="replay" label="Previous sentence" onClick={() => readAloud.skip(-1)} />
        <button className="play-btn" onClick={() => readAloud.toggle()} aria-label={state.status === 'playing' ? 'Pause' : 'Play'}>
          <Icon name={state.status === 'playing' ? 'pause' : 'play'} size={28} />
        </button>
        <IconButton icon="forward" label="Next sentence" onClick={() => readAloud.skip(1)} />
        <IconButton icon="skipNext" label="Next article" onClick={onNext} disabled={!hasNext} />
        <button className="chip speed" onClick={cycleSpeed} title="Speed">
          {state.rate.toFixed(1).replace(/\.0$/, '')}×
        </button>
        <span className="player-info" title="Whether this voice reports word timings">
          {state.sentence + 1}/{state.sentences}
          {state.wordTiming ? ` · ${state.wordTiming === 'exact' ? 'exact' : 'estimated'} words` : ''}
        </span>
        <IconButton icon="close" label="Stop" onClick={() => readAloud.stop()} />
      </div>
    </div>
  )
}
