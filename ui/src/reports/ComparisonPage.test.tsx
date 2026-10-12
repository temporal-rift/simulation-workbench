import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from '../auth/scopes'
import { FakeService, problem } from '../test/fakeService'
import { COMPARISON_ID, IDS, comparison } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const SEATS = [
  { seatIndex: 0, faction: 'ERASERS' as const },
  { seatIndex: 1, faction: 'PROPHETS' as const },
  { seatIndex: 2, faction: 'WEAVERS' as const },
]

let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

describe('creating a comparison', () => {
  async function fillIn(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText('Baseline run ID'), IDS.run)
    await user.type(screen.getByLabelText('Baseline variant'), 'baseline')
    await user.type(screen.getByLabelText('Candidate run ID'), IDS.run)
    await user.type(screen.getByLabelText('Candidate variant'), 'candidate')
  }

  it('opens the comparison the service created', async () => {
    const user = userEvent.setup()
    await renderApp('/comparisons', service)
    await fillIn(user)

    await user.click(screen.getByRole('button', { name: 'Compare' }))

    expect(await screen.findByRole('heading', { name: 'Comparison of baseline and candidate' })).toBeInTheDocument()
    expect(service.comparisons.size).toBe(1)
  })

  it('lists the comparisons made', async () => {
    service.comparisons.set(COMPARISON_ID, comparison())
    await renderApp('/comparisons', service)

    const table = await screen.findByRole('table', { name: 'Comparisons, newest first' })
    expect(within(table).getByText(/baseline of run 0b9d9d2c/)).toBeInTheDocument()
  })

  it('names the differences when the runs are incomparable and shows no comparison', async () => {
    const user = userEvent.setup()
    service.override('POST', /\/comparisons$/, () => problem(422, 'INCOMPARABLE_RUNS', 'The sides differ in: services.gameService, contracts.'))
    await renderApp('/comparisons', service)
    await fillIn(user)

    await user.click(screen.getByRole('button', { name: 'Compare' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('These runs cannot be compared')
    expect(alert).toHaveTextContent('services.gameService, contracts')
    expect(screen.queryByRole('heading', { name: /Comparison of/ })).not.toBeInTheDocument()
  })

  it('is unavailable without the write scope, naming it', async () => {
    await renderApp('/comparisons', service, { scopes: [READ_SCOPE] })

    expect(await screen.findByText(/requires the simulation:write scope/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Compare' })).toHaveAttribute('aria-disabled', 'true')
  })
})

describe('a comparison', () => {
  it('shows the declared differences, the matched counts and the paired differences', async () => {
    service.comparisons.set(COMPARISON_ID, comparison())
    await renderApp(`/comparisons/${COMPARISON_ID}`, service)

    expect(await screen.findByRole('heading', { name: 'Comparison of baseline and candidate' })).toBeInTheDocument()
    const declared = screen.getByRole('table', { name: 'Definitions that differ between the sides' })
    expect(within(declared).getByRole('row', { name: /RULES/ })).toBeInTheDocument()
    const paired = screen.getByRole('table', { name: /Paired differences/ })
    const row = within(paired).getByRole('row', { name: /faction_win_rate/ })
    expect(within(row).getByText('0.25 share')).toBeInTheDocument()
    expect(within(row).getByText('0.05 to 0.45 (95%)')).toBeInTheDocument()
    expect(screen.queryByText(/Partial comparison/)).not.toBeInTheDocument()
  })

  it('is marked partial and lists what it excluded and why', async () => {
    service.comparisons.set(
      COMPARISON_ID,
      comparison({
        complete: false,
        unmatchedCases: 1,
        failedCounterparts: 1,
        excludedPairs: [
          {
            seed: '42',
            playerCount: 3,
            policyId: 'random',
            policyVersion: 'v1',
            seats: SEATS,
            baselineCaseId: IDS.caseA,
            candidateCaseId: null,
            baselineState: 'SUCCEEDED',
            candidateState: 'FAILED',
            reason: 'FAILED_COUNTERPART',
          },
          {
            seed: '43',
            playerCount: 3,
            policyId: 'random',
            policyVersion: 'v1',
            seats: SEATS,
            baselineCaseId: IDS.caseB,
            candidateCaseId: null,
            baselineState: 'SUCCEEDED',
            candidateState: 'RUNNING',
            reason: 'UNFINISHED',
          },
        ],
      }),
    )
    await renderApp(`/comparisons/${COMPARISON_ID}`, service)

    expect(await screen.findByText(/Partial comparison: 1 pairs wait on an unfinished case and 1 have a failed or cancelled side/)).toBeInTheDocument()
    const excluded = screen.getByRole('table', { name: 'Pairs left out of the differences' })
    expect(within(excluded).getByText('A side failed or was cancelled')).toBeInTheDocument()
    expect(within(excluded).getByText('A side is still pending or running')).toBeInTheDocument()
  })

  it('keeps the data and offers a retry when the service cannot be reached', async () => {
    service.comparisons.set(COMPARISON_ID, comparison())
    const { queryClient } = await renderApp(`/comparisons/${COMPARISON_ID}`, service)
    await screen.findByRole('heading', { name: 'Comparison of baseline and candidate' })

    service.unreachable = true
    await queryClient.refetchQueries({ queryKey: ['comparison'] })

    expect(await screen.findByText(/Showing the last data received/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Comparison of baseline and candidate' })).toBeInTheDocument()
  })
})
