import { useQuery } from '@tanstack/react-query'
import type { Campaign, ConfirmationRun, Run } from '../api/generated'
import { evaluationQuery, runQuery, useApiClient } from '../api/queries'
import { QueryView } from '../components/QueryView'
import { EvaluationDetails } from '../evaluation/EvaluationPanel'
import { BOT_RESULTS_STATEMENT, VERDICT_LABELS } from '../evaluation/evaluationView'
import { describeSubject } from './campaignView'

/**
 * The confirmation of a campaign's short list: for the baseline and for each short-listed candidate, the verdict
 * of the criterion on fresh seeds played on the real services, every line's verdict and the confirmation sample.
 */
export function ConfirmationReport({ campaign }: { readonly campaign: Campaign }) {
  const confirmation = campaign.confirmation
  if (confirmation === null) {
    return null
  }
  return (
    <section aria-labelledby="campaign-confirmation">
      <h2 id="campaign-confirmation">Confirmation</h2>
      <p>
        Each subject plays {confirmation.seedCount} fresh seeds on the real services, one case at a time. The seeds derive from the confirmation master seed
        and are checked against every seed the search played.
      </p>
      <p className="notice notice-warning">{BOT_RESULTS_STATEMENT}</p>
      {confirmation.runs.map((run) => (
        <SubjectReport key={run.runId} campaign={campaign} run={run} />
      ))}
    </section>
  )
}

function SubjectReport({ campaign, run }: { readonly campaign: Campaign; readonly run: ConfirmationRun }) {
  const heading = describeSubject(run.candidateId)
  return (
    <section aria-label={heading}>
      <h3>{heading}</h3>
      {run.evaluation === null ? <Progress runId={run.runId} /> : <Evaluated campaign={campaign} run={run} />}
    </section>
  )
}

function Progress({ runId }: { readonly runId: string }) {
  const client = useApiClient()
  const query = useQuery(runQuery(client, runId))
  return (
    <QueryView query={query} failure={{ action: 'load the confirmation run' }}>
      {(run) => <p role="status">{describeRun(run)}</p>}
    </QueryView>
  )
}

/** What the run is doing now, from its own state: a run that failed or was cancelled never says it is playing. */
function describeRun(run: Run): string {
  const { counts } = run
  const done = counts.succeeded + counts.failed + counts.cancelled
  const progress = `${done} of ${counts.requested} cases done`
  switch (run.state) {
    case 'QUEUED':
      return 'Waiting for its turn.'
    case 'RUNNING':
      return `Playing: ${progress}.`
    case 'INTERRUPTED':
      return `Interrupted, resuming: ${progress}.`
    case 'CANCELLING':
    case 'CANCELLED':
      return `Cancelled: ${progress}.`
    case 'FAILED':
      return `Failed: ${progress}${run.failure ? `. ${run.failure.message}` : '.'}`
    case 'COMPLETED':
      return `Played all ${counts.requested} cases; waiting for the other runs before it is evaluated.`
  }
}

function Evaluated({ campaign, run }: { readonly campaign: Campaign; readonly run: ConfirmationRun }) {
  const client = useApiClient()
  const evaluation = useQuery(evaluationQuery(client, run.runId, run.candidateId, campaign.criterionVersion, 'CONFIRMATION'))
  return (
    <>
      <p>
        <strong>Recorded verdict: {run.evaluation ? VERDICT_LABELS[run.evaluation.verdict] : ''}</strong>
      </p>
      <QueryView query={evaluation} failure={{ action: 'load the confirmation evaluation' }}>
        {(result, { stale }) => <EvaluationDetails result={result} stale={stale} />}
      </QueryView>
    </>
  )
}
