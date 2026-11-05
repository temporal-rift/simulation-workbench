import { useQuery } from '@tanstack/react-query'
import type { Campaign, ConfirmationRun } from '../api/generated'
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
      {(run) => (
        <p role="status">
          {run.state === 'QUEUED' ? 'Waiting for its turn' : `Playing: ${run.counts.succeeded + run.counts.failed} of ${run.counts.requested} cases done`}.
        </p>
      )}
    </QueryView>
  )
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
