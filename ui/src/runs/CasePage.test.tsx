import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it } from 'vitest'
import { DIVERGENCE_KINDS } from '../cases/divergenceKinds'
import { READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { FakeService, problem } from '../test/fakeService'
import { HIDDEN_HAND, IDS } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const path = `/runs/${IDS.run}/cases/${IDS.caseA}`
let service: FakeService

beforeEach(() => {
  service = new FakeService()
  service.addFailedCase({ caseId: IDS.caseA })
})

/** Everything the client holds for the replay, as text, so the absence of a fact can be asserted on the cache itself. */
function cachedReplayText(queryClient: QueryClient): string {
  return JSON.stringify(
    queryClient
      .getQueryCache()
      .findAll({ queryKey: ['replay'] })
      .map((query) => [query.queryKey, query.state.data]),
  )
}

const replayRequests = (perspective?: string) =>
  service.requests.filter((request) => request.path.endsWith('/replay') && (!perspective || request.query.get('perspective') === perspective))

describe('inspecting a case', () => {
  it('shows a failed case with its attempts and failures and says it has no result', async () => {
    await renderApp(path, service)

    expect(await screen.findByRole('heading', { name: 'Case baseline, seed 42' })).toBeInTheDocument()
    expect(screen.getByText(/Attempt 1: failed/)).toHaveTextContent('CONTRACT_MISMATCH')
    expect(screen.getByText(/This case has no result: it is failed/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Seat assignments' })).toBeInTheDocument()
  })

  it('filters a run’s cases by variant', async () => {
    const user = userEvent.setup()
    await renderApp(`/runs/${IDS.run}`, service)
    await screen.findByRole('table', { name: 'Cases of this run' })

    await user.type(screen.getByLabelText('Variant'), 'candidate')

    await waitFor(() =>
      expect(service.requests.some((request) => request.path.endsWith('/cases') && request.query.get('variantLabel') === 'candidate')).toBe(true),
    )
  })
})

describe('the player replay', () => {
  it('shows only the chosen seat’s steps and never requests or holds another perspective', async () => {
    const user = userEvent.setup()
    const { queryClient } = await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'View as player' }))

    expect(await screen.findByRole('heading', { name: 'Player view: seat 0' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Steps of seat 0' })).toHaveTextContent('seat-0-card')
    expect(document.body.textContent).not.toContain(HIDDEN_HAND)
    expect(cachedReplayText(queryClient)).not.toContain(HIDDEN_HAND)
    expect(replayRequests('OBSERVER')).toHaveLength(0)
    expect(replayRequests().map((request) => request.query.get('seatIndex'))).toEqual(['0'])
  })

  it('pages with the last step read and keeps step numbers, skipping other seats’ numbers', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'View as player' }))
    await screen.findByRole('heading', { name: 'Step 0 · ACTION · era 1 · round 1' })
    expect(screen.getByText('More steps follow.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Later steps' }))

    expect(await screen.findByRole('heading', { name: 'Step 3 · ACTION · era 1 · round 1' })).toBeInTheDocument()
    expect(replayRequests().at(-1)!.query.get('afterStep')).toBe('0')
    expect(screen.queryByText('More steps follow.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Later steps' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Earlier steps' }))
    expect(await screen.findByRole('heading', { name: 'Step 0 · ACTION · era 1 · round 1' })).toBeInTheDocument()
  })

  it('shows another seat only after the designer chooses it, and then not the first seat’s facts', async () => {
    const user = userEvent.setup()
    const { queryClient } = await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'View as player' }))
    await screen.findByRole('heading', { name: 'Player view: seat 0' })

    await user.selectOptions(screen.getByLabelText('Seat'), '2')
    await user.click(screen.getByRole('button', { name: 'View as player' }))

    expect(await screen.findByRole('heading', { name: 'Player view: seat 2' })).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('seat-0-card')
    expect(cachedReplayText(queryClient)).not.toContain('seat-0-card')
  })
})

describe('moving to another case', () => {
  it('starts that case’s replay and reproduction afresh', async () => {
    const user = userEvent.setup()
    service.addFailedCase({ caseId: IDS.caseB })
    const { router } = await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'View as player' }))
    await user.click(await screen.findByRole('button', { name: 'Later steps' }))
    await screen.findByRole('heading', { name: /^Step 3 / })

    await router.navigate({ to: '/runs/$runId/cases/$caseId', params: { runId: IDS.run, caseId: IDS.caseB } })

    await screen.findByRole('heading', { name: 'Case baseline, seed 42' })
    expect(screen.queryByRole('heading', { name: /^Player view/ })).not.toBeInTheDocument()
    expect(replayRequests().map((request) => request.query.get('afterStep'))).toEqual([null, '0'])
  })
})

describe('the observer replay', () => {
  it('is labelled, lists every seat’s steps, and keeps each source’s events as its own list in order', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))

    expect(await screen.findByRole('heading', { name: 'Observer evidence' })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('never feeds bot policies')
    const seats = screen.getByRole('list', { name: 'Steps of every seat' })
    expect(within(seats).getAllByRole('heading', { level: 5 })).toHaveLength(6)
    const game = screen.getByRole('list', { name: 'Events from game.events' })
    expect(within(game).getAllByRole('listitem').filter((item) => item.querySelector('h5')).map((item) => item.textContent)).toEqual([
      expect.stringContaining('game.events-0'),
      expect.stringContaining('game.events-1'),
    ])
    const timeline = screen.getByRole('list', { name: 'Events from timeline.events' })
    expect(within(timeline).getAllByRole('heading', { level: 5 })).toHaveLength(1)
    expect(replayRequests('OBSERVER')[0]!.query.has('seatIndex')).toBe(false)
  })

  it('is denied and labelled, without any request, when the session lacks the observe scope', async () => {
    const user = userEvent.setup()
    await renderApp(path, service, { scopes: [READ_SCOPE, WRITE_SCOPE] })

    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Observer access denied.')
    expect(screen.queryByRole('heading', { name: 'Observer evidence' })).not.toBeInTheDocument()
    expect(replayRequests()).toHaveLength(0)
  })

  it('shows the service’s own denial labelled, and no data, when the service refuses', async () => {
    const user = userEvent.setup()
    service.override('GET', /\/replay$/, () => problem(403, 'INSUFFICIENT_SCOPE', 'An OBSERVER replay requires the simulation:observe scope'))
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Observer access denied.')
    expect(screen.queryByRole('heading', { name: 'Observer evidence' })).not.toBeInTheDocument()
  })

  it('leaves no observer event or other seat’s step in the view or the cache after switching to a seat', async () => {
    const user = userEvent.setup()
    const { queryClient } = await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))
    await screen.findByRole('heading', { name: 'Observer evidence' })
    expect(cachedReplayText(queryClient)).toContain(HIDDEN_HAND)

    await user.click(screen.getByRole('button', { name: 'View as player' }))
    await screen.findByRole('heading', { name: 'Player view: seat 0' })

    for (const fact of [HIDDEN_HAND, 'game.events', 'timeline.events']) {
      expect(document.body.textContent).not.toContain(fact)
      expect(cachedReplayText(queryClient)).not.toContain(fact)
    }
    expect(queryClient.getQueryCache().findAll({ queryKey: ['replay'] }).every((query) => JSON.stringify(query.queryKey).includes('PLAYER'))).toBe(true)
  })

  it('drops the replay from the cache when the case page is left', async () => {
    const user = userEvent.setup()
    const { queryClient, router } = await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))
    await screen.findByRole('heading', { name: 'Observer evidence' })

    await router.navigate({ to: '/runs' })

    await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: ['replay'] })).toHaveLength(0))
  })
})

describe('reproduction', () => {
  const reproduceButton = () => screen.getByRole('button', { name: /Reproduce/ })
  const reproduceRequests = () => service.requests.filter((request) => request.method === 'POST' && request.path.endsWith('/reproductions'))

  it('says a queued reproduction is waiting for the lane, not failing, then shows the match with the same key', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'Reproduce this case' }))

    expect(await screen.findByText(/queued behind other work on the lane that played this case/)).toBeInTheDocument()
    expect(screen.queryByText(/The reproduction failed/)).not.toBeInTheDocument()

    expect(await screen.findByText(/matched the recording exactly/, undefined, { timeout: 6000 })).toBeInTheDocument()
    const keys = reproduceRequests().map((request) => request.headers.get('Idempotency-Key'))
    expect(keys.length).toBeGreaterThanOrEqual(2)
    expect(new Set(keys).size).toBe(1)
  }, 15_000)

  it('shows the first divergence with step, kind, expected and actual, leaving the case’s own result alone', async () => {
    const user = userEvent.setup()
    service.reproductionScript = ['DIVERGED']
    service.reproductionDivergence = { step: 12, kind: 'DECISION', expected: { candidate: 'card-1' }, actual: { candidate: 'card-9' } }
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'Reproduce this case' }))

    const outcome = await screen.findByText(/The first difference is at step 12/)
    expect(outcome).toHaveTextContent('DECISION')
    expect(screen.getByText('Expected (the recording)')).toBeInTheDocument()
    expect(screen.getByText('card-1')).toBeInTheDocument()
    expect(screen.getByText('card-9')).toBeInTheDocument()
    expect(screen.getByText(/This case has no result/)).toBeInTheDocument()
  })

  it.each(Object.keys(DIVERGENCE_KINDS))('describes a %s divergence', async (kind) => {
    const user = userEvent.setup()
    service.reproductionScript = ['DIVERGED']
    service.reproductionDivergence = { step: 3, kind, expected: {}, actual: {} }
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'Reproduce this case' }))

    await screen.findByText(/The first difference is at step 3/)
    expect(screen.getByText(DIVERGENCE_KINDS[kind]!)).toBeInTheDocument()
  })

  it('says a failed reproduction tells nothing about the case, and asking again is a new request', async () => {
    const user = userEvent.setup()
    service.reproductionScript = ['FAILED', 'MATCH']
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'Reproduce this case' }))

    const failure = await screen.findByText(/The reproduction failed\./)
    expect(failure.closest('p')).toHaveTextContent('says nothing about the case')

    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText(/matched the recording exactly/)).toBeInTheDocument()
    const keys = reproduceRequests().map((request) => request.headers.get('Idempotency-Key'))
    expect(new Set(keys).size).toBe(2)
  })

  it('explains a manifest mismatch, including a case with no saved result or transcript', async () => {
    const user = userEvent.setup()
    service.override('POST', /reproductions$/, () => problem(409, 'MANIFEST_MISMATCH', 'No transcript is stored for this case.'))
    await renderApp(path, service)
    await user.click(await screen.findByRole('button', { name: 'Reproduce this case' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/has no saved result or transcript/)
  })

  it('is unavailable without the write scope, naming it', async () => {
    const user = userEvent.setup()
    await renderApp(path, service, { scopes: [READ_SCOPE] })
    await screen.findByRole('heading', { name: 'Reproduction' })

    expect(screen.getByText(/requires the simulation:write scope/)).toBeInTheDocument()
    await user.click(reproduceButton())
    expect(reproduceRequests()).toHaveLength(0)
  })
})
