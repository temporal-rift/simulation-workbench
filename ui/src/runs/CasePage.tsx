import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from '@tanstack/react-router'
import { caseQuery, useApiClient } from '../api/queries'
import type { Case } from '../api/generated'
import { ReplayInspector } from '../cases/ReplayInspector'
import { ReproductionPanel } from '../cases/ReproductionPanel'
import { QueryView } from '../components/QueryView'
import { CASE_STATE_LABELS } from './runView'

export function CasePage() {
  const { runId, caseId } = useParams({ from: '/runs/$runId/cases/$caseId' })
  const client = useApiClient()
  const query = useQuery(caseQuery(client, runId, caseId))
  return (
    <QueryView query={query} failure={{ action: 'load the case' }}>
      {(loaded) => <CaseDetail loaded={loaded} />}
    </QueryView>
  )
}

function CaseDetail({ loaded }: { readonly loaded: Case }) {
  return (
    <>
      <h1>
        Case {loaded.variantLabel}, seed {loaded.seed}
      </h1>
      <p>
        <Link to="/runs/$runId" params={{ runId: loaded.runId }}>
          Back to the run
        </Link>
        {' · '}
        <strong>State: {CASE_STATE_LABELS[loaded.state]}</strong>
      </p>
      <p>{loaded.playerCount} players</p>
      <table>
        <caption>Seat assignments</caption>
        <thead>
          <tr>
            <th scope="col">Seat</th>
            <th scope="col">Faction</th>
            <th scope="col">Policy</th>
          </tr>
        </thead>
        <tbody>
          {loaded.assignments.map((seat) => (
            <tr key={seat.seatIndex}>
              <th scope="row">{seat.seatIndex}</th>
              <td>{seat.faction}</td>
              <td>
                {seat.policyId}@{seat.policyVersion}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>Attempts</h2>
      {loaded.attempts.length === 0 ? (
        <p>No attempt has started.</p>
      ) : (
        <ol>
          {loaded.attempts.map((attempt) => (
            <li key={attempt.attemptId}>
              Attempt {attempt.ordinal}: {attempt.state.toLowerCase()}
              {attempt.failure && (
                <>
                  {' — '}
                  <code>{attempt.failure.code}</code>: {attempt.failure.message}
                </>
              )}
            </li>
          ))}
        </ol>
      )}
      <h2>Result</h2>
      {loaded.result ? (
        <dl>
          <dt>End reason</dt>
          <dd>{loaded.result.endReason}</dd>
          <dt>Winners</dt>
          <dd>{loaded.result.winners.map((winner) => `seat ${winner.seatIndex} (${winner.faction})`).join(', ') || 'None'}</dd>
          <dt>Final scores</dt>
          <dd>{loaded.result.finalScores.map((score) => `seat ${score.seatIndex} (${score.faction}): ${score.score}`).join('; ')}</dd>
          <dt>Eras, rounds, decisions</dt>
          <dd>
            {loaded.result.eras}, {loaded.result.rounds}, {loaded.result.decisions}
          </dd>
          <dt>Semantic digest</dt>
          <dd>
            <code>{loaded.result.semanticDigest}</code>
          </dd>
        </dl>
      ) : (
        <p>This case has no result: it is {CASE_STATE_LABELS[loaded.state].toLowerCase()}, and only a succeeded case is a result.</p>
      )}
      <ReplayInspector runId={loaded.runId} caseId={loaded.caseId} assignments={loaded.assignments} />
      <ReproductionPanel runId={loaded.runId} caseId={loaded.caseId} />
    </>
  )
}
