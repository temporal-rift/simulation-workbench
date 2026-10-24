import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import type { CaseSummary, Run } from '../api/generated'
import { describeFailure } from '../api/problems'
import { PAGE_SIZE, caseQuery, runCasesQuery, useApiClient } from '../api/queries'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { isTerminalRunState } from './runView'

/** The cases that failed, each with the code and message of its last failed attempt and a link to the case. */
export function FailuresSection({ run }: { readonly run: Run }) {
  const client = useApiClient()
  const [offset, setOffset] = useState(0)
  // The list is read only once the run's own counts report a failure, so a healthy run polls no failed list.
  const query = useQuery({
    ...runCasesQuery(client, run.runId, { state: 'FAILED' }, offset, !isTerminalRunState(run.state)),
    enabled: run.counts.failed > 0,
  })
  if (run.counts.failed === 0) {
    return null
  }
  return (
    <section aria-labelledby="failures-heading">
      <h2 id="failures-heading">Failed cases</h2>
      <QueryView query={query} failure={{ action: 'load the failed cases' }}>
        {(page) => (
          <>
            {page.items.length === 0 ? (
              <p>No case has failed.</p>
            ) : (
              <ul className="failures">
                {page.items.map((summary) => (
                  <FailedCase key={summary.caseId} runId={run.runId} summary={summary} />
                ))}
              </ul>
            )}
            <Pager label="Failed case pages" offset={offset} count={page.items.length} total={page.total} pageSize={PAGE_SIZE} onOffset={setOffset} />
          </>
        )}
      </QueryView>
    </section>
  )
}

function FailedCase({ runId, summary }: { readonly runId: string; readonly summary: CaseSummary }) {
  const client = useApiClient()
  const detail = useQuery(caseQuery(client, runId, summary.caseId))
  const failure = detail.data?.attempts.findLast((attempt) => attempt.failure !== null)?.failure
  return (
    <li>
      <Link to="/runs/$runId/cases/$caseId" params={{ runId, caseId: summary.caseId }}>
        Case {summary.variantLabel}, seed {summary.seed}, {summary.playerCount} players
      </Link>
      {': '}
      {detail.isPending && <span>loading the failure…</span>}
      {detail.isError && (
        <span>
          {describeFailure(detail.error, { action: 'load the failure' })}{' '}
          <button type="button" onClick={() => void detail.refetch()}>
            Retry
          </button>
        </span>
      )}
      {detail.data &&
        (failure ? (
          <span>
            <code>{failure.code}</code> — {failure.message}
          </span>
        ) : (
          <span>no attempt recorded a failure</span>
        ))}
    </li>
  )
}
