import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import { ApiProblemError } from '../api/client'
import type { Run } from '../api/generated'
import { describeFailure } from '../api/problems'
import { cancelRunCommand, isTerminalRunState, resumeRunCommand, runQuery, useApiClient } from '../api/queries'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant } from '../components/format'
import { QueryView } from '../components/QueryView'
import { ScopedButton } from '../components/ScopedButton'
import { CasesSection } from './CasesSection'
import { FailuresSection } from './FailuresSection'
import { RUN_STATE_LABELS, isCancellable, isResumable, partialNote } from './runView'

type Command = 'cancel' | 'resume'

export function RunPage() {
  const { runId } = useParams({ from: '/runs/$runId' })
  const client = useApiClient()
  const query = useQuery(runQuery(client, runId))
  return (
    <QueryView query={query} failure={{ action: 'load the run' }}>
      {(run, { stale }) => <RunDetail run={run} stale={stale} />}
    </QueryView>
  )
}

function RunDetail({ run, stale }: { readonly run: Run; readonly stale: boolean }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  const keys = useRef<Partial<Record<Command, string>>>({})
  const [refusal, setRefusal] = useState<Command | null>(null)
  const previous = useRef(run.state)

  useEffect(() => {
    if (previous.current !== run.state) {
      announce(`Run is now ${RUN_STATE_LABELS[run.state].toLowerCase()}.`)
      previous.current = run.state
    }
  }, [run.state, announce])

  const command = useMutation({
    mutationFn: (name: Command) => {
      keys.current[name] ??= crypto.randomUUID()
      const key = keys.current[name]
      return name === 'cancel' ? cancelRunCommand(client, run.runId, key) : resumeRunCommand(client, run.runId, key)
    },
    onMutate: () => setRefusal(null),
    onSuccess: (updated, name) => {
      delete keys.current[name]
      queryClient.setQueryData(['run', run.runId], updated)
      void queryClient.invalidateQueries({ queryKey: ['runs'] })
      announce(name === 'cancel' ? 'Cancellation requested.' : 'Run resumed.')
    },
    onError: async (error, name) => {
      if (error instanceof ApiProblemError && error.isCode('INVALID_RUN_STATE')) {
        // The page was showing an outdated state: read the current one so the refusal can name it.
        delete keys.current[name]
        await queryClient.refetchQueries({ queryKey: ['run', run.runId] })
        setRefusal(name)
      }
    },
  })

  const note = partialNote(run.counts)
  const terminal = isTerminalRunState(run.state)
  const failedCommand = command.isError && !refusal ? command.variables : null

  return (
    <>
      <h1>
        Run <code>{run.runId}</code>
      </h1>
      <p>
        <Link to="/experiments/$experimentId" params={{ experimentId: run.experimentId }}>
          Experiment
        </Link>
        {' · '}
        <Link to="/runs/$runId/report" params={{ runId: run.runId }}>
          Report
        </Link>
        {' · '}
        <strong>State: {RUN_STATE_LABELS[run.state]}</strong>
        {stale && <span className="stale"> (stale: the service could not be reached)</span>}
        {!terminal && !stale && <span className="hint"> Refreshing while the run is open.</span>}
      </p>
      <dl>
        <dt>Created</dt>
        <dd>{formatInstant(run.createdAt)}</dd>
        <dt>Started</dt>
        <dd>{formatInstant(run.startedAt)}</dd>
        <dt>Finished</dt>
        <dd>{formatInstant(run.finishedAt)}</dd>
      </dl>
      {note && (
        <p role="status" className="notice notice-warning">
          {note}
        </p>
      )}
      <table>
        <caption>Case counts</caption>
        <thead>
          <tr>
            <th scope="col">Requested</th>
            <th scope="col">Pending</th>
            <th scope="col">Running</th>
            <th scope="col">Succeeded</th>
            <th scope="col">Failed</th>
            <th scope="col">Cancelled</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{run.counts.requested}</td>
            <td>{run.counts.pending}</td>
            <td>{run.counts.running}</td>
            <td>{run.counts.succeeded}</td>
            <td>{run.counts.failed}</td>
            <td>{run.counts.cancelled}</td>
          </tr>
        </tbody>
      </table>
      {run.failure && (
        <div className="notice notice-error">
          <h2>Run failure</h2>
          <p>
            <code>{run.failure.code}</code>: {run.failure.message}
          </p>
        </div>
      )}
      <div className="actions">
        {isCancellable(run.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('cancel')}>
            Cancel run
          </ScopedButton>
        )}
        {isResumable(run.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('resume')}>
            Resume run
          </ScopedButton>
        )}
      </div>
      {refusal && (
        <p role="alert" className="notice notice-error">
          Could not {refusal} the run: it is {RUN_STATE_LABELS[run.state].toLowerCase()}.{' '}
          {refusal === 'resume' ? 'A run can be resumed only while it is interrupted.' : 'A run can be cancelled only until it settles.'}
        </p>
      )}
      {failedCommand && (
        <ErrorNotice
          message={describeFailure(command.error, { action: `${failedCommand} the run`, requiredScope: WRITE_SCOPE })}
          onRetry={() => command.mutate(failedCommand)}
        />
      )}
      <FailuresSection run={run} />
      <CasesSection run={run} />
    </>
  )
}
