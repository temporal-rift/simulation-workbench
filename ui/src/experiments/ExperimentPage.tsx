import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from '@tanstack/react-router'
import { useRef, useState } from 'react'
import type { Experiment } from '../api/generated'
import { describeFailure } from '../api/problems'
import { experimentQuery, launchRun, runsQuery, useApiClient } from '../api/queries'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant } from '../components/format'
import { QueryView } from '../components/QueryView'
import { ScopedButton } from '../components/ScopedButton'
import { RUN_STATE_LABELS } from '../runs/runView'
import { hasUnsentDraft, manifestToDraft, saveDraft } from './draft'

export function ExperimentPage() {
  const { experimentId } = useParams({ from: '/experiments/$experimentId' })
  const client = useApiClient()
  const query = useQuery(experimentQuery(client, experimentId))
  return (
    <QueryView query={query} failure={{ action: 'load the experiment' }}>
      {(experiment) => <FrozenExperiment experiment={experiment} />}
    </QueryView>
  )
}

function FrozenExperiment({ experiment }: { readonly experiment: Experiment }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  const { manifest } = experiment
  // One key per launch intent, kept until the launch succeeds so that retrying a lost response cannot start a second run.
  const launchKey = useRef<string | null>(null)
  const [replacing, setReplacing] = useState(false)
  const runs = useQuery(runsQuery(client, { experimentId: experiment.experimentId }, 0))

  const launch = useMutation({
    mutationFn: () => {
      launchKey.current ??= crypto.randomUUID()
      return launchRun(client, experiment.experimentId, launchKey.current)
    },
    onSuccess: async (run) => {
      launchKey.current = null
      queryClient.setQueryData(['run', run.runId], run)
      await queryClient.invalidateQueries({ queryKey: ['runs'] })
      announce('Run started.')
      await navigate({ to: '/runs/$runId', params: { runId: run.runId } })
    },
  })

  function startDraft() {
    saveDraft({ draft: manifestToDraft(manifest), basedOn: { experimentId: experiment.experimentId, name: manifest.name } })
    void navigate({ to: '/experiments/new' })
  }

  return (
    <>
      <h1>{manifest.name}</h1>
      <p className="notice">
        Frozen and read-only. To change the configuration, start a new draft from it; this experiment stays as it is.
      </p>
      <dl>
        <dt>Manifest digest</dt>
        <dd>
          <code>{experiment.manifestDigest}</code>
        </dd>
        <dt>Frozen</dt>
        <dd>{formatInstant(experiment.createdAt)}</dd>
        <dt>Player counts</dt>
        <dd>{manifest.playerCounts.join(', ')}</dd>
        <dt>Seeds</dt>
        <dd>{manifest.seeds.join(', ')}</dd>
        <dt>Faction sets</dt>
        <dd>{manifest.factionSets.map((set) => set.join(' + ')).join('; ')}</dd>
        <dt>Execution</dt>
        <dd>
          Concurrency {manifest.concurrency}, case timeout {manifest.caseWallTimeoutSeconds} s, at most {manifest.maxRejectedCandidatesPerWindow} rejected
          candidates per window; {manifest.seatRotationMode.toLowerCase()} seat rotation, {manifest.timingMode.toLowerCase()} timing
        </dd>
      </dl>
      <h2>Variants</h2>
      <table>
        <caption>Variants</caption>
        <thead>
          <tr>
            <th scope="col">Label</th>
            <th scope="col">Overrides</th>
            <th scope="col">Effective rules digest</th>
            <th scope="col">Effective content digest</th>
          </tr>
        </thead>
        <tbody>
          {manifest.variants.map((variant) => (
            <tr key={variant.label}>
              <th scope="row">{variant.label}</th>
              <td>
                {Object.keys(variant.overrides ?? {}).length === 0 ? (
                  'None'
                ) : (
                  <ul>
                    {Object.entries(variant.overrides ?? {}).map(([key, value]) => (
                      <li key={key}>
                        <code>{key}</code> = {value}
                      </li>
                    ))}
                  </ul>
                )}
              </td>
              <td>
                <code>{variant.effectiveRulesDigest}</code>
              </td>
              <td>
                <code>{variant.effectiveContentDigest}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>Bot policies</h2>
      <ul>
        {manifest.policies.map((policy) => (
          <li key={`${policy.id}@${policy.version}`}>
            {policy.id}@{policy.version} — seats {policy.seatAssignment.map((seat) => seat.seatIndex).join(', ')}
            {Object.keys(policy.parameters).length > 0 && <> — parameters <code>{JSON.stringify(policy.parameters)}</code></>}
          </li>
        ))}
      </ul>
      <h2>Service provenance and contracts</h2>
      <ul>
        {Object.entries(manifest.services).map(([name, service]) => (
          <li key={name}>
            {name}: <code>{service.imageDigest}</code>, revision <code>{service.sourceRevision}</code>
          </li>
        ))}
        {Object.entries(manifest.contracts).map(([module, version]) => (
          <li key={module}>
            {module} {version}
          </li>
        ))}
      </ul>

      <h2>Runs</h2>
      <div className="actions">
        <ScopedButton scope={WRITE_SCOPE} aria-busy={launch.isPending} disabled={launch.isPending} onAction={() => launch.mutate()}>
          Launch a run
        </ScopedButton>
        {!replacing ? (
          <button type="button" onClick={() => (hasUnsentDraft() ? setReplacing(true) : startDraft())}>
            Change: new draft from this experiment
          </button>
        ) : (
          <div role="group" aria-label="Replace the unsent draft" className="notice notice-warning">
            <p>You have an unsent draft. Starting a new one from this experiment replaces it.</p>
            <button type="button" onClick={startDraft}>
              Replace the draft
            </button>
            <button type="button" onClick={() => setReplacing(false)}>
              Keep my draft
            </button>
          </div>
        )}
      </div>
      {launch.isError && <ErrorNotice message={describeFailure(launch.error, { action: 'start the run', requiredScope: WRITE_SCOPE })} onRetry={() => launch.mutate()} />}
      <QueryView query={runs} failure={{ action: 'load the runs of this experiment' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>No run has been started from this experiment.</p>
          ) : (
            <>
              <ul>
                {page.items.map((run) => (
                  <li key={run.runId}>
                    <Link to="/runs/$runId" params={{ runId: run.runId }}>
                      Run started {formatInstant(run.createdAt)}
                    </Link>{' '}
                    — {RUN_STATE_LABELS[run.state]}, {run.counts.succeeded} of {run.counts.requested} cases succeeded
                  </li>
                ))}
              </ul>
              {page.total > page.items.length && (
                <p>
                  <Link to="/runs" search={{ experimentId: experiment.experimentId }}>
                    All {page.total} runs of this experiment
                  </Link>
                </p>
              )}
            </>
          )
        }
      </QueryView>
    </>
  )
}
