import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { FakeService, problem } from '../test/fakeService'
import { IDS, manifest } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { draftToManifest, loadDraft, saveDraft, emptyDraft } from './draft'

const path = `/experiments/${IDS.experiment}`
let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

describe('a frozen experiment', () => {
  it('is read-only and offers a new draft built from its manifest, leaving it unchanged', async () => {
    const user = userEvent.setup()
    const before = JSON.stringify(service.experiments.get(IDS.experiment))
    await renderApp(path, service)

    await screen.findByRole('heading', { name: 'threshold experiment' })
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change: new draft from this experiment' }))

    await screen.findByRole('heading', { name: 'New experiment' })
    expect(screen.getByText(/Started from the frozen experiment “threshold experiment”/)).toBeInTheDocument()
    expect(draftToManifest(loadDraft()!.draft)).toEqual(manifest())
    expect(JSON.stringify(service.experiments.get(IDS.experiment))).toBe(before)
    expect(service.count('POST', /experiments/)).toBe(0)
  })

  it('asks before replacing an unsent draft', async () => {
    const user = userEvent.setup()
    saveDraft({ draft: { ...emptyDraft(), name: 'mine' }, basedOn: null })
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Change: new draft from this experiment' }))
    expect(screen.getByText(/You have an unsent draft/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Keep my draft' }))

    expect(loadDraft()!.draft.name).toBe('mine')
    expect(screen.queryByText(/You have an unsent draft/)).not.toBeInTheDocument()
  })

  it('shows launching unavailable, naming the scope, to a read-only designer', async () => {
    await renderApp(path, service, { scopes: [READ_SCOPE] })

    const launch = await screen.findByRole('button', { name: 'Launch a run' })
    expect(launch).toHaveAttribute('aria-disabled', 'true')
    expect(launch).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)
  })

  it('launches a run and opens it', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Launch a run' }))

    await screen.findByText(/State: Queued/)
    expect(service.count('POST', /\/runs$/)).toBe(1)
  })

  it('reuses the launch key when a launch is retried after a failure', async () => {
    const user = userEvent.setup()
    service.failOnce('POST', /\/runs$/, () => problem(503, 'UNAVAILABLE', 'try later'))
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Launch a run' }))
    await user.click(await screen.findByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(service.count('POST', /\/runs$/)).toBe(2))
    const keys = service.requests.filter((request) => request.method === 'POST' && /\/runs$/.test(request.path)).map((request) => request.headers.get('Idempotency-Key'))
    expect(new Set(keys).size).toBe(1)
  })

  it('reports an unknown experiment plainly', async () => {
    await renderApp('/experiments/11111111-1111-4111-8111-111111111111', service)

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the experiment: it does not exist or is not visible to you.')
  })
})
