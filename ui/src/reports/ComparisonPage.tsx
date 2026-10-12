import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from '@tanstack/react-router'
import type { Comparison, ComparisonCohort, ExcludedPair } from '../api/generated'
import { comparisonQuery, useApiClient } from '../api/queries'
import { ExportButtons } from '../components/ExportButtons'
import { shortDigest } from '../components/format'
import { QueryView } from '../components/QueryView'
import { CASE_STATE_LABELS } from '../runs/runView'
import { STATUS_LABELS, describeDimensions, describeFactionSet, describeSeat, formatInterval, formatValue } from './metricView'

export function ComparisonPage() {
  const { comparisonId } = useParams({ from: '/comparisons/$comparisonId' })
  const client = useApiClient()
  const query = useQuery(comparisonQuery(client, comparisonId))
  return (
    <QueryView query={query} failure={{ action: 'load the comparison' }}>
      {(comparison, { stale }) => <ComparisonView comparison={comparison} stale={stale} />}
    </QueryView>
  )
}

const REASONS: Readonly<Record<ExcludedPair['reason'], string>> = {
  FAILED_COUNTERPART: 'A side failed or was cancelled',
  UNFINISHED: 'A side is still pending or running',
}

function ComparisonView({ comparison, stale }: { readonly comparison: Comparison; readonly stale: boolean }) {
  return (
    <>
      <h1>
        Comparison of {comparison.baseline.variantLabel} and {comparison.candidate.variantLabel}
      </h1>
      <p>
        Baseline:{' '}
        <Link to="/runs/$runId" params={{ runId: comparison.baseline.runId }}>
          {comparison.baseline.variantLabel}
        </Link>
        {' · '}Candidate:{' '}
        <Link to="/runs/$runId" params={{ runId: comparison.candidate.runId }}>
          {comparison.candidate.variantLabel}
        </Link>
        {stale && <span className="stale"> (stale: the service could not be reached)</span>}
      </p>
      {!comparison.complete && (
        <p role="status" className="notice notice-warning">
          Partial comparison: {comparison.unmatchedCases} pairs wait on an unfinished case and {comparison.failedCounterparts} have a failed or
          cancelled side. Only fully succeeded pairs are in the differences below.
        </p>
      )}
      <dl>
        <dt>Analysis</dt>
        <dd>
          version {comparison.analysisVersion}, seed {comparison.analysisSeed}
        </dd>
        <dt>Matched blocks</dt>
        <dd>{comparison.matchedBlocks}</dd>
        <dt>Unmatched cases</dt>
        <dd>{comparison.unmatchedCases}</dd>
        <dt>Failed counterparts</dt>
        <dd>{comparison.failedCounterparts}</dd>
      </dl>
      <ExportButtons path={`/api/v1/comparisons/${comparison.comparisonId}`} filename={`comparison-${comparison.comparisonId}`} />
      <section aria-labelledby="declared-heading">
        <h2 id="declared-heading">Declared differences</h2>
        {comparison.declaredDifferences.length === 0 ? (
          <p>The two sides declare no difference in rules or content.</p>
        ) : (
          <table>
            <caption>Definitions that differ between the sides</caption>
            <thead>
              <tr>
                <th scope="col">Definition</th>
                <th scope="col">Baseline digest</th>
                <th scope="col">Candidate digest</th>
              </tr>
            </thead>
            <tbody>
              {comparison.declaredDifferences.map((difference) => (
                <tr key={difference.field}>
                  <th scope="row">{difference.field}</th>
                  <td>
                    <code title={difference.baselineDigest}>{shortDigest(difference.baselineDigest)}</code>
                  </td>
                  <td>
                    <code title={difference.candidateDigest}>{shortDigest(difference.candidateDigest)}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {comparison.cohorts.map((cohort, index) => (
        <CohortSection key={index} cohort={cohort} index={index} />
      ))}
      <ExcludedSection pairs={comparison.excludedPairs} />
    </>
  )
}

function cohortTitle(cohort: ComparisonCohort): string {
  return `${cohort.policyId}@${cohort.policyVersion} · ${cohort.playerCount} players · ${describeFactionSet(cohort.factionSet)} · ${describeSeat(cohort.seatIndex)}`
}

function CohortSection({ cohort, index }: { readonly cohort: ComparisonCohort; readonly index: number }) {
  const headingId = `comparison-cohort-${index}`
  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId}>{cohortTitle(cohort)}</h2>
      <p>
        {cohort.matchedBlocks} matched blocks, {cohort.excludedBlocks} excluded.
      </p>
      <table>
        <caption>Paired differences of {cohortTitle(cohort)}</caption>
        <thead>
          <tr>
            <th scope="col">Metric</th>
            <th scope="col">Split by</th>
            <th scope="col">Baseline</th>
            <th scope="col">Candidate</th>
            <th scope="col">Difference</th>
            <th scope="col">Interval</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {cohort.metricDifferences.map((difference, row) => (
            <tr key={row}>
              <th scope="row">{difference.name}</th>
              <td>{describeDimensions(difference.dimensions)}</td>
              <td>{formatValue(difference.baselineValue)}</td>
              <td>{formatValue(difference.candidateValue)}</td>
              <td>
                {formatValue(difference.difference)} {difference.unit}
              </td>
              <td>{formatInterval(difference.interval)}</td>
              <td>{STATUS_LABELS[difference.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function ExcludedSection({ pairs }: { readonly pairs: readonly ExcludedPair[] }) {
  return (
    <section aria-labelledby="excluded-heading">
      <h2 id="excluded-heading">Excluded pairs</h2>
      {pairs.length === 0 ? (
        <p>No pair was excluded.</p>
      ) : (
        <table>
          <caption>Pairs left out of the differences</caption>
          <thead>
            <tr>
              <th scope="col">Seed</th>
              <th scope="col">Players</th>
              <th scope="col">Policy</th>
              <th scope="col">Baseline</th>
              <th scope="col">Candidate</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            {pairs.map((pair, index) => (
              <tr key={index}>
                <th scope="row">{pair.seed}</th>
                <td>{pair.playerCount}</td>
                <td>
                  {pair.policyId}@{pair.policyVersion}
                </td>
                <td>{CASE_STATE_LABELS[pair.baselineState]}</td>
                <td>{CASE_STATE_LABELS[pair.candidateState]}</td>
                <td>{REASONS[pair.reason]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
