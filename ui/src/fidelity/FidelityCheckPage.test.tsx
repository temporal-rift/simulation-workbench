import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FakeService } from '../test/fakeService'
import { FIDELITY_IDS, check, fidelityCase, serveFidelity } from '../test/fidelityFixtures'
import { renderApp } from '../test/renderApp'

const path = `/fidelity/${FIDELITY_IDS.check}`
let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

describe('a passed check', () => {
  const passed = check({
    state: 'PASSED',
    verdict: 'PASSED',
    counts: { total: 330, matched: 330, diverged: 0, unreproducible: 0, pending: 0 },
    finishedAt: '2026-01-01T01:00:00Z',
  })

  it('shows its verdict, counts, environments and variants', async () => {
    serveFidelity(service, { checks: [passed] })
    await renderApp(path, service)

    expect(await screen.findByRole('heading', { name: 'Fidelity check b1d2c3e4' })).toBeInTheDocument()
    expect(screen.getByText('Every case matched: the simulator played the same game as the real lane.')).toBeInTheDocument()
    const counts = within(screen.getByRole('table', { name: 'Case counts' })).getAllByRole('cell').map((cell) => Number(cell.textContent))
    expect(counts).toEqual([330, 330, 0, 0, 0])
    expect(screen.getByRole('table', { name: 'Simulator' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Real lane' })).toBeInTheDocument()
    const variants = screen.getByRole('table', { name: 'Variants played' })
    expect(within(variants).getByText('baseline')).toBeInTheDocument()
    expect(within(variants).getByText('= 16', { exact: false })).toBeInTheDocument()
  })

  it('lists its cases', async () => {
    serveFidelity(service, { checks: [passed], cases: [fidelityCase(), fidelityCase({ caseKey: FIDELITY_IDS.other, policyId: 'random', seatRotation: 1 })] })
    await renderApp(path, service)

    const table = await screen.findByRole('table', { name: 'Cases of this check' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0]!).getByText('ACTIVISTS + ERASERS + PROPHETS')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('random')).toBeInTheDocument()
  })
})

describe('a failed check', () => {
  const failed = check({
    state: 'FAILED',
    verdict: 'FAILED',
    counts: { total: 330, matched: 328, diverged: 1, unreproducible: 1, pending: 0 },
    finishedAt: '2026-01-01T01:00:00Z',
  })
  const cases = [
    fidelityCase(),
    fidelityCase({
      caseKey: FIDELITY_IDS.other,
      variantLabel: 'high',
      result: 'DIVERGED',
      firstDivergence: { step: 12, kind: 'DECISION', expected: {}, actual: {} },
    }),
    fidelityCase({
      caseKey: '1d2e3f40-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
      variantLabel: 'low',
      result: 'UNREPRODUCIBLE',
      failure: { code: 'RUNNER_TIMEOUT', message: 'The case ended FAILED on the simulator' },
    }),
  ]

  it('says no difference is tolerated', async () => {
    serveFidelity(service, { checks: [failed], cases })
    await renderApp(path, service)

    expect(await screen.findByText(/At least one case differed or could not be reproduced/)).toBeInTheDocument()
  })

  it('shows where a diverged case differed and why another could not be reproduced', async () => {
    serveFidelity(service, { checks: [failed], cases })
    await renderApp(path, service)

    const table = await screen.findByRole('table', { name: 'Cases of this check' })
    expect(within(table).getByText('Differs at step 12: DECISION')).toBeInTheDocument()
    expect(within(table).getByText('The case ended FAILED on the simulator (RUNNER_TIMEOUT)')).toBeInTheDocument()
  })

  it('filters the cases by result through the service', async () => {
    serveFidelity(service, { checks: [failed], cases })
    await renderApp(path, service)
    const user = userEvent.setup()

    await user.selectOptions(await screen.findByLabelText('Result'), 'DIVERGED')

    await waitFor(() => expect(service.requests.some((request) => request.query.get('result') === 'DIVERGED')).toBe(true))
    const table = await screen.findByRole('table', { name: 'Cases of this check' })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2))
  })
})

describe('the report', () => {
  it('is downloaded as markdown named for the seed and revision, with the cases that did not match', async () => {
    const saved: { blob: Blob; name: string }[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      saved.push({ blob: blob as Blob, name: '' })
      return 'blob:report'
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved[saved.length - 1]!.name = this.download
    })
    const failed = check({ state: 'FAILED', verdict: 'FAILED', counts: { total: 330, matched: 329, diverged: 1, unreproducible: 0, pending: 0 } })
    serveFidelity(service, {
      checks: [failed],
      cases: [fidelityCase({ result: 'DIVERGED', firstDivergence: { step: 12, kind: 'DECISION', expected: {}, actual: {} } })],
    })
    await renderApp(path, service)

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Download report' }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]!.name).toBe('report-42-abcdef1.md')
    const text = await saved[0]!.blob.text()
    expect(text).toContain('# Simulator fidelity report, seed 42')
    expect(text).toContain('- Verdict: FAILED')
    expect(text).toContain('differs at step 12: DECISION')
  })
})

describe('a check that could not complete', () => {
  it('shows its failure', async () => {
    serveFidelity(service, {
      checks: [check({ state: 'FAILED', verdict: 'FAILED', counts: { total: 0, matched: 0, diverged: 0, unreproducible: 0, pending: 0 }, failure: { code: 'RUN_FAILED', message: 'lane crashed' } })],
      cases: [],
    })
    await renderApp(path, service)

    expect(await screen.findByText(/The check could not complete: lane crashed/)).toBeInTheDocument()
    expect(await screen.findByText('The check has no cases yet.')).toBeInTheDocument()
  })
})

describe('an unknown check', () => {
  it('says it does not exist', async () => {
    serveFidelity(service, { checks: [] })
    await renderApp(path, service)

    expect(await screen.findByText(/it does not exist or is not visible to you/)).toBeInTheDocument()
  })
})
