import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useState } from 'react'
import { ApiProblemError } from '../api/client'
import type { CampaignDefinition, LaneBundle, PolicyBundle } from '../api/generated'
import { describeFailure } from '../api/problems'
import { PAGE_SIZE, campaignsQuery, createCampaignCommand, laneBundlesQuery, policiesQuery, useApiClient } from '../api/queries'
import { useIntentKeys } from '../api/useIntentKeys'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { Field, TextField } from '../components/Field'
import { formatInstant, shortDigest, shortId } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { ScopedButton } from '../components/ScopedButton'
import { CAMPAIGN_STATE_LABELS, formatSeconds, isMasterSeed, isWholeNumber } from './campaignView'

const SEATS = 5
const PLAYER_COUNTS = [3, 4, 5] as const

export function CampaignsPage() {
  return (
    <>
      <h1>Campaigns</h1>
      <p>
        A campaign searches the rules of a baseline for configurations that meet the balance criterion. It plays candidate configurations on the same seeds,
        discards those that fail, are dominated or fall outside the top fraction, and gives more seeds to the rest, until its budget is spent or its short
        list stops changing.
      </p>
      <StartSection />
      <CampaignsSection />
    </>
  )
}

interface Draft {
  name: string
  bundle: number
  policy: number
  playerCounts: number[]
  parameterSpaceVersion: string
  criterionVersion: string
  budgetSeconds: string
  parallelism: string
  searchMasterSeed: string
  confirmationMasterSeed: string
  initialCandidates: string
  screeningSeeds: string
  eta: string
  shortListSize: string
}

const INITIAL: Draft = {
  name: '',
  bundle: 0,
  policy: 0,
  playerCounts: [3],
  parameterSpaceVersion: '1',
  criterionVersion: '1',
  budgetSeconds: '14400',
  parallelism: '4',
  searchMasterSeed: '',
  confirmationMasterSeed: '',
  initialCandidates: '20',
  screeningSeeds: '4',
  eta: '2',
  shortListSize: '3',
}

/** What is wrong with the draft that the designer can fix before sending it; the service stays the authority. */
function problems(draft: Draft): Partial<Record<keyof Draft, string>> {
  const found: Partial<Record<keyof Draft, string>> = {}
  if (draft.name.trim() === '' || draft.name.length > 120) {
    found.name = 'Give the campaign a name of 1 to 120 characters.'
  }
  if (draft.playerCounts.length === 0) {
    found.playerCounts = 'Choose at least one player count.'
  }
  const positive: [keyof Draft, number][] = [
    ['parameterSpaceVersion', 1],
    ['criterionVersion', 1],
    ['budgetSeconds', 60],
    ['parallelism', 1],
    ['initialCandidates', 2],
    ['screeningSeeds', 2],
    ['eta', 2],
    ['shortListSize', 1],
  ]
  for (const [key, minimum] of positive) {
    if (!isWholeNumber(String(draft[key]), minimum)) {
      found[key] = `Enter a whole number of at least ${minimum}.`
    }
  }
  for (const key of ['searchMasterSeed', 'confirmationMasterSeed'] as const) {
    if (!isMasterSeed(draft[key])) {
      found[key] = 'Enter a whole number from 0 to 18446744073709551615.'
    }
  }
  if (!found.searchMasterSeed && !found.confirmationMasterSeed && draft.searchMasterSeed === draft.confirmationMasterSeed) {
    found.confirmationMasterSeed = 'The confirmation master seed must differ from the search master seed.'
  }
  return found
}

function definitionOf(draft: Draft, bundle: LaneBundle, policy: PolicyBundle): CampaignDefinition {
  return {
    name: draft.name.trim(),
    baseline: { rulesArtifact: bundle.rulesArtifact, contentArtifact: bundle.contentArtifact },
    parameterSpaceVersion: Number(draft.parameterSpaceVersion),
    criterionVersion: Number(draft.criterionVersion),
    policies: [
      {
        id: policy.id,
        version: policy.version,
        artifactDigest: policy.artifactDigest,
        parameters: {},
        seatAssignment: Array.from({ length: SEATS }, (_, seatIndex) => ({ seatIndex, policyId: policy.id, policyVersion: policy.version })),
      },
    ],
    playerCounts: [...draft.playerCounts].sort() as CampaignDefinition['playerCounts'],
    budgetSeconds: Number(draft.budgetSeconds),
    parallelism: Number(draft.parallelism),
    searchMasterSeed: draft.searchMasterSeed,
    confirmationMasterSeed: draft.confirmationMasterSeed,
    algorithm: {
      version: 'SUCCESSIVE_HALVING_V1',
      initialCandidates: Number(draft.initialCandidates),
      screeningSeeds: Number(draft.screeningSeeds),
      eta: Number(draft.eta),
      shortListSize: Number(draft.shortListSize),
    },
  }
}

function StartSection() {
  const client = useApiClient()
  const bundles = useQuery(laneBundlesQuery(client))
  const policies = useQuery(policiesQuery(client))
  return (
    <section aria-labelledby="campaign-start">
      <h2 id="campaign-start">Start a campaign</h2>
      <QueryView query={bundles} failure={{ action: 'load the rules bundles' }}>
        {(bundleList) => (
          <QueryView query={policies} failure={{ action: 'load the policies' }}>
            {(catalog) =>
              bundleList.items.length === 0 || catalog.items.length === 0 ? (
                <p>A campaign needs a rules bundle some lane serves and a policy; none is available.</p>
              ) : (
                <StartForm bundles={bundleList.items} policies={catalog.items} />
              )
            }
          </QueryView>
        )}
      </QueryView>
    </section>
  )
}

function StartForm({ bundles, policies }: { readonly bundles: readonly LaneBundle[]; readonly policies: readonly PolicyBundle[] }) {
  const client = useApiClient()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  // One key per start intent, kept until it succeeds, so retrying a lost response cannot start a second campaign.
  const keys = useIntentKeys()
  const [draft, setDraft] = useState<Draft>(INITIAL)
  const [attempted, setAttempted] = useState(false)
  const found = problems(draft)
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }))

  const start = useMutation({
    mutationFn: () => {
      const definition = definitionOf(draft, bundles[draft.bundle]!, policies[draft.policy]!)
      return createCampaignCommand(client, definition, keys.keyFor(JSON.stringify(definition)))
    },
    onSuccess: async (campaign) => {
      keys.clear(JSON.stringify(definitionOf(draft, bundles[draft.bundle]!, policies[draft.policy]!)))
      queryClient.setQueryData(['campaign', campaign.campaignId], campaign)
      await queryClient.invalidateQueries({ queryKey: ['campaigns'] })
      announce('Campaign started.')
      await navigate({ to: '/campaigns/$campaignId', params: { campaignId: campaign.campaignId } })
    },
  })

  const shown = (key: keyof Draft) => {
    if (attempted && found[key]) {
      return found[key]
    }
    return start.error instanceof ApiProblemError && start.error.field === key ? start.error.message : undefined
  }

  const submit = () => {
    setAttempted(true)
    if (Object.keys(found).length === 0) {
      start.mutate()
    }
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        submit()
      }}
      noValidate
    >
      <TextField label="Name" value={draft.name} onChange={(value) => set('name', value)} error={shown('name')} />
      <Field label="Baseline rules and content">
        {(props) => (
          <select {...props} value={draft.bundle} onChange={(event) => set('bundle', Number(event.target.value))}>
            {bundles.map((bundle, index) => (
              <option key={`${bundle.rulesArtifact.digest}-${bundle.contentArtifact.digest}`} value={index}>
                {bundle.rulesArtifact.name ?? shortDigest(bundle.rulesArtifact.digest)} with {bundle.contentArtifact.name ?? shortDigest(bundle.contentArtifact.digest)}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Policy on every seat">
        {(props) => (
          <select {...props} value={draft.policy} onChange={(event) => set('policy', Number(event.target.value))}>
            {policies.map((policy, index) => (
              <option key={`${policy.id}@${policy.version}`} value={index}>
                {policy.id} {policy.version}
              </option>
            ))}
          </select>
        )}
      </Field>
      <fieldset>
        <legend>Player counts</legend>
        {PLAYER_COUNTS.map((count) => (
          <label key={count}>
            <input
              type="checkbox"
              checked={draft.playerCounts.includes(count)}
              onChange={(event) =>
                set('playerCounts', event.target.checked ? [...draft.playerCounts, count] : draft.playerCounts.filter((existing) => existing !== count))
              }
            />{' '}
            {count} players
          </label>
        ))}
        {shown('playerCounts') && <p className="field-error">{shown('playerCounts')}</p>}
      </fieldset>
      <TextField label="Parameter space version" value={draft.parameterSpaceVersion} onChange={(value) => set('parameterSpaceVersion', value)} inputMode="numeric" error={shown('parameterSpaceVersion')} />
      <TextField label="Criterion version" value={draft.criterionVersion} onChange={(value) => set('criterionVersion', value)} inputMode="numeric" error={shown('criterionVersion')} />
      <TextField
        label="Budget (seconds of active time)"
        value={draft.budgetSeconds}
        onChange={(value) => set('budgetSeconds', value)}
        inputMode="numeric"
        error={shown('budgetSeconds')}
        hint="At least 60. Time spent paused or queued is not counted."
      />
      <TextField label="Games at once" value={draft.parallelism} onChange={(value) => set('parallelism', value)} inputMode="numeric" error={shown('parallelism')} />
      <TextField label="Search master seed" value={draft.searchMasterSeed} onChange={(value) => set('searchMasterSeed', value)} inputMode="numeric" error={shown('searchMasterSeed')} />
      <TextField
        label="Confirmation master seed"
        value={draft.confirmationMasterSeed}
        onChange={(value) => set('confirmationMasterSeed', value)}
        inputMode="numeric"
        error={shown('confirmationMasterSeed')}
      />
      <TextField label="Candidates in round 0" value={draft.initialCandidates} onChange={(value) => set('initialCandidates', value)} inputMode="numeric" error={shown('initialCandidates')} />
      <TextField label="Seeds in round 0" value={draft.screeningSeeds} onChange={(value) => set('screeningSeeds', value)} inputMode="numeric" error={shown('screeningSeeds')} />
      <TextField
        label="Eta"
        value={draft.eta}
        onChange={(value) => set('eta', value)}
        inputMode="numeric"
        error={shown('eta')}
        hint="Each round keeps 1/eta of its candidates and plays eta times as many seeds."
      />
      <TextField label="Short list size" value={draft.shortListSize} onChange={(value) => set('shortListSize', value)} inputMode="numeric" error={shown('shortListSize')} />
      <div className="actions">
        <ScopedButton scope={WRITE_SCOPE} aria-busy={start.isPending} disabled={start.isPending} onAction={submit}>
          Start campaign
        </ScopedButton>
      </div>
      {start.isError && <ErrorNotice message={describeFailure(start.error, { action: 'start the campaign', requiredScope: WRITE_SCOPE })} onRetry={submit} />}
    </form>
  )
}

function CampaignsSection() {
  const client = useApiClient()
  const navigate = useNavigate()
  const { offset = 0 } = useSearch({ from: '/campaigns' })
  const query = useQuery(campaignsQuery(client, offset))
  return (
    <section aria-labelledby="campaign-list">
      <h2 id="campaign-list">Campaigns</h2>
      <QueryView query={query} failure={{ action: 'load the campaigns' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>No campaign has been started.</p>
          ) : (
            <>
              <table>
                <caption>Campaigns, newest first</caption>
                <thead>
                  <tr>
                    <th scope="col">Campaign</th>
                    <th scope="col">Name</th>
                    <th scope="col">State</th>
                    <th scope="col">Round</th>
                    <th scope="col">Budget spent</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((campaign) => (
                    <tr key={campaign.campaignId}>
                      <th scope="row">
                        <Link to="/campaigns/$campaignId" params={{ campaignId: campaign.campaignId }}>
                          {shortId(campaign.campaignId)}
                        </Link>
                      </th>
                      <td>{campaign.name}</td>
                      <td>{CAMPAIGN_STATE_LABELS[campaign.state]}</td>
                      <td>{campaign.round}</td>
                      <td>
                        {formatSeconds(campaign.spentSeconds)} of {formatSeconds(campaign.budgetSeconds)}
                      </td>
                      <td>{formatInstant(campaign.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Campaign pages"
                offset={offset}
                count={page.items.length}
                total={page.total}
                pageSize={PAGE_SIZE}
                onOffset={(next) => void navigate({ to: '/campaigns', search: { offset: next || undefined } })}
              />
            </>
          )
        }
      </QueryView>
    </section>
  )
}
