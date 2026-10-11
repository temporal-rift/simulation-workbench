import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState } from 'react'
import type { CaseState, Run } from '../api/generated'
import { PAGE_SIZE, isTerminalRunState, runCasesQuery, useApiClient } from '../api/queries'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { CASE_STATES, CASE_STATE_LABELS } from './runView'

/** The run's cases in matrix order. A result is shown only for a succeeded case; every other state says it has none. */
export function CasesSection({ run }: { readonly run: Run }) {
  const client = useApiClient()
  const [state, setState] = useState<CaseState | undefined>(undefined)
  const [offset, setOffset] = useState(0)
  const query = useQuery(runCasesQuery(client, run.runId, state, offset, !isTerminalRunState(run.state)))
  return (
    <section aria-labelledby="cases-heading">
      <h2 id="cases-heading">Cases</h2>
      <label>
        State{' '}
        <select
          value={state ?? ''}
          onChange={(event) => {
            setState((event.target.value || undefined) as CaseState | undefined)
            setOffset(0)
          }}
        >
          <option value="">All</option>
          {CASE_STATES.map((value) => (
            <option key={value} value={value}>
              {CASE_STATE_LABELS[value]}
            </option>
          ))}
        </select>
      </label>
      <QueryView query={query} failure={{ action: 'load the cases' }}>
        {(page) => (
          <>
            <table>
              <caption>Cases of this run</caption>
              <thead>
                <tr>
                  <th scope="col">Case</th>
                  <th scope="col">Players</th>
                  <th scope="col">State</th>
                  <th scope="col">Result</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((summary) => (
                  <tr key={summary.caseId}>
                    <th scope="row">
                      <Link to="/runs/$runId/cases/$caseId" params={{ runId: run.runId, caseId: summary.caseId }}>
                        {summary.variantLabel}, seed {summary.seed}
                      </Link>
                    </th>
                    <td>{summary.playerCount}</td>
                    <td>{CASE_STATE_LABELS[summary.state]}</td>
                    <td>{summary.state === 'SUCCEEDED' && summary.endReason ? summary.endReason : 'No result'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager label="Case pages" offset={offset} count={page.items.length} total={page.total} pageSize={PAGE_SIZE} onOffset={setOffset} />
          </>
        )}
      </QueryView>
    </section>
  )
}
