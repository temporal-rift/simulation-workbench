import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { FakeService, problem } from '../test/fakeService'
import { manifest } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { loadDraft, manifestToDraft, saveDraft } from './draft'

const EXPERIMENTS = /^\/api\/v1\/experiments$/
const PREVIEWS = /^\/api\/v1\/experiment-previews$/

let service: FakeService

beforeEach(() => {
  service = new FakeService()
  saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
})

async function previewTheDraft(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Preview matrix' }))
  await screen.findByRole('heading', { name: 'Matrix preview' })
}

describe('configuring an experiment', () => {
  it('previews the matrix the service returns and freezes nothing', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)

    await previewTheDraft(user)

    expect(screen.getByText(/in total\. Nothing is frozen yet/)).toHaveTextContent('4 cases in total')
    expect(screen.getByText('7'.repeat(64))).toBeInTheDocument()
    const breakdown = screen.getByRole('table', { name: 'Cases per variant and player count' })
    expect(within(breakdown).getByRole('row', { name: /baseline 3 2/ })).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: /Case coordinates/ })).getByText('42')).toBeInTheDocument()
    expect(service.count('POST', EXPERIMENTS)).toBe(0)
  })

  it('creates one experiment when the freeze is confirmed twice', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    expect(screen.getByText(/exactly 4 cases with the digest/)).toBeInTheDocument()
    await user.dblClick(screen.getByRole('button', { name: 'Confirm and freeze' }))

    await screen.findByText('Frozen and read-only. To change the configuration, start a new draft from it; this experiment stays as it is.')
    const freezes = service.requests.filter((request) => request.method === 'POST' && EXPERIMENTS.test(request.path))
    expect(freezes.length).toBeGreaterThan(0)
    expect(new Set(freezes.map((request) => request.headers.get('Idempotency-Key'))).size).toBe(1)
    expect(service.experiments.size).toBe(2)
    expect(loadDraft()).toBeNull()
  })

  it('retries a freeze whose answer was lost under the same key, so it cannot freeze twice', async () => {
    const user = userEvent.setup()
    service.failOnce('POST', EXPERIMENTS, () => problem(503, 'UNAVAILABLE', 'try later'))
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    await user.click(screen.getByRole('button', { name: 'Confirm and freeze' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Freeze this experiment…' }))
    await user.click(screen.getByRole('button', { name: 'Confirm and freeze' }))

    await screen.findByRole('heading', { name: 'threshold experiment' })
    const keys = service.requests.filter((request) => request.method === 'POST' && EXPERIMENTS.test(request.path)).map((request) => request.headers.get('Idempotency-Key'))
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('shows freeze and preview unavailable, naming the missing scope, to a read-only designer', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service, { scopes: [READ_SCOPE] })

    const preview = await screen.findByRole('button', { name: 'Preview matrix' })
    const freeze = screen.getByRole('button', { name: 'Freeze this experiment…' })
    expect(preview).toHaveAttribute('aria-disabled', 'true')
    expect(freeze).toHaveAttribute('aria-disabled', 'true')
    expect(preview).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)
    expect(freeze).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)

    await user.click(preview)
    expect(service.count('POST', PREVIEWS)).toBe(0)
  })

  it('places a refusal on the field it names and in the summary', async () => {
    const user = userEvent.setup()
    service.override('POST', PREVIEWS, () => problem(400, 'INVALID_EXPERIMENT', 'seeds must be unique: 42', 'seeds[1]'))
    await renderApp('/experiments/new', service)

    await user.click(await screen.findByRole('button', { name: 'Preview matrix' }))

    const seeds = await screen.findByRole('textbox', { name: 'Seeds' })
    expect(seeds).toHaveAttribute('aria-invalid', 'true')
    expect(seeds).toHaveAccessibleDescription(/seeds must be unique: 42/)
    expect(screen.getByRole('alert')).toHaveTextContent('Could not preview the matrix: the experiment configuration was refused.')
  })

  it('keeps the unsent draft across a new session and says so', async () => {
    const user = userEvent.setup()
    saveDraft({ draft: { ...manifestToDraft(manifest()), name: '' }, basedOn: null })
    const first = await renderApp('/experiments/new', service)
    await user.type(await screen.findByRole('textbox', { name: 'Experiment name' }), 'half written')
    first.unmount()

    await renderApp('/experiments/new', service)

    expect(await screen.findByRole('textbox', { name: 'Experiment name' })).toHaveValue('half written')
    expect(screen.getByText('Your unsent draft was restored.')).toBeInTheDocument()
  })

  it('does not let an outdated preview be frozen', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    await previewTheDraft(user)

    await user.type(screen.getByRole('textbox', { name: 'Experiment name' }), ' v2')

    expect(screen.getByText('The draft changed after this preview. Preview again before freezing.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Freeze this experiment…' })).toBeDisabled()
  })

  it('keeps invalid policy parameters from being sent', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments/new', service)
    const parameters = await screen.findByRole('textbox', { name: 'Parameters (JSON object)' })
    await user.clear(parameters)
    await user.type(parameters, 'not json')

    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))

    expect(parameters).toHaveAttribute('aria-invalid', 'true')
    expect(await screen.findByText('Fix the highlighted fields before previewing the matrix.')).toBeInTheDocument()
    await waitFor(() => expect(service.count('POST', PREVIEWS)).toBe(0))
  })
})
