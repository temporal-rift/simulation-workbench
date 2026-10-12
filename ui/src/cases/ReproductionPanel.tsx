import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { ApiProblemError } from '../api/client'
import type { Divergence, Reproduction } from '../api/generated'
import { describeFailure } from '../api/problems'
import { RUN_POLL_MS, reproduceCommand, useApiClient } from '../api/queries'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { ScopedButton } from '../components/ScopedButton'
import { DIVERGENCE_KINDS } from './divergenceKinds'
import { FactList } from './FactList'

const isFinal = (reproduction: Reproduction | undefined) =>
  reproduction !== undefined && reproduction.state !== 'QUEUED' && reproduction.state !== 'RUNNING'

interface Props {
  readonly runId: string
  readonly caseId: string
}

/**
 * Requests a reproduction of a saved case and follows it. There is no polling endpoint: repeating the request
 * with the same idempotency key returns the reproduction in its current state, so the refresh is that repeat.
 */
export function ReproductionPanel({ runId, caseId }: Props) {
  const client = useApiClient()
  const announce = useAnnounce()
  // A new key is one new intent: asking again after a failed reproduction; refreshing keeps the key.
  const [key, setKey] = useState<string | null>(null)
  const query = useQuery({
    queryKey: ['reproduction', runId, caseId, key],
    queryFn: () => reproduceCommand(client, runId, caseId, key!),
    enabled: key !== null,
    refetchInterval: (current) => (isFinal(current.state.data) ? false : RUN_POLL_MS),
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  })

  const state = query.data?.state
  const previous = useRef(state)
  useEffect(() => {
    if (state && previous.current !== state) {
      announce(`Reproduction is now ${state.toLowerCase()}.`)
    }
    previous.current = state
  }, [state, announce])

  return (
    <section aria-labelledby="reproduction-heading">
      <h2 id="reproduction-heading">Reproduction</h2>
      <p>
        Reproducing replays the saved case against the pinned services and compares it with the recording. It is a check of the case, not a new
        research sample, and it never changes the case's result.
      </p>
      <ScopedButton
        scope={WRITE_SCOPE}
        aria-busy={query.isFetching}
        disabled={key !== null && !isFinal(query.data) && !query.isError}
        onAction={() => setKey(crypto.randomUUID())}
      >
        {isFinal(query.data) || query.isError ? 'Reproduce again' : 'Reproduce this case'}
      </ScopedButton>
      {key !== null && <Outcome query={query} onRetry={() => setKey(crypto.randomUUID())} />}
    </section>
  )
}

function Outcome({ query, onRetry }: { readonly query: ReturnType<typeof useQuery<Reproduction>>; readonly onRetry: () => void }) {
  if (query.data === undefined) {
    if (query.isError) {
      return <ReproductionRefusal error={query.error} />
    }
    return (
      <p role="status" aria-busy="true">
        Requesting the reproduction…
      </p>
    )
  }
  const reproduction = query.data
  return (
    <div role="status" className="reproduction">
      {query.isError && <p className="stale">Stale: the service could not be reached; showing the last state received.</p>}
      {reproduction.state === 'QUEUED' && (
        <p>
          <strong>Waiting.</strong> The reproduction is queued behind other work on the lane that played this case. This is not a failure; it starts when
          that lane is free.
        </p>
      )}
      {reproduction.state === 'RUNNING' && (
        <p>
          <strong>Running.</strong> The case is being replayed against the pinned services.
        </p>
      )}
      {reproduction.state === 'MATCH' && (
        <p>
          <strong>Match.</strong> The reproduction matched the recording exactly: every step and the final result agree.
        </p>
      )}
      {reproduction.state === 'DIVERGED' && reproduction.firstDivergence && <FirstDivergence divergence={reproduction.firstDivergence} />}
      {reproduction.state === 'DIVERGED' && !reproduction.firstDivergence && (
        <p>
          <strong>Diverged.</strong> The reproduction differed from the recording, but the service did not say where.
        </p>
      )}
      {reproduction.state === 'FAILED' && (
        <>
          <p>
            <strong>The reproduction failed.</strong> This says nothing about the case: it could not be replayed this time, and the case's result is
            unchanged.
          </p>
          <button type="button" onClick={onRetry}>
            Try again
          </button>
        </>
      )}
    </div>
  )
}

function FirstDivergence({ divergence }: { readonly divergence: Divergence }) {
  return (
    <>
      <p>
        <strong>Diverged.</strong> The first difference is at step {divergence.step}, kind <code>{divergence.kind}</code>.
      </p>
      <p>{DIVERGENCE_KINDS[divergence.kind] ?? 'The service reported a divergence of a kind this version does not describe.'}</p>
      <dl>
        <dt>Expected (the recording)</dt>
        <dd>
          <FactList value={divergence.expected} />
        </dd>
        <dt>Actual (the reproduction)</dt>
        <dd>
          <FactList value={divergence.actual} />
        </dd>
      </dl>
    </>
  )
}

function ReproductionRefusal({ error }: { readonly error: unknown }) {
  if (error instanceof ApiProblemError && error.isCode('MANIFEST_MISMATCH')) {
    return (
      <div role="alert" className="notice notice-error">
        <p>
          This case cannot be reproduced. Either the run was recorded against a configuration that no longer matches what the service holds, or the case
          has no saved result or transcript (a case that failed or never finished has none).
        </p>
      </div>
    )
  }
  return <ErrorNotice message={describeFailure(error, { action: 'reproduce the case', requiredScope: WRITE_SCOPE })} />
}
