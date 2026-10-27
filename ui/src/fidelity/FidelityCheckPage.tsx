import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import type { FidelityCase, FidelityCheck, FidelityEnvironment } from '../api/generated'
import { describeFailure } from '../api/problems'
import { PAGE_SIZE, fidelityCasesQuery, fidelityCheckQuery, unmatchedFidelityCases, useApiClient } from '../api/queries'
import { saveBlob } from '../components/download'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { fidelityReport, reportFilename } from './fidelityReport'
import { FIDELITY_RESULTS, FIDELITY_RESULT_LABELS, FIDELITY_STATE_LABELS, FIDELITY_VARIANTS, isOpenCheck } from './fidelityView'

export function FidelityCheckPage() {
  const { checkId } = useParams({ from: '/fidelity/$checkId' })
  const client = useApiClient()
  const query = useQuery(fidelityCheckQuery(client, checkId))
  return (
    <QueryView query={query} failure={{ action: 'load the fidelity check' }}>
      {(check) => <Check check={check} />}
    </QueryView>
  )
}

function Check({ check }: { readonly check: FidelityCheck }) {
  const { counts } = check
  return (
    <>
      <h1>Fidelity check {check.fidelityCheckId.slice(0, 8)}</h1>
      <p>
        <Link to="/fidelity">All fidelity checks</Link>
      </p>
      <dl>
        <dt>Seed</dt>
        <dd>{check.seed}</dd>
        <dt>State</dt>
        <dd>{FIDELITY_STATE_LABELS[check.state]}</dd>
        <dt>Verdict</dt>
        <dd>{check.verdict ?? 'Not yet decided'}</dd>
        <dt>Simulator run</dt>
        <dd>
          {check.runId ? (
            <Link to="/runs/$runId" params={{ runId: check.runId }}>
              Run {check.runId.slice(0, 8)}
            </Link>
          ) : (
            'Not started yet'
          )}
        </dd>
        <dt>Created</dt>
        <dd>{formatInstant(check.createdAt)}</dd>
        <dt>Started</dt>
        <dd>{formatInstant(check.startedAt)}</dd>
        <dt>Finished</dt>
        <dd>{formatInstant(check.finishedAt)}</dd>
      </dl>
      {check.failure && (
        <div role="alert" className="notice notice-error">
          <p>
            The check could not complete: {check.failure.message} <code>{check.failure.code}</code>
          </p>
        </div>
      )}
      {check.state === 'PASSED' && <p className="notice">Every case matched: the simulator played the same game as the real lane.</p>}
      {check.state === 'FAILED' && !check.failure && (
        <p className="notice notice-warning">At least one case differed or could not be reproduced. No difference is tolerated.</p>
      )}
      <ReportButton check={check} />
      <h2>Case counts</h2>
      <table>
        <caption>Case counts</caption>
        <thead>
          <tr>
            <th scope="col">Total</th>
            <th scope="col">Matched</th>
            <th scope="col">Diverged</th>
            <th scope="col">Cannot be reproduced</th>
            <th scope="col">Pending</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{counts.total}</td>
            <td>{counts.matched}</td>
            <td>{counts.diverged}</td>
            <td>{counts.unreproducible}</td>
            <td>{counts.pending}</td>
          </tr>
        </tbody>
      </table>
      <h2>Environments</h2>
      <Environment title="Simulator" environment={check.simulator} />
      {check.lane && <Environment title="Real lane" environment={check.lane} />}
      <h2>Variants</h2>
      <table>
        <caption>Variants played</caption>
        <thead>
          <tr>
            <th scope="col">Label</th>
            <th scope="col">Effective rules digest</th>
            <th scope="col">Overrides</th>
          </tr>
        </thead>
        <tbody>
          {check.variants.map((variant) => (
            <tr key={variant.label}>
              <th scope="row">{variant.label}</th>
              <td>
                <code>{variant.effectiveRulesDigest}</code>
              </td>
              <td>
                {Object.keys(variant.overrides).length === 0 ? (
                  'None'
                ) : (
                  <ul>
                    {Object.entries(variant.overrides).map(([key, value]) => (
                      <li key={key}>
                        <code>{key}</code> = {value}
                      </li>
                    ))}
                  </ul>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Cases check={check} />
    </>
  )
}

/** Hands the report of the check to the browser as a file, to be committed beside the design records. */
function ReportButton({ check }: { readonly check: FidelityCheck }) {
  const client = useApiClient()
  const download = useMutation({
    mutationFn: async () => {
      const unmatched = await unmatchedFidelityCases(client, check.fidelityCheckId)
      saveBlob(new Blob([fidelityReport(check, unmatched)], { type: 'text/markdown' }), reportFilename(check))
    },
  })
  return (
    <div className="actions">
      <button type="button" aria-busy={download.isPending} disabled={download.isPending} onClick={() => download.mutate()}>
        Download report
      </button>
      {download.isError && <ErrorNotice message={describeFailure(download.error, { action: 'build the report' })} />}
    </div>
  )
}

function Environment({ title, environment }: { readonly title: string; readonly environment: FidelityEnvironment }) {
  return (
    <table>
      <caption>{title}</caption>
      <thead>
        <tr>
          <th scope="col">Service</th>
          <th scope="col">Source revision</th>
          <th scope="col">Image digest</th>
        </tr>
      </thead>
      <tbody>
        {Object.entries(environment.services).map(([name, service]) => (
          <tr key={name}>
            <th scope="row">{name}</th>
            <td>
              <code>{service.sourceRevision}</code>
            </td>
            <td>
              <code>{service.imageDigest}</code>
            </td>
          </tr>
        ))}
        <tr>
          <th scope="row">Content digest</th>
          <td colSpan={2}>
            <code>{environment.contentDigest}</code>
          </td>
        </tr>
      </tbody>
    </table>
  )
}

function detailOf(item: FidelityCase): string {
  if (item.firstDivergence) {
    return `Differs at step ${item.firstDivergence.step}: ${item.firstDivergence.kind}`
  }
  if (item.failure) {
    return `${item.failure.message} (${item.failure.code})`
  }
  return ''
}

function Cases({ check }: { readonly check: FidelityCheck }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const { variantLabel, result, offset = 0 } = useSearch({ from: '/fidelity/$checkId' })
  const query = useQuery(fidelityCasesQuery(client, check.fidelityCheckId, { variantLabel, result }, offset, isOpenCheck(check.state)))
  const go = (next: { variantLabel?: typeof variantLabel; result?: typeof result; offset?: number | undefined }) =>
    void navigate({
      to: '/fidelity/$checkId',
      params: { checkId: check.fidelityCheckId },
      search: { variantLabel, result, offset: undefined, ...next },
    })
  return (
    <>
      <h2>Cases</h2>
      <div className="filters">
        <label>
          Variant{' '}
          <select value={variantLabel ?? ''} onChange={(event) => go({ variantLabel: (event.target.value || undefined) as typeof variantLabel })}>
            <option value="">All</option>
            {FIDELITY_VARIANTS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label>
          Result{' '}
          <select value={result ?? ''} onChange={(event) => go({ result: (event.target.value || undefined) as typeof result })}>
            <option value="">All</option>
            {FIDELITY_RESULTS.map((value) => (
              <option key={value} value={value}>
                {FIDELITY_RESULT_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <QueryView query={query} failure={{ action: 'load the cases of this check' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>{check.counts.total === 0 ? 'The check has no cases yet.' : 'No case matches.'}</p>
          ) : (
            <>
              <table>
                <caption>Cases of this check</caption>
                <thead>
                  <tr>
                    <th scope="col">Variant</th>
                    <th scope="col">Players</th>
                    <th scope="col">Faction set</th>
                    <th scope="col">Rotation</th>
                    <th scope="col">Policy</th>
                    <th scope="col">Result</th>
                    <th scope="col">Detail</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((item) => (
                    <tr key={item.caseKey}>
                      <th scope="row">{item.variantLabel}</th>
                      <td>{item.playerCount}</td>
                      <td>{item.factionSet.join(' + ')}</td>
                      <td>{item.seatRotation}</td>
                      <td>{item.policyId}</td>
                      <td>{FIDELITY_RESULT_LABELS[item.result]}</td>
                      <td>{detailOf(item)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Case pages"
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
