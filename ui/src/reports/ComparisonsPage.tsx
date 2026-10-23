import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useState, type FormEvent } from 'react'
import { ApiProblemError } from '../api/client'
import type { ComparisonRequest } from '../api/generated'
import { describeFailure } from '../api/problems'
import { PAGE_SIZE, comparisonsQuery, createComparisonCommand, useApiClient } from '../api/queries'
import { useIntentKeys } from '../api/useIntentKeys'
import { WRITE_SCOPE } from '../auth/scopes'
import { useCan } from '../auth/sessionContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant, shortId } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { UUID_PATTERN } from '../routing/paths'

export function ComparisonsPage() {
  const { offset = 0 } = useSearch({ from: '/comparisons' })
  const navigate = useNavigate()
  const client = useApiClient()
  const query = useQuery(comparisonsQuery(client, offset))
  return (
    <>
      <h1>Comparisons</h1>
      <NewComparison />
      <QueryView query={query} failure={{ action: 'load the comparisons' }}>
        {(page) => (
          <>
            {page.items.length === 0 ? (
              <p>No comparison has been made yet.</p>
            ) : (
              <table>
                <caption>Comparisons, newest first</caption>
                <thead>
                  <tr>
                    <th scope="col">Comparison</th>
                    <th scope="col">Baseline</th>
                    <th scope="col">Candidate</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((item) => (
                    <tr key={item.comparisonId}>
                      <th scope="row">
                        <Link to="/comparisons/$comparisonId" params={{ comparisonId: item.comparisonId }}>
                          {shortId(item.comparisonId)}
                        </Link>
                      </th>
                      <td>
                        {item.baseline.variantLabel} of run {shortId(item.baseline.runId)}
                      </td>
                      <td>
                        {item.candidate.variantLabel} of run {shortId(item.candidate.runId)}
                      </td>
                      <td>{formatInstant(item.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <Pager
              label="Comparison pages"
              offset={offset}
              count={page.items.length}
              total={page.total}
              pageSize={PAGE_SIZE}
              onOffset={(next) => void navigate({ to: '/comparisons', search: { offset: next } })}
            />
          </>
        )}
      </QueryView>
    </>
  )
}

interface Side {
  runId: string
  variantLabel: string
}

const normalized = (side: Side): Side => ({ runId: side.runId.trim(), variantLabel: side.variantLabel.trim() })

const EMPTY: Side = { runId: '', variantLabel: '' }

function NewComparison() {
  const client = useApiClient()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const allowed = useCan(WRITE_SCOPE)
  const [baseline, setBaseline] = useState<Side>(EMPTY)
  const [candidate, setCandidate] = useState<Side>(EMPTY)
  // One key per request content: repeating the same request is the same intent, a changed one is a new intent.
  const keys = useIntentKeys()

  const create = useMutation({
    mutationFn: (request: ComparisonRequest) => {
      return createComparisonCommand(client, request, keys.keyFor(JSON.stringify(request)))
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ['comparisons'] })
      void navigate({ to: '/comparisons/$comparisonId', params: { comparisonId: created.comparisonId } })
    },
  })

  const request = { baseline: normalized(baseline), candidate: normalized(candidate) }
  const valid = [request.baseline, request.candidate].every((side) => UUID_PATTERN.test(side.runId) && side.variantLabel !== '')

  function submit(event: FormEvent) {
    event.preventDefault()
    if (allowed && valid) {
      create.mutate(request)
    }
  }

  const refusal = create.error instanceof ApiProblemError && create.error.isCode('INCOMPARABLE_RUNS') ? create.error : null

  return (
    <section aria-labelledby="new-comparison-heading">
      <h2 id="new-comparison-heading">Compare two variants</h2>
      <form onSubmit={submit}>
        <SideFields legend="Baseline" side={baseline} onChange={setBaseline} />
        <SideFields legend="Candidate" side={candidate} onChange={setCandidate} />
        <button type="submit" aria-disabled={!allowed || !valid || create.isPending} aria-busy={create.isPending}>
          Compare
        </button>
        {!allowed && <span className="hint"> Unavailable: requires the {WRITE_SCOPE} scope.</span>}
      </form>
      {refusal && (
        <div role="alert" className="notice notice-error">
          <h3>These runs cannot be compared</h3>
          <p>
            Their manifests differ in a gameplay-affecting definition, so a difference in results could not be attributed to the variant. The
            service names the difference:
          </p>
          <p>{refusal.message}</p>
        </div>
      )}
      {create.isError && !refusal && (
        <ErrorNotice message={describeFailure(create.error, { action: 'create the comparison', requiredScope: WRITE_SCOPE })} />
      )}
    </section>
  )
}

function SideFields({ legend, side, onChange }: { readonly legend: string; readonly side: Side; readonly onChange: (side: Side) => void }) {
  return (
    <fieldset>
      <legend>{legend}</legend>
      <label>
        {legend} run ID{' '}
        <input type="text" value={side.runId} onChange={(event) => onChange({ ...side, runId: event.target.value })} />
      </label>
      <label>
        {legend} variant{' '}
        <input type="text" value={side.variantLabel} onChange={(event) => onChange({ ...side, variantLabel: event.target.value })} />
      </label>
    </fieldset>
  )
}
