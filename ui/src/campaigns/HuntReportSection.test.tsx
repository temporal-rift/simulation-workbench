import { screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import type { HuntReport } from '../api/generated'
import { CAMPAIGN_IDS, campaign, serveCampaigns } from '../test/campaignFixtures'
import { FakeService } from '../test/fakeService'
import { renderApp } from '../test/renderApp'

let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

const PATH = `/campaigns/${CAMPAIGN_IDS.campaign}`

const LIMITS = [
  'A cell without a flag is evidence, not a guarantee, that no exploit exists.',
  'Results characterize bot strategies only and say nothing about balance among human players.',
]

function report(overrides: Partial<HuntReport>): HuntReport {
  return {
    faction: 'WEAVERS',
    playerCount: 5,
    screening: { candidates: 64, screeningRatioOfBest: 1.31, noise: 0.31, shareAboveFlagLine: 0.04 },
    classification: 'NOT_CONFIRMED',
    confirmation: null,
    limitations: LIMITS,
    ...overrides,
  }
}

describe('a policy hunt', () => {
  it('says it is searching until it has a report', async () => {
    serveCampaigns(service, { campaigns: [campaign({ kind: 'POLICY_HUNT', faction: 'WEAVERS', playerCounts: [5], huntReport: null })] })
    await renderApp(PATH, service)

    expect(await screen.findByRole('heading', { name: 'Policy hunt' })).toBeInTheDocument()
    const hunt = screen.getByRole('region', { name: 'Policy hunt' })
    expect(within(hunt).getByRole('status')).toHaveTextContent('Searching')
    expect(screen.queryByRole('heading', { name: 'Confirmation' })).not.toBeInTheDocument()
  })

  it('states the screening noise, the share above the flag line and both limits of an unconfirmed cell', async () => {
    serveCampaigns(service, {
      campaigns: [campaign({ state: 'COMPLETED', kind: 'POLICY_HUNT', faction: 'WEAVERS', playerCounts: [5], huntReport: report({}) })],
    })
    await renderApp(PATH, service)

    expect(await screen.findByText('Not confirmed', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/64 candidates screened/)).toBeInTheDocument()
    expect(screen.getByText(/Realized screening noise.*0\.31/)).toBeInTheDocument()
    expect(screen.getByText(/Share of candidates at or above the flag line: 4\.0%/)).toBeInTheDocument()
    expect(screen.getByText(/evidence, not a guarantee/)).toBeInTheDocument()
    expect(screen.getByText(/bot strategies only/)).toBeInTheDocument()
  })

  it('separates the flag line from the exploit line by the normal ratio', async () => {
    serveCampaigns(service, {
      campaigns: [
        campaign({
          state: 'COMPLETED',
          kind: 'POLICY_HUNT',
          faction: 'WEAVERS',
          playerCounts: [5],
          huntReport: report({
            classification: 'FLAGGED',
            confirmation: {
              candidateRatio: 2.6,
              candidateInterval: { lower: 2.2, upper: 3.0 },
              standardRatio: 2.0,
              normalRatio: 2.0,
              parameters: { 'affinity.card.WEAVERS.SCAN': 8 },
            },
          }),
        }),
      ],
    })
    await renderApp(PATH, service)

    expect(await screen.findByText('Flagged')).toBeInTheDocument()
    expect(screen.getByText(/Normal ratio 2\.00: flagged from 2\.50, exploit from 3\.00/)).toBeInTheDocument()
    expect(screen.getByText(/Candidate win ratio 2\.60/)).toBeInTheDocument()
    expect(screen.getByText('affinity.card.WEAVERS.SCAN')).toBeInTheDocument()
  })
})
