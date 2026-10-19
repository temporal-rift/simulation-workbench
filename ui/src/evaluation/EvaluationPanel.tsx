import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { EvaluationMode, Run, RunEvaluation } from '../api/generated'
import { balanceCriteriaQuery, evaluationQuery, experimentQuery, useApiClient } from '../api/queries'
import { describeFailure } from '../api/problems'
import { ErrorNotice } from '../components/ErrorNotice'
import { QueryView } from '../components/QueryView'
import { formatInterval, formatValue } from '../reports/metricView'
import {
  BOT_RESULTS_STATEMENT,
  LINE_FILTER_LABELS,
  LINE_VERDICT_LABELS,
  VALIDITY_LABELS,
  VERDICT_LABELS,
  describeBand,
  describeCohort,
  filterLines,
  type LineFilter,
} from './evaluationView'

const MODES: readonly EvaluationMode[] = ['SCREENING', 'CONFIRMATION']
const FILTERS = Object.keys(LINE_FILTER_LABELS) as LineFilter[]

/** Judges one variant of the run against a balance criterion version. The service derives it on every read. */
export function EvaluationPanel({ run }: { readonly run: Run }) {
  const client = useApiClient()
  const experiment = useQuery(experimentQuery(client, run.experimentId))
  const criteria = useQuery(balanceCriteriaQuery(client))
  const [variant, setVariant] = useState<string | undefined>(undefined)
  const [version, setVersion] = useState<number | undefined>(undefined)
  const [mode, setMode] = useState<EvaluationMode>('SCREENING')
  const [filter, setFilter] = useState<LineFilter>('ALL')

  const variants = experiment.data?.manifest.variants.map((candidate) => candidate.label) ?? []
  const versions = criteria.data?.criteria.map((criterion) => criterion.version) ?? []
  const chosenVariant = variant ?? variants[0]
  const chosenVersion = version ?? versions[versions.length - 1]
  const choicesFailed = experiment.isError ? experiment : criteria.isError ? criteria : undefined
  const ready = chosenVariant !== undefined && chosenVersion !== undefined
  const evaluation = useQuery({
    ...evaluationQuery(client, run.runId, chosenVariant ?? '', chosenVersion ?? 0, mode),
    // The evaluation is derived from the cases of the run, so it is read again when they change.
    queryKey: ['evaluation', run.runId, chosenVariant ?? '', chosenVersion ?? 0, mode, run.state, ...Object.values(run.counts)],
    enabled: ready,
  })

  return (
    <section aria-labelledby="evaluation-heading">
      <h2 id="evaluation-heading">Evaluation</h2>
      <p className="notice notice-warning">{BOT_RESULTS_STATEMENT}</p>
      <div className="filters">
        <label>
          Variant to evaluate{' '}
          <select value={chosenVariant ?? ''} onChange={(event) => setVariant(event.target.value)}>
            {variants.map((label) => (
              <option key={label} value={label}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Criterion version{' '}
          <select value={chosenVersion ?? ''} onChange={(event) => setVersion(Number(event.target.value))}>
            {versions.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        </label>
        <label>
          Mode{' '}
          <select value={mode} onChange={(event) => setMode(event.target.value as EvaluationMode)}>
            {MODES.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate === 'SCREENING' ? 'Screening' : 'Confirmation'}
              </option>
            ))}
          </select>
        </label>
      </div>
      {choicesFailed ? (
        <ErrorNotice
          message={describeFailure(choicesFailed.error, { action: 'load the evaluation choices' })}
          onRetry={() => void choicesFailed.refetch()}
        />
      ) : !ready ? (
        experiment.data !== undefined && criteria.data !== undefined ? (
          <p>This run has no variant or criterion version to evaluate against.</p>
        ) : (
          <p role="status" aria-busy="true">
            Loading…
          </p>
        )
      ) : (
        <QueryView query={evaluation} failure={{ action: 'load the evaluation' }}>
          {(result, { stale }) => <EvaluationView result={result} stale={stale} filter={filter} onFilter={setFilter} />}
        </QueryView>
      )}
    </section>
  )
}

interface ViewProps {
  readonly result: RunEvaluation
  readonly stale: boolean
  readonly filter: LineFilter
  readonly onFilter: (filter: LineFilter) => void
}

function EvaluationView({ result, stale, filter, onFilter }: ViewProps) {
  const lines = filterLines(result.lines, filter)
  const sample = result.confirmationSample
  return (
    <>
      {stale && <p className="stale">Stale: the service could not be reached.</p>}
      <p role="status">
        <strong>Verdict: {VERDICT_LABELS[result.verdict]}</strong>
      </p>
      <dl>
        <dt>Score</dt>
        <dd>{result.score === null ? 'No score: an invalid evaluation is not scored.' : formatValue(result.score)}</dd>
        <dt>Criterion</dt>
        <dd>
          version {result.criterionVersion}, analysis version {result.analysisVersion}, seed {result.analysisSeed}
        </dd>
        {sample && (
          <>
            <dt>Confirmation sample</dt>
            <dd>
              {sample.seeds} of {sample.requiredSeeds} seeds, {sample.fullMatrix ? 'full case matrix' : 'case matrix incomplete'}:{' '}
              {sample.satisfied ? 'satisfied' : 'not satisfied'}
            </dd>
          </>
        )}
      </dl>
      {result.validity.reasons.length > 0 && (
        <div className="notice notice-error">
          <h3>Why this run is not valid evidence</h3>
          <ul>
            {result.validity.reasons.map((reason, index) => (
              <li key={index}>
                {VALIDITY_LABELS[reason.code]}: {reason.detail}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="filters">
        <label>
          Show{' '}
          <select value={filter} onChange={(event) => onFilter(event.target.value as LineFilter)}>
            {FILTERS.map((candidate) => (
              <option key={candidate} value={candidate}>
                {LINE_FILTER_LABELS[candidate]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <table>
        <caption>Evaluated lines</caption>
        <thead>
          <tr>
            <th scope="col">Line</th>
            <th scope="col">Role</th>
            <th scope="col">Cohort</th>
            <th scope="col">Value</th>
            <th scope="col">Interval</th>
            <th scope="col">Band</th>
            <th scope="col">Deviation</th>
            <th scope="col">Verdict</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line, index) => (
            <tr key={index}>
              <th scope="row">{line.lineId}</th>
              <td>{line.role === 'GATE' ? 'Gate' : 'Scored'}</td>
              <td>{describeCohort(line)}</td>
              <td>{formatValue(line.value)}</td>
              <td>{formatInterval(line.interval)}</td>
              <td>{describeBand(line.band)}</td>
              <td>{formatValue(line.deviation)}</td>
              <td>{LINE_VERDICT_LABELS[line.verdict]}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {lines.length === 0 && <p>No line matches this filter.</p>}
    </>
  )
}
