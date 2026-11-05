import type { Campaign, CampaignLogEntry } from '../api/generated'
import { json } from './fakeService'
import type { FakeService } from './fakeService'

export const CAMPAIGN_IDS = {
  campaign: 'a1b2c3d4-5e6f-4a71-8b92-a3b4c5d6e7f8',
  other: 'b2c3d4e5-6f70-4b82-9ca3-b4c5d6e7f809',
} as const

const digest = (character: string) => character.repeat(64)

export function campaign(overrides: Partial<Campaign> = {}): Campaign {
  return {
    campaignId: CAMPAIGN_IDS.campaign,
    name: 'threshold-halving-01',
    baseline: { rulesArtifact: { digest: digest('a') }, contentArtifact: { digest: digest('b') } },
    parameterSpaceVersion: 1,
    criterionVersion: 1,
    policies: [
      {
        id: 'faction-greedy',
        version: '1.0.0',
        artifactDigest: digest('c'),
        parameters: {},
        seatAssignment: [{ seatIndex: 0, policyId: 'faction-greedy', policyVersion: '1.0.0' }],
      },
    ],
    playerCounts: [3],
    budgetSeconds: 14400,
    parallelism: 4,
    searchMasterSeed: '7',
    confirmationMasterSeed: '9',
    algorithm: { version: 'SUCCESSIVE_HALVING_V1', initialCandidates: 32, screeningSeeds: 2, eta: 2, shortListSize: 1 },
    state: 'RUNNING',
    spentSeconds: 3725,
    round: 1,
    shortList: [],
    confirmation: null,
    failure: null,
    createdAt: '2026-01-01T00:00:00Z',
    startedAt: '2026-01-01T00:00:05Z',
    finishedAt: null,
    ...overrides,
  }
}

export function logEntry(overrides: Partial<CampaignLogEntry> = {}): CampaignLogEntry {
  return {
    sequence: 1,
    round: 0,
    candidateId: 'c-0001',
    overrides: { 'game.rules.win-score-threshold': 22 },
    effectiveRulesDigest: digest('d'),
    seeds: [],
    gamesSpent: 0,
    redraws: 0,
    evaluation: null,
    decision: 'GENERATED',
    reason: null,
    evidence: null,
    ...overrides,
  }
}

/** Answers the campaign operations of a test from the given records and lets commands change the first campaign. */
export function serveCampaigns(service: FakeService, state: { campaigns?: Campaign[]; log?: CampaignLogEntry[] } = {}): { current: Campaign[] } {
  const held = { current: state.campaigns ?? [campaign()] }
  const log = state.log ?? [logEntry()]
  service.override('GET', /^\/api\/v1\/campaigns$/, () => json({ items: held.current, total: held.current.length }))
  service.override('GET', /^\/api\/v1\/campaigns\/([^/]+)$/, (_request, match) => {
    const found = held.current.find((item) => item.campaignId === match[1])
    return found ? json(found) : json({ type: 'about:blank', title: 'Problem', status: 404, detail: 'No such campaign.', code: 'RESOURCE_NOT_FOUND' }, 404)
  })
  service.override('GET', /^\/api\/v1\/campaigns\/[^/]+\/log$/, (request) => {
    const offset = Number(request.query.get('offset') ?? 0)
    const limit = Number(request.query.get('limit') ?? 50)
    return json({ items: log.slice(offset, offset + limit), total: log.length })
  })
  const change = (state: Campaign['state']) => (_request: unknown, match: RegExpExecArray) => {
    const found = held.current.find((item) => item.campaignId === match[1])!
    const updated = { ...found, state }
    held.current = held.current.map((item) => (item.campaignId === updated.campaignId ? updated : item))
    return json(updated)
  }
  service.override('POST', /^\/api\/v1\/campaigns\/([^/]+)\/pause$/, change('PAUSED'))
  service.override('POST', /^\/api\/v1\/campaigns\/([^/]+)\/resume$/, change('RUNNING'))
  service.override('POST', /^\/api\/v1\/campaigns\/([^/]+)\/cancel$/, change('CANCELLED'))
  service.override('PUT', /^\/api\/v1\/campaigns\/([^/]+)\/parallelism$/, (request, match) => {
    const found = held.current.find((item) => item.campaignId === match[1])!
    const updated = { ...found, parallelism: (request.body as { parallelism: number }).parallelism }
    held.current = held.current.map((item) => (item.campaignId === updated.campaignId ? updated : item))
    return json(updated)
  })
  return held
}
