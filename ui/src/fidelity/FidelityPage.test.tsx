import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from '../auth/scopes'
import { FakeService, json, problem } from '../test/fakeService'
import { FIDELITY_IDS, check, gate, serveFidelity } from '../test/fidelityFixtures'
import { renderApp } from '../test/renderApp'

let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

describe('the simulator gate', () => {
  it('says the simulator is not established and names its source revisions', async () => {
    serveFidelity(service, { gate: gate(false), checks: [] })
    await renderApp('/fidelity', service)

    expect(await screen.findByText(/Not established/)).toBeInTheDocument()
    const revisions = screen.getByRole('table', { name: 'Source revisions the simulator embeds' })
    expect(within(revisions).getByText('abcdef1')).toBeInTheDocument()
    expect(within(revisions).getByText('abcdef2')).toBeInTheDocument()
    expect(within(revisions).getByText('abcdef3')).toBeInTheDocument()
  })

  it('links the passing record when the simulator is established', async () => {
    serveFidelity(service, { gate: gate(true, check({ state: 'PASSED' })), checks: [] })
    await renderApp('/fidelity', service)

    expect(await screen.findByText(/Established: a passing fidelity check exists/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /check b1d2c3e4/ })).toHaveAttribute('href', `/fidelity/${FIDELITY_IDS.check}`)
  })

  it('explains a simulator that is not enabled', async () => {
    serveFidelity(service)
    service.override('GET', /^\/api\/v1\/simulator\/fidelity$/, () => problem(409, 'SIMULATOR_NOT_AVAILABLE', 'The simulator is not enabled'))
    await renderApp('/fidelity', service)

    expect(await screen.findByText(/the simulator is not enabled or no real lane matches its builds/)).toBeInTheDocument()
  })
})

describe('the checks', () => {
  it('lists them newest first with their state and matched cases', async () => {
    serveFidelity(service, {
      checks: [
        check({ fidelityCheckId: FIDELITY_IDS.check, state: 'RUNNING' }),
        check({ fidelityCheckId: FIDELITY_IDS.other, state: 'PASSED', counts: { total: 330, matched: 330, diverged: 0, unreproducible: 0, pending: 0 } }),
      ],
    })
    await renderApp('/fidelity', service)

    const table = await screen.findByRole('table', { name: 'Fidelity checks, newest first' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]!).getByText('Running')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('Passed')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('330 of 330')).toBeInTheDocument()
  })

  it('says so when none has been started', async () => {
    serveFidelity(service, { checks: [] })
    await renderApp('/fidelity', service)

    expect(await screen.findByText('No fidelity check has been started.')).toBeInTheDocument()
  })
})

describe('starting a check', () => {
  it('sends the seed with an idempotency key and opens the check', async () => {
    serveFidelity(service)
    service.override('POST', /^\/api\/v1\/fidelity-checks$/, () => json(check({ state: 'QUEUED' }), 202))
    await renderApp('/fidelity', service)
    const user = userEvent.setup()

    await user.clear(await screen.findByLabelText('Seed'))
    await user.type(screen.getByLabelText('Seed'), '7')
    await user.click(screen.getByRole('button', { name: 'Start a check' }))

    await waitFor(() => expect(service.count('POST', /fidelity-checks$/)).toBe(1))
    const request = service.requests.find((item) => item.method === 'POST')!
    expect(request.body).toEqual({ seed: '7' })
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(await screen.findByRole('heading', { name: /Fidelity check b1d2c3e4/ })).toBeInTheDocument()
  })

  it('refuses a seed that is not an unsigned 64-bit whole number before sending anything', async () => {
    serveFidelity(service)
    await renderApp('/fidelity', service)
    const user = userEvent.setup()

    await user.clear(await screen.findByLabelText('Seed'))
    await user.type(screen.getByLabelText('Seed'), '-1')

    expect(screen.getByText(/The seed must be a whole number/)).toBeInTheDocument()
    expect(screen.getByLabelText('Seed')).toHaveAttribute('aria-invalid', 'true')
    await user.click(screen.getByRole('button', { name: 'Start a check' }))
    expect(service.count('POST', /fidelity-checks$/)).toBe(0)
  })

  it('names the scope it needs when the session cannot write', async () => {
    serveFidelity(service)
    await renderApp('/fidelity', service, { scopes: [READ_SCOPE] })

    expect(await screen.findByText('Unavailable: requires the simulation:write scope.')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Start a check' }))
    expect(service.count('POST', /fidelity-checks$/)).toBe(0)
  })

  it('explains that another check is running and repeats the same key on retry', async () => {
    serveFidelity(service)
    service.override('POST', /^\/api\/v1\/fidelity-checks$/, () => json(check({ state: 'QUEUED' }), 202))
    service.failOnce('POST', /^\/api\/v1\/fidelity-checks$/, () => problem(409, 'FIDELITY_CHECK_ACTIVE', 'Another fidelity check is queued or running'))
    await renderApp('/fidelity', service)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Start a check' }))
    expect(await screen.findByText(/another fidelity check is queued or running/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(service.count('POST', /fidelity-checks$/)).toBe(2))
    const keys = service.requests.filter((item) => item.method === 'POST').map((item) => item.headers.get('Idempotency-Key'))
    expect(keys[0]).toBe(keys[1])
  })
})
