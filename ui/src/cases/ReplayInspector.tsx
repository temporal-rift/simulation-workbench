import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { ApiProblemError } from '../api/client'
import type { Replay, ReplayStep, SeatAssignment } from '../api/generated'
import { describeFailure } from '../api/problems'
import { replayQuery, replayRootKey, useApiClient, type ReplayView } from '../api/queries'
import { OBSERVE_SCOPE } from '../auth/scopes'
import { useCan } from '../auth/sessionContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { shortDigest } from '../components/format'
import { FactList } from './FactList'

interface Props {
  readonly runId: string
  readonly caseId: string
  readonly assignments: readonly SeatAssignment[]
}

const viewKey = (view: ReplayView) => (view.perspective === 'PLAYER' ? `player-${view.seatIndex}` : 'observer')

/**
 * Inspects a case's replay from exactly one perspective at a time. Changing perspective or seat drops every
 * cached replay page of the case before the next one is requested, and the page state is keyed by the view, so
 * nothing read as one perspective can be shown or served as another.
 */
export function ReplayInspector({ runId, caseId, assignments }: Props) {
  const queryClient = useQueryClient()
  const canObserve = useCan(OBSERVE_SCOPE)
  const [view, setView] = useState<ReplayView | null>(null)
  const [seat, setSeat] = useState<number>(assignments[0]?.seatIndex ?? 0)
  const [observerDenied, setObserverDenied] = useState(false)

  useEffect(() => () => queryClient.removeQueries({ queryKey: replayRootKey(runId, caseId) }), [queryClient, runId, caseId])

  function show(next: ReplayView | null) {
    queryClient.removeQueries({ queryKey: replayRootKey(runId, caseId) })
    setObserverDenied(false)
    setView(next)
  }

  function showObserver() {
    if (canObserve) {
      show({ perspective: 'OBSERVER' })
    } else {
      // Nothing is requested without the scope; the denial is shown as the labelled outcome.
      show(null)
      setObserverDenied(true)
    }
  }

  return (
    <section aria-labelledby="replay-heading">
      <h2 id="replay-heading">Replay</h2>
      <div className="filters">
        <label>
          Seat{' '}
          <select value={seat} onChange={(event) => setSeat(Number(event.target.value))}>
            {assignments.map((assignment) => (
              <option key={assignment.seatIndex} value={assignment.seatIndex}>
                Seat {assignment.seatIndex} ({assignment.faction})
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => show({ perspective: 'PLAYER', seatIndex: seat })}>
          View as player
        </button>
        <button type="button" onClick={showObserver}>
          View as observer…
        </button>
      </div>
      <p className="hint">
        A player view shows only what that seat was entitled to know at each step. Observer evidence shows every seat and the raw events, needs the{' '}
        {OBSERVE_SCOPE} scope, and is never used by bot policies.
      </p>
      {observerDenied && (
        <div role="alert" className="notice notice-error observer-denied">
          <p>
            <strong>Observer access denied.</strong> Observer evidence requires the {OBSERVE_SCOPE} scope, which this session lacks. No observer data
            is shown.
          </p>
        </div>
      )}
      {view && <ReplayPages key={viewKey(view)} runId={runId} caseId={caseId} view={view} />}
    </section>
  )
}

function ReplayPages({ runId, caseId, view }: { readonly runId: string; readonly caseId: string; readonly view: ReplayView }) {
  const client = useApiClient()
  // The `afterStep` of every page read so far, so earlier pages can be revisited; each is the previous page's `nextStep`.
  const [cursors, setCursors] = useState<(number | undefined)[]>([undefined])
  const afterStep = cursors[cursors.length - 1]
  const query = useQuery(replayQuery(client, runId, caseId, view, afterStep))

  if (query.isError) {
    return <ReplayFailure error={query.error} view={view} onRetry={() => void query.refetch()} />
  }
  if (!query.data) {
    return (
      <p role="status" aria-busy="true">
        Loading the replay…
      </p>
    )
  }
  const replay = query.data
  const paging = (
    <nav aria-label="Replay pages" className="pager">
      <span>{replay.steps.length === 0 ? 'No steps on this page' : `Steps ${replay.steps[0]!.step} to ${replay.steps[replay.steps.length - 1]!.step}`}</span>
      <button type="button" disabled={cursors.length === 1} onClick={() => setCursors((current) => current.slice(0, -1))}>
        Earlier steps
      </button>
      <button type="button" disabled={replay.nextStep === null} onClick={() => setCursors((current) => [...current, replay.nextStep ?? undefined])}>
        Later steps
      </button>
      {replay.nextStep !== null && <span>More steps follow.</span>}
    </nav>
  )
  return view.perspective === 'PLAYER' ? (
    <PlayerReplay replay={replay} seatIndex={view.seatIndex} paging={paging} />
  ) : (
    <ObserverReplay replay={replay} paging={paging} />
  )
}

function ReplayFailure({ error, view, onRetry }: { readonly error: unknown; readonly view: ReplayView; readonly onRetry: () => void }) {
  if (error instanceof ApiProblemError && error.isCode('INSUFFICIENT_SCOPE') && view.perspective === 'OBSERVER') {
    return (
      <div role="alert" className="notice notice-error observer-denied">
        <p>
          <strong>Observer access denied.</strong> The service requires the {OBSERVE_SCOPE} scope for observer evidence. No observer data is shown.
        </p>
      </div>
    )
  }
  return <ErrorNotice message={describeFailure(error, { action: 'load the replay', requiredScope: OBSERVE_SCOPE })} onRetry={onRetry} />
}

function PlayerReplay({ replay, seatIndex, paging }: { readonly replay: Replay; readonly seatIndex: number; readonly paging: ReactNode }) {
  return (
    <section aria-labelledby="player-replay-heading" className="player-replay">
      <h3 id="player-replay-heading">Player view: seat {seatIndex}</h3>
      <p>
        Manifest <code title={replay.manifestDigest}>{shortDigest(replay.manifestDigest)}</code>. Step numbers belong to the whole case, so steps of other
        seats leave gaps in the numbering.
      </p>
      {paging}
      <StepList steps={replay.steps} label={`Steps of seat ${seatIndex}`} level={4} />
    </section>
  )
}

function ObserverReplay({ replay, paging }: { readonly replay: Replay; readonly paging: ReactNode }) {
  const seatSteps = replay.steps.filter((step) => step.phase !== 'EVENT')
  const bySource = new Map<string, ReplayStep[]>()
  for (const step of replay.steps.filter((entry) => entry.phase === 'EVENT')) {
    const source = String((step.observations as { source?: unknown } | null)?.source ?? 'unknown source')
    bySource.set(source, [...(bySource.get(source) ?? []), step])
  }
  return (
    <section aria-labelledby="observer-replay-heading" className="observer-replay">
      <h3 id="observer-replay-heading">Observer evidence</h3>
      <p className="notice notice-warning" role="note">
        Observer evidence: every seat and the raw events, not what any player knew. It is for inspection only and never feeds bot policies.
      </p>
      {paging}
      <h4>Steps of every seat</h4>
      <StepList steps={seatSteps} label="Steps of every seat" level={5} />
      <h4>Raw events by source</h4>
      <p className="hint">No order across sources exists, so each source is its own list, in the order it delivered.</p>
      {bySource.size === 0 && <p>No raw events on this page.</p>}
      {[...bySource.entries()].map(([source, events]) => (
        <StepList key={source} steps={events} label={`Events from ${source}`} level={5} />
      ))}
    </section>
  )
}

/** Step headings sit one level below their section heading: 4 under the player view, 5 under the observer's subsections. */
function StepList({ steps, label, level }: { readonly steps: readonly ReplayStep[]; readonly label: string; readonly level: 4 | 5 }) {
  const Heading = `h${level}` as const
  if (steps.length === 0) {
    return <p>No steps on this page.</p>
  }
  return (
    <ol className="steps" aria-label={label}>
      {steps.map((step) => (
        <li key={step.step} value={step.step}>
          <Heading>
            Step {step.step} · {step.phase}
            {step.eraNumber !== null && ` · era ${step.eraNumber}`}
            {step.roundNumber !== null && ` · round ${step.roundNumber}`}
          </Heading>
          <p className="hint">{step.logicalTime}</p>
          {step.observations && (
            <>
              <strong>Observed</strong>
              <FactList value={step.observations} />
            </>
          )}
          {step.decision && (
            <>
              <strong>Decision</strong>
              <FactList value={step.decision} />
            </>
          )}
          {step.acceptedResult && (
            <>
              <strong>Result</strong>
              <FactList value={step.acceptedResult} />
            </>
          )}
        </li>
      ))}
    </ol>
  )
}
