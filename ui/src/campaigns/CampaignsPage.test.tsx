import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from '../auth/scopes'
import { CAMPAIGN_IDS, campaign, serveCampaigns } from '../test/campaignFixtures'
import { FakeService, json, problem } from '../test/fakeService'
import { renderApp } from '../test/renderApp'

let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

describe('the campaigns', () => {
  it('lists them newest first with their state, round and budget', async () => {
    serveCampaigns(service, {
      campaigns: [
        campaign(),
        campaign({ campaignId: CAMPAIGN_IDS.other, name: 'second', state: 'PAUSED', round: 2, spentSeconds: 60 }),
      ],
    })
    await renderApp('/campaigns', service)

    const table = await screen.findByRole('table', { name: 'Campaigns, newest first' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]!).getByText('Running')).toBeInTheDocument()
    expect(within(rows[0]!).getByText('1 h 02 min 05 s of 4 h 00 min 00 s')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('Paused')).toBeInTheDocument()
    expect(within(rows[1]!).getByRole('link')).toHaveAttribute('href', `/campaigns/${CAMPAIGN_IDS.other}`)
  })

  it('says so when none has been started', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp('/campaigns', service)

    expect(await screen.findByText('No campaign has been started.')).toBeInTheDocument()
  })
})

describe('starting a campaign', () => {
  async function fillName(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText('Name'), 'first search')
  }

  async function fill(user: ReturnType<typeof userEvent.setup>, search: string, confirmation: string) {
    await fillName(user)
    await user.type(screen.getByLabelText('Search master seed'), search)
    await user.type(screen.getByLabelText('Confirmation master seed'), confirmation)
  }

  it('asks only for the short form and leaves everything else to the service', async () => {
    serveCampaigns(service, { campaigns: [] })
    service.override('POST', /^\/api\/v1\/campaigns$/, () => json(campaign({ state: 'QUEUED', spentSeconds: 0, round: 0, startedAt: null }), 201))
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fillName(user)
    expect(screen.getByLabelText('Budget (hours of active time)')).toHaveValue('4')
    expect(screen.getByLabelText('Games at once')).toHaveValue('4')
    expect(screen.getByText('Advanced').closest('details')).not.toHaveProperty('open', true)
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    await waitFor(() => expect(service.count('POST', /campaigns$/)).toBe(1))
    const request = service.requests.find((item) => item.method === 'POST')!
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(request.body).toMatchObject({ name: 'first search', playerCounts: [3], budgetSeconds: 14400, parallelism: 4 })
    for (const omitted of ['parameterSpaceVersion', 'criterionVersion', 'searchMasterSeed', 'confirmationMasterSeed', 'algorithm']) {
      expect(request.body).not.toHaveProperty(omitted)
    }
    expect(await screen.findByRole('heading', { name: /Campaign threshold-halving-01/ })).toBeInTheDocument()
  })

  it('converts the budget from hours to seconds', async () => {
    serveCampaigns(service, { campaigns: [] })
    service.override('POST', /^\/api\/v1\/campaigns$/, () => json(campaign({ state: 'QUEUED' }), 201))
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fillName(user)
    await user.clear(screen.getByLabelText('Budget (hours of active time)'))
    await user.type(screen.getByLabelText('Budget (hours of active time)'), '1.5')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    await waitFor(() => expect(service.count('POST', /campaigns$/)).toBe(1))
    expect(service.requests.find((item) => item.method === 'POST')!.body).toMatchObject({ budgetSeconds: 5400 })
  })

  it('refuses a budget that is not a number of hours or is under a minute', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fillName(user)
    await user.clear(screen.getByLabelText('Budget (hours of active time)'))
    await user.type(screen.getByLabelText('Budget (hours of active time)'), '0.001')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    expect(await screen.findByText('Enter a number of hours of at least 0.02 (one minute).')).toBeInTheDocument()
    expect(service.count('POST', /campaigns$/)).toBe(0)
  })

  it('sends only the advanced values the designer filled in', async () => {
    serveCampaigns(service, { campaigns: [] })
    service.override('POST', /^\/api\/v1\/campaigns$/, () => json(campaign({ state: 'QUEUED' }), 201))
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fillName(user)
    await user.type(screen.getByLabelText('Candidates in round 0'), '10')
    await user.type(screen.getByLabelText('Search master seed'), '7')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    await waitFor(() => expect(service.count('POST', /campaigns$/)).toBe(1))
    const body = service.requests.find((item) => item.method === 'POST')!.body
    expect(body).toMatchObject({ searchMasterSeed: '7', algorithm: { initialCandidates: 10 } })
    expect((body as { algorithm: object }).algorithm).toEqual({ initialCandidates: 10 })
    for (const omitted of ['parameterSpaceVersion', 'criterionVersion', 'confirmationMasterSeed']) {
      expect(body).not.toHaveProperty(omitted)
    }
  })

  it('sends the definition with an idempotency key and opens the campaign', async () => {
    serveCampaigns(service, { campaigns: [] })
    service.override('POST', /^\/api\/v1\/campaigns$/, () => json(campaign({ state: 'QUEUED', spentSeconds: 0, round: 0, startedAt: null }), 201))
    serveCampaigns(service, { campaigns: [campaign({ state: 'QUEUED', spentSeconds: 0, round: 0, startedAt: null })] })
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fill(user, '7', '9')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    await waitFor(() => expect(service.count('POST', /campaigns$/)).toBe(1))
    const request = service.requests.find((item) => item.method === 'POST')!
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(request.body).toMatchObject({
      name: 'first search',
      playerCounts: [3],
      budgetSeconds: 14400,
      parallelism: 4,
      searchMasterSeed: '7',
      confirmationMasterSeed: '9',
    })
    // The service resolves the policies from the criterion's tables, so the request names none.
    expect(request.body).not.toHaveProperty('policies')
    expect(await screen.findByRole('heading', { name: /Campaign threshold-halving-01/ })).toBeInTheDocument()
  })

  it('refuses equal master seeds before sending anything', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fill(user, '7', '7')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    expect(await screen.findByText('The confirmation master seed must differ from the search master seed.')).toBeInTheDocument()
    expect(screen.getByLabelText('Confirmation master seed')).toHaveAttribute('aria-invalid', 'true')
    expect(service.count('POST', /campaigns$/)).toBe(0)
  })

  it('refuses a missing name and no player count', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await user.click(await screen.findByLabelText('3 players'))
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))

    expect(await screen.findByText('Give the campaign a name of 1 to 120 characters.')).toBeInTheDocument()
    expect(screen.getByText('Choose at least one player count.')).toBeInTheDocument()
    expect(service.count('POST', /campaigns$/)).toBe(0)
  })

  it('shows the service refusal on the field it names and repeats the key on retry', async () => {
    serveCampaigns(service, { campaigns: [] })
    service.override('POST', /^\/api\/v1\/campaigns$/, () => json(campaign({ state: 'QUEUED' }), 201))
    service.failOnce('POST', /^\/api\/v1\/campaigns$/, () => problem(400, 'INVALID_CAMPAIGN', 'Criterion 1 is not served', 'criterionVersion', { reason: 'UNKNOWN_CRITERION' }))
    await renderApp('/campaigns', service)
    const user = userEvent.setup()

    await fill(user, '7', '9')
    await user.click(screen.getByRole('button', { name: 'Start campaign' }))
    expect(await screen.findByText(/the campaign definition was refused/)).toBeInTheDocument()
    expect(screen.getAllByText('Criterion 1 is not served').length).toBeGreaterThan(0)
    expect(screen.getByText('Advanced').closest('details')).toHaveProperty('open', true)
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(service.count('POST', /campaigns$/)).toBe(2))
    const keys = service.requests.filter((item) => item.method === 'POST').map((item) => item.headers.get('Idempotency-Key'))
    expect(keys[0]).toBe(keys[1])
  })

  it('names the scope it needs when the session cannot write', async () => {
    serveCampaigns(service, { campaigns: [] })
    await renderApp('/campaigns', service, { scopes: [READ_SCOPE] })

    expect(await screen.findByText('Unavailable: requires the simulation:write scope.')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Start campaign' }))
    expect(service.count('POST', /campaigns$/)).toBe(0)
  })
})
