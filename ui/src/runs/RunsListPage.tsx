import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { PAGE_SIZE, runsQuery, useApiClient } from '../api/queries'
import type { RunState } from '../api/generated'
import { formatInstant, shortId } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { RUN_STATES, RUN_STATE_LABELS, partialNote } from './runView'

export function RunsListPage() {
  const client = useApiClient()
  const search = useSearch({ from: '/runs' })
  const { state, experimentId, offset = 0 } = search
  const navigate = useNavigate()
  const query = useQuery(runsQuery(client, { state, experimentId }, offset))
  const go = (next: { state?: RunState | undefined; experimentId?: string | undefined; offset?: number | undefined }) =>
    void navigate({ to: '/runs', search: { state, experimentId, offset: undefined, ...next } })
  return (
    <>
      <h1>Runs</h1>
      <div className="filters">
        <label>
          State{' '}
          <select value={state ?? ''} onChange={(event) => go({ state: (event.target.value || undefined) as RunState | undefined })}>
            <option value="">All</option>
            {RUN_STATES.map((value) => (
              <option key={value} value={value}>
                {RUN_STATE_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        {experimentId && (
          <p>
            Only runs of experiment <Link to="/experiments/$experimentId" params={{ experimentId }}>{shortId(experimentId)}</Link>{' '}
            <button type="button" onClick={() => go({ experimentId: undefined })}>
              Show runs of every experiment
            </button>
          </p>
        )}
      </div>
      <QueryView query={query} failure={{ action: 'load the runs' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>No run matches.</p>
          ) : (
            <>
              <table>
                <caption>Runs, newest first</caption>
                <thead>
                  <tr>
                    <th scope="col">Run</th>
                    <th scope="col">State</th>
                    <th scope="col">Cases succeeded</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((run) => (
                    <tr key={run.runId}>
                      <th scope="row">
                        <Link to="/runs/$runId" params={{ runId: run.runId }}>
                          {shortId(run.runId)}
                        </Link>
                      </th>
                      <td>{RUN_STATE_LABELS[run.state]}</td>
                      <td>
                        {run.counts.succeeded} of {run.counts.requested}
                        {partialNote(run.counts) && <span className="partial"> (partial)</span>}
                      </td>
                      <td>{formatInstant(run.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Run pages"
                offset={offset}
                count={page.items.length}
                total={page.total}
                pageSize={PAGE_SIZE}
                onOffset={(next) => go({ offset: next || undefined })}
              />
            </>
          )
        }
      </QueryView>
    </>
  )
}
