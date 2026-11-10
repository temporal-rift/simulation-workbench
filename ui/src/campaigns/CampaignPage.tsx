import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'
import { useState } from 'react'
import { ApiProblemError } from '../api/client'
import type { Campaign } from '../api/generated'
import { describeFailure } from '../api/problems'
import {
  CAMPAIGN_LOG_PAGE_SIZE,
  campaignLogQuery,
  campaignQuery,
  cancelCampaignCommand,
  pauseCampaignCommand,
  resumeCampaignCommand,
  setCampaignParallelismCommand,
  startConfirmationCommand,
  useApiClient,
} from '../api/queries'
import { useIntentKeys } from '../api/useIntentKeys'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { ScopedButton } from '../components/ScopedButton'
import { useAnnounceChange } from '../components/useAnnounceChange'
import {
  CAMPAIGN_STATE_LABELS,
  DECISION_LABELS,
  REASON_LABELS,
  formatSeconds,
  isCancellable,
  isConfirmable,
  isLive,
  isPausable,
  isResumable,
  isTuneable,
  isWholeNumber,
  evaluationText,
} from './campaignView'
import { ConfirmationReport } from './ConfirmationReport'

type Command = 'pause' | 'resume' | 'cancel' | 'confirm'

export function CampaignPage() {
  const { campaignId } = useParams({ from: '/campaigns/$campaignId' })
  const client = useApiClient()
  const query = useQuery(campaignQuery(client, campaignId))
  return (
    <QueryView query={query} failure={{ action: 'load the campaign' }}>
      {(campaign, { stale }) => (
        <>
          <CampaignDetail campaign={campaign} stale={stale} />
          <LogSection campaign={campaign} />
        </>
      )}
    </QueryView>
  )
}

function CampaignDetail({ campaign, stale }: { readonly campaign: Campaign; readonly stale: boolean }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  const keys = useIntentKeys()
  const [refused, setRefused] = useState(false)

  useAnnounceChange(campaign.state, (state) => `Campaign is now ${CAMPAIGN_STATE_LABELS[state].toLowerCase()}.`)

  const command = useMutation({
    mutationFn: (name: Command) => {
      const key = keys.keyFor(name)
      const id = campaign.campaignId
      switch (name) {
        case 'pause':
          return pauseCampaignCommand(client, id, key)
        case 'resume':
          return resumeCampaignCommand(client, id, key)
        case 'confirm':
          return startConfirmationCommand(client, id, key)
        default:
          return cancelCampaignCommand(client, id, key)
      }
    },
    onMutate: () => setRefused(false),
    onSuccess: (updated, name) => {
      keys.clear(name)
      queryClient.setQueryData(['campaign', campaign.campaignId], updated)
      void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
      announce(
        name === 'pause'
          ? 'Campaign paused.'
          : name === 'resume'
            ? 'Campaign resumed.'
            : name === 'confirm'
              ? 'Confirmation started.'
              : 'Campaign cancelled.',
      )
    },
    onError: async (error, name) => {
      if (error instanceof ApiProblemError && error.isCode('INVALID_CAMPAIGN_STATE')) {
        // The page was showing an outdated state: read the current one so the refusal can name it.
        keys.clear(name)
        await queryClient.refetchQueries({ queryKey: ['campaign', campaign.campaignId] })
        setRefused(true)
      }
    },
  })

  const failedCommand = command.isError && !refused ? command.variables : null

  return (
    <>
      <h1>
        Campaign <code>{campaign.name}</code>
      </h1>
      <p>
        <Link to="/campaigns">All campaigns</Link>
        {' · '}
        <strong>State: {CAMPAIGN_STATE_LABELS[campaign.state]}</strong>
        {stale && <span className="stale"> (stale: the service could not be reached)</span>}
        {isLive(campaign.state) && !stale && <span className="hint"> Refreshing while the campaign is open.</span>}
      </p>
      {campaign.state === 'FAILED' && campaign.failure && (
        <div className="notice notice-error">
          <h2>Campaign failure</h2>
          <p>
            <code>{campaign.failure.code}</code>: {campaign.failure.message}
          </p>
        </div>
      )}
      <dl>
        <dt>Time spent</dt>
        <dd>{formatSeconds(campaign.spentSeconds)}</dd>
        <dt>Round</dt>
        <dd>{campaign.round}</dd>
        <dt>Games at once</dt>
        <dd>{campaign.parallelism}</dd>
        <dt>Player counts</dt>
        <dd>{campaign.playerCounts.join(', ')}</dd>
        <dt>Short list</dt>
        <dd>{campaign.shortList.length === 0 ? 'None yet' : campaign.shortList.join(', ')}</dd>
        <dt>Candidates in round 0</dt>
        <dd>{campaign.algorithm.initialCandidates}</dd>
        <dt>Seeds in round 0</dt>
        <dd>{campaign.algorithm.screeningSeeds}</dd>
        <dt>Eta</dt>
        <dd>{campaign.algorithm.eta}</dd>
        <dt>Short list size</dt>
        <dd>{campaign.algorithm.shortListSize}</dd>
        <dt>Parameter space version</dt>
        <dd>{campaign.parameterSpaceVersion}</dd>
        <dt>Criterion version</dt>
        <dd>{campaign.criterionVersion}</dd>
        <dt>Search master seed</dt>
        <dd>{campaign.searchMasterSeed}</dd>
        <dt>Confirmation master seed</dt>
        <dd>{campaign.confirmationMasterSeed}</dd>
        <dt>Created</dt>
        <dd>{formatInstant(campaign.createdAt)}</dd>
        <dt>Started</dt>
        <dd>{formatInstant(campaign.startedAt)}</dd>
        <dt>Finished</dt>
        <dd>{formatInstant(campaign.finishedAt)}</dd>
      </dl>
      <div className="actions">
        {isPausable(campaign.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('pause')}>
            Pause campaign
          </ScopedButton>
        )}
        {isResumable(campaign.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('resume')}>
            Resume campaign
          </ScopedButton>
        )}
        {isConfirmable(campaign.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('confirm')}>
            Confirm short list
          </ScopedButton>
        )}
        {isCancellable(campaign.state) && (
          <ScopedButton scope={WRITE_SCOPE} aria-busy={command.isPending} disabled={command.isPending} onAction={() => command.mutate('cancel')}>
            Cancel campaign
          </ScopedButton>
        )}
      </div>
      {refused && (
        <p role="alert" className="notice notice-warning">
          The campaign is now {CAMPAIGN_STATE_LABELS[campaign.state].toLowerCase()}, which does not allow that.
        </p>
      )}
      {failedCommand && (
        <ErrorNotice
          message={describeFailure(command.error, { action: `${failedCommand} the campaign`, requiredScope: WRITE_SCOPE })}
          onRetry={() => command.mutate(failedCommand)}
        />
      )}
      {isTuneable(campaign.state) && <ParallelismControl campaign={campaign} />}
      <ConfirmationReport campaign={campaign} />
    </>
  )
}

function ParallelismControl({ campaign }: { readonly campaign: Campaign }) {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  const keys = useIntentKeys()
  const [text, setText] = useState(String(campaign.parallelism))
  const valid = isWholeNumber(text, 1)

  const change = useMutation({
    mutationFn: () => setCampaignParallelismCommand(client, campaign.campaignId, Number(text), keys.keyFor(`parallelism:${text}`)),
    onSuccess: (updated) => {
      keys.clear(`parallelism:${text}`)
      queryClient.setQueryData(['campaign', campaign.campaignId], updated)
      announce(`Games at once set to ${updated.parallelism}.`)
    },
  })

  return (
    <section aria-labelledby="campaign-parallelism">
      <h2 id="campaign-parallelism">Games at once</h2>
      <p>The next game claimed obeys the new number; games already playing finish.</p>
      <label>
        Games at once{' '}
        <input value={text} inputMode="numeric" aria-invalid={!valid} onChange={(event) => setText(event.target.value.trim())} />
      </label>
      <div className="actions">
        <ScopedButton scope={WRITE_SCOPE} aria-busy={change.isPending} disabled={change.isPending || !valid} onAction={() => change.mutate()}>
          Set games at once
        </ScopedButton>
      </div>
      {change.isError && <ErrorNotice message={describeFailure(change.error, { action: 'change the games at once', requiredScope: WRITE_SCOPE })} onRetry={() => change.mutate()} />}
    </section>
  )
}

function LogSection({ campaign }: { readonly campaign: Campaign }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const { offset = 0 } = useSearch({ from: '/campaigns/$campaignId' })
  const query = useQuery(campaignLogQuery(client, campaign.campaignId, offset, isLive(campaign.state)))
  return (
    <section aria-labelledby="campaign-log">
      <h2 id="campaign-log">Decision log</h2>
      <QueryView query={query} failure={{ action: 'load the decision log' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>The campaign has made no decision yet.</p>
          ) : (
            <>
              <table>
                <caption>Decisions, in the order they were taken</caption>
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Round</th>
                    <th scope="col">Candidate</th>
                    <th scope="col">Decision</th>
                    <th scope="col">Why</th>
                    <th scope="col">Evaluation</th>
                    <th scope="col">Games</th>
                    <th scope="col">Overrides</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((entry) => (
                    <tr key={entry.sequence}>
                      <th scope="row">{entry.sequence}</th>
                      <td>{entry.round}</td>
                      <td>
                        <code>{entry.candidateId}</code>
                      </td>
                      <td>{DECISION_LABELS[entry.decision]}</td>
                      <td>
                        {entry.reason ? REASON_LABELS[entry.reason] : '—'}
                        {entry.evidence?.lineIds && entry.evidence.lineIds.length > 0 && <> ({entry.evidence.lineIds.join(', ')})</>}
                        {entry.evidence?.dominatedBy && (
                          <>
                            {' '}
                            by <code>{entry.evidence.dominatedBy}</code>
                          </>
                        )}
                      </td>
                      <td>{entry.evaluation ? evaluationText(entry.evaluation) : '—'}</td>
                      <td>{entry.gamesSpent}</td>
                      <td>
                        {Object.keys(entry.overrides).length === 0
                          ? 'baseline'
                          : Object.entries(entry.overrides)
                              .map(([key, value]) => `${key.replace('game.rules.', '')} = ${value}`)
                              .join('; ')}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Decision log pages"
                offset={offset}
                count={page.items.length}
                total={page.total}
                pageSize={CAMPAIGN_LOG_PAGE_SIZE}
                onOffset={(next) => void navigate({ to: '/campaigns/$campaignId', params: { campaignId: campaign.campaignId }, search: { offset: next || undefined } })}
              />
            </>
          )
        }
      </QueryView>
    </section>
  )
}
