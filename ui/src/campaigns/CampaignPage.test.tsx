import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from '../auth/scopes'
import { CAMPAIGN_IDS, CONFIRMATION_RUNS, campaign, confirmation, logEntry, serveCampaigns } from '../test/campaignFixtures'
import { FakeService, problem } from '../test/fakeService'
import { evaluation, run } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

const PATH = `/campaigns/${CAMPAIGN_IDS.campaign}`

describe('a running campaign', () => {
  it('shows its state, budget spent and remaining, round and games at once', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service)

    expect(await screen.findByRole('heading', { name: /Campaign threshold-halving-01/ })).toBeInTheDocument()
    expect(screen.getByText('State: Running')).toBeInTheDocument()
    expect(screen.getByText('1 h 02 min 05 s')).toBeInTheDocument()
    expect(screen.getByText('2 h 57 min 55 s')).toBeInTheDocument()
    expect(screen.getByText('None yet')).toBeInTheDocument()
  })

  it('shows the algorithm, versions and master seeds the service resolved', async () => {
    serveCampaigns(service, {
      campaigns: [campaign({
        parameterSpaceVersion: 2,
        criterionVersion: 3,
        searchMasterSeed: '12345678901234567890',
        confirmationMasterSeed: '98765432109876543210',
        algorithm: { version: 'SUCCESSIVE_HALVING_V1', initialCandidates: 32, screeningSeeds: 2, eta: 2, shortListSize: 1 },
      })],
    })
    await renderApp(PATH, service)

    await screen.findByRole('heading', { name: /Campaign threshold-halving-01/ })
    const value = (term: string) => screen.getByText(term).nextElementSibling
    expect(value('Candidates in round 0')).toHaveTextContent('32')
    expect(value('Seeds in round 0')).toHaveTextContent('2')
    expect(value('Eta')).toHaveTextContent('2')
    expect(value('Short list size')).toHaveTextContent('1')
    expect(value('Parameter space version')).toHaveTextContent('2')
    expect(value('Criterion version')).toHaveTextContent('3')
    expect(value('Search master seed')).toHaveTextContent('12345678901234567890')
    expect(value('Confirmation master seed')).toHaveTextContent('98765432109876543210')
  })

  it('offers pause and cancel but not resume', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service)

    expect(await screen.findByRole('button', { name: 'Pause campaign' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel campaign' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume campaign' })).not.toBeInTheDocument()
  })

  it('pauses, then offers resume, and sends an idempotency key with each command', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Pause campaign' }))
    expect(await screen.findByText('State: Paused')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Resume campaign' }))
    expect(await screen.findByText('State: Running')).toBeInTheDocument()

    const commands = service.requests.filter((request) => request.method === 'POST')
    expect(commands.map((request) => request.path)).toEqual([`${PATH.replace('/campaigns', '/api/v1/campaigns')}/pause`, `${PATH.replace('/campaigns', '/api/v1/campaigns')}/resume`])
    commands.forEach((request) => expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/))
  })

  it('changes the games at once', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service)
    const user = userEvent.setup()

    const field = await screen.findByLabelText('Games at once', { selector: 'input' })
    await user.clear(field)
    await user.type(field, '2')
    await user.click(screen.getByRole('button', { name: 'Set games at once' }))

    await waitFor(() => expect(service.count('PUT', /parallelism$/)).toBe(1))
    expect(service.requests.find((request) => request.method === 'PUT')!.body).toEqual({ parallelism: 2 })
  })

  it('refuses a number of games below one before sending anything', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service)
    const user = userEvent.setup()

    const field = await screen.findByLabelText('Games at once', { selector: 'input' })
    await user.clear(field)
    await user.type(field, '0')

    expect(field).toHaveAttribute('aria-invalid', 'true')
    await user.click(screen.getByRole('button', { name: 'Set games at once' }))
    expect(service.count('PUT', /parallelism$/)).toBe(0)
  })

  it('names the state the campaign is really in when the service refuses a command', async () => {
    const held = serveCampaigns(service)
    service.override('POST', /\/pause$/, () => {
      held.current = [campaign({ state: 'SEARCH_COMPLETED' })]
      return problem(409, 'INVALID_CAMPAIGN_STATE', 'The campaign is search completed')
    })
    await renderApp(PATH, service)

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Pause campaign' }))

    expect(await screen.findByText(/The campaign is now search completed, which does not allow that/)).toBeInTheDocument()
  })

  it('needs the write scope to act and says which', async () => {
    serveCampaigns(service)
    await renderApp(PATH, service, { scopes: [READ_SCOPE] })

    expect((await screen.findAllByText('Unavailable: requires the simulation:write scope.')).length).toBeGreaterThan(0)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Pause campaign' }))
    expect(service.count('POST', /pause$/)).toBe(0)
  })
})

describe('a campaign that has not started', () => {
  it('shows the fidelity gate as the reason it is waiting', async () => {
    serveCampaigns(service, {
      campaigns: [campaign({ state: 'QUEUED', spentSeconds: 0, round: 0, startedAt: null, failure: { code: 'FIDELITY_NOT_ESTABLISHED', message: 'No passing record' } })],
    })
    await renderApp(PATH, service)

    expect(await screen.findByText(/This campaign has not started: the simulator has no passing fidelity record/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Simulator fidelity' })).toHaveLength(2)
  })
})

describe('a campaign that ended', () => {
  it('lists the short list and offers no commands', async () => {
    serveCampaigns(service, { campaigns: [campaign({ state: 'SEARCH_COMPLETED', shortList: ['c-0003', 'c-0007'], finishedAt: '2026-01-01T03:00:00Z' })] })
    await renderApp(PATH, service)

    expect(await screen.findByText('c-0003, c-0007')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pause campaign' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume campaign' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set games at once' })).not.toBeInTheDocument()
  })

  it('shows why a failed campaign failed', async () => {
    serveCampaigns(service, { campaigns: [campaign({ state: 'FAILED', failure: { code: 'RUN_FAILED', message: 'Wave 2 failed' } })] })
    await renderApp(PATH, service)

    expect(await screen.findByText('RUN_FAILED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel campaign' })).not.toBeInTheDocument()
  })

  it('says so when the campaign does not exist', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp(PATH, service)

    expect(await screen.findByText(/it does not exist or is not visible to you/)).toBeInTheDocument()
  })
})

describe('the decision log', () => {
  it('shows each decision with its reason, evidence, evaluation and overrides', async () => {
    serveCampaigns(service, {
      log: [
        logEntry(),
        logEntry({
          sequence: 2,
          decision: 'DISCARDED',
          reason: 'GATE_FAILS',
          evidence: { lineIds: ['G-01'] },
          evaluation: { verdict: 'REJECTED', score: null },
          seeds: ['11', '12'],
          gamesSpent: 2,
        }),
        logEntry({ sequence: 3, candidateId: 'c-0002', overrides: {}, decision: 'DISCARDED', reason: 'DOMINATED', evidence: { dominatedBy: 'c-0001' } }),
      ],
    })
    await renderApp(PATH, service)

    const table = await screen.findByRole('table', { name: 'Decisions, in the order they were taken' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]!).getByText('Generated')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('win-score-threshold = 22')).toBeInTheDocument()
    expect(within(rows[1]!).getByText(/A gate line fails \(G-01\)/)).toBeInTheDocument()
    expect(within(rows[1]!).getByText('REJECTED')).toBeInTheDocument()
    expect(within(rows[2]!).getByText(/Dominated by/)).toBeInTheDocument()
    expect(within(rows[2]!).getByText('baseline')).toBeInTheDocument()
  })

  it('pages the log', async () => {
    const entries = Array.from({ length: 60 }, (_, index) => logEntry({ sequence: index + 1, candidateId: `c-${String(index).padStart(4, '0')}` }))
    serveCampaigns(service, { log: entries })
    await renderApp(PATH, service)
    const user = userEvent.setup()

    const pager = await screen.findByRole('navigation', { name: 'Decision log pages' })
    expect(within(pager).getByText('1–50 of 60')).toBeInTheDocument()
    await user.click(within(pager).getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('51–60 of 60')).toBeInTheDocument()
  })

  it('says so when nothing was decided yet', async () => {
    serveCampaigns(service, { log: [] })
    await renderApp(PATH, service)

    expect(await screen.findByText('The campaign has made no decision yet.')).toBeInTheDocument()
  })
})

describe('confirming the short list', () => {
  const searched = () => campaign({ state: 'SEARCH_COMPLETED', shortList: ['c-0001'], finishedAt: null })

  it('offers to confirm only once the search is complete', async () => {
    serveCampaigns(service, { campaigns: [campaign()] })
    await renderApp(PATH, service)

    await screen.findByRole('button', { name: 'Pause campaign' })
    expect(screen.queryByRole('button', { name: 'Confirm short list' })).not.toBeInTheDocument()
  })

  it('starts the confirmation and shows its runs while they play', async () => {
    serveCampaigns(service, { campaigns: [searched()] })
    service.runs.set(CONFIRMATION_RUNS.baseline.run, run({ runId: CONFIRMATION_RUNS.baseline.run }))
    service.runs.set(CONFIRMATION_RUNS.candidate.run, run({ runId: CONFIRMATION_RUNS.candidate.run, state: 'QUEUED' }))
    await renderApp(PATH, service)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Confirm short list' }))

    expect(await screen.findByText('State: Confirming')).toBeInTheDocument()
    const report = await screen.findByRole('region', { name: 'Confirmation' })
    expect(within(report).getByText(/100 fresh seeds/)).toBeInTheDocument()
    expect(within(report).getByRole('region', { name: 'Baseline' })).toBeInTheDocument()
    expect(within(report).getByRole('region', { name: 'Candidate c-0001' })).toBeInTheDocument()
    expect(await within(report).findByText(/Waiting for its turn/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm short list' })).not.toBeInTheDocument()
  })

  it('shows a confirmed candidate with every line, the sample and the human balance statement', async () => {
    serveCampaigns(service, {
      campaigns: [{ ...searched(), state: 'COMPLETED', confirmation: confirmation(true), finishedAt: '2026-01-01T05:00:00Z' }],
    })
    service.runs.set(CONFIRMATION_RUNS.baseline.run, run({ runId: CONFIRMATION_RUNS.baseline.run, state: 'COMPLETED' }))
    service.runs.set(CONFIRMATION_RUNS.candidate.run, run({ runId: CONFIRMATION_RUNS.candidate.run, state: 'COMPLETED' }))
    service.evaluationFor = (variantLabel, criterionVersion, mode) =>
      evaluation({
        variantLabel,
        criterionVersion,
        mode,
        verdict: variantLabel === 'c-0001' ? 'CONFIRMED' : 'UNCONFIRMED',
        confirmationSample: { requiredSeeds: 100, seeds: 100, fullMatrix: true, satisfied: true },
      })
    await renderApp(PATH, service)

    const report = await screen.findByRole('region', { name: 'Confirmation' })
    expect(within(report).getByText('Bot results say nothing about balance among human players.')).toBeInTheDocument()
    const candidate = await within(report).findByRole('region', { name: 'Candidate c-0001' })
    expect(await within(candidate).findByText(/Verdict: Confirmed/)).toBeInTheDocument()
    expect(within(candidate).getByText('Recorded verdict: Confirmed')).toBeInTheDocument()
    expect(within(candidate).getByText(/100 of 100 seeds, full case matrix: satisfied/)).toBeInTheDocument()
    expect(within(candidate).getByRole('table', { name: 'Evaluated lines' })).toBeInTheDocument()
    const baseline = within(report).getByRole('region', { name: 'Baseline' })
    expect(await within(baseline).findByText(/Verdict: Unconfirmed/)).toBeInTheDocument()
  })
})
