import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from '@tanstack/react-router'
import type { Cohort, Report } from '../api/generated'
import { reportQuery, runQuery, useApiClient } from '../api/queries'
import { ExportButtons } from '../components/ExportButtons'
import { shortDigest } from '../components/format'
import { QueryView } from '../components/QueryView'
import { STATUS_LABELS, describeDimensions, describeFactionSet, describeSeat, formatInterval, formatValue } from './metricView'

export function ReportPage() {
  const { runId } = useParams({ from: '/runs/$runId/report' })
  const client = useApiClient()
  const run = useQuery(runQuery(client, runId))
  const query = useQuery(reportQuery(client, runId, run.data?.counts))
  return (
    <>
      <h1>
        Report for run <code>{runId}</code>
      </h1>
      <p>
        <Link to="/runs/$runId" params={{ runId }}>
          Back to the run
        </Link>
      </p>
      <QueryView query={query} failure={{ action: 'load the report' }}>
        {(report, { stale }) => <ReportView report={report} stale={stale} />}
      </QueryView>
    </>
  )
}

function ReportView({ report, stale }: { readonly report: Report; readonly stale: boolean }) {
  const { caseCounts } = report
  return (
    <>
      {stale && <p className="stale">Stale: the service could not be reached.</p>}
      {!report.complete && (
        <p role="status" className="notice notice-warning">
          Partial report: {caseCounts.succeeded} of {caseCounts.requested} requested cases have succeeded ({caseCounts.pending} pending,{' '}
          {caseCounts.running} running, {caseCounts.failed} failed, {caseCounts.cancelled} cancelled). Only succeeded cases are in the metrics below.
        </p>
      )}
      <dl>
        <dt>Manifest digest</dt>
        <dd>
          <code title={report.manifestDigest}>{shortDigest(report.manifestDigest)}</code>
        </dd>
        <dt>Analysis</dt>
        <dd>
          version {report.analysisVersion}, seed {report.analysisSeed}
        </dd>
      </dl>
      <ExportButtons path={`/api/v1/runs/${report.runId}/report`} filename={`report-${report.runId}`} />
      {report.cohorts.length === 0 && <p>This report has no cohort yet: no case has succeeded.</p>}
      {report.cohorts.map((cohort, index) => (
        <CohortSection key={index} cohort={cohort} index={index} />
      ))}
    </>
  )
}

function cohortTitle(cohort: Cohort): string {
  return `${cohort.variantLabel} · ${cohort.policyId}@${cohort.policyVersion} · ${cohort.playerCount} players · ${describeFactionSet(cohort.factionSet)} · ${describeSeat(cohort.seatIndex)}`
}

function CohortSection({ cohort, index }: { readonly cohort: Cohort; readonly index: number }) {
  const headingId = `cohort-${index}`
  const { caseCounts } = cohort
  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId}>{cohortTitle(cohort)}</h2>
      <p>
        {cohort.eligibleGames} eligible games in {cohort.independentBlocks} independent blocks ({caseCounts.requested} requested, {caseCounts.failed}{' '}
        failed, {caseCounts.pending + caseCounts.running} unfinished, {caseCounts.cancelled} cancelled).
      </p>
      <table>
        <caption>Metrics of {cohortTitle(cohort)}</caption>
        <thead>
          <tr>
            <th scope="col">Metric</th>
            <th scope="col">Split by</th>
            <th scope="col">Value</th>
            <th scope="col">Of</th>
            <th scope="col">Interval</th>
            <th scope="col">Status</th>
            <th scope="col">Unknown</th>
          </tr>
        </thead>
        <tbody>
          {cohort.metrics.map((metric, row) => (
            <tr key={row}>
              <th scope="row">{metric.name}</th>
              <td>{describeDimensions(metric.dimensions)}</td>
              <td>
                {formatValue(metric.value)} {metric.unit}
              </td>
              <td>
                {metric.numerator === null ? '' : `${metric.numerator} / `}
                {metric.denominator}
              </td>
              <td>{formatInterval(metric.interval)}</td>
              <td>{STATUS_LABELS[metric.status]}</td>
              <td>{metric.unknownCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
