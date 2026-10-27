import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from './auth/scopes'
import { manifestToDraft, saveDraft } from './experiments/draft'
import { FakeService, problem } from './test/fakeService'
import { expectNoViolations } from './test/axe'
import { COMPARISON_ID, IDS, comparison, manifest, report, run } from './test/fixtures'
import { check as fidelityCheck, fidelityCase, gate, serveFidelity } from './test/fidelityFixtures'
import { renderApp } from './test/renderApp'

let service: FakeService

beforeEach(() => {
  service = new FakeService()
  service.addFailedCase({ caseId: IDS.caseA })
  service.runs.set(
    IDS.run,
    run({ state: 'INTERRUPTED', counts: { requested: 4, pending: 1, running: 0, succeeded: 2, failed: 1, cancelled: 0 }, failure: { code: 'CONFIGURATION_DRIFT', message: 'Drifted.' } }),
  )
})

describe('accessibility', () => {
  it.each([
    ['the experiments list', '/experiments', 'Frozen experiments, newest first'],
    ['a frozen experiment', `/experiments/${IDS.experiment}`, 'Variants'],
    ['the runs list', '/runs', 'Runs, newest first'],
    ['a run with failures', `/runs/${IDS.run}`, 'Resume run'],
    ['a failed case', `/runs/${IDS.run}/cases/${IDS.caseA}`, 'Attempts'],
  ])('%s has no axe violations', async (_name, path, ready) => {
    const { container } = await renderApp(path, service)
    await screen.findAllByText(ready, { exact: false })
    await expectNoViolations(container)
  })

  it('the fidelity page and a failed check have no axe violations', async () => {
    const failed = fidelityCheck({ state: 'FAILED', verdict: 'FAILED' })
    serveFidelity(service, {
      gate: gate(false),
      checks: [failed],
      cases: [
        fidelityCase({ result: 'DIVERGED', firstDivergence: { step: 3, kind: 'DECISION', expected: {}, actual: {} } }),
        fidelityCase({ caseKey: IDS.caseB, result: 'UNREPRODUCIBLE', failure: { code: 'RUNNER_TIMEOUT', message: 'The case ended FAILED on the simulator' } }),
      ],
    })
    const page = await renderApp('/fidelity', service)
    await page.findByRole('table', { name: 'Fidelity checks, newest first' })
    await expectNoViolations(page.container)
    page.unmount()

    const detail = await renderApp(`/fidelity/${failed.fidelityCheckId}`, service)
    await detail.findByRole('table', { name: 'Cases of this check' })
    await expectNoViolations(detail.container)
  })

  it('the experiment form, previewed and with a problem on a field, has no axe violations', async () => {
    const user = userEvent.setup()
    saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
    const { container } = await renderApp('/experiments/new', service)
    await user.click(await screen.findByRole('button', { name: 'Preview matrix' }))
    await screen.findByRole('heading', { name: 'Matrix preview' })
    await expectNoViolations(container)

    service.override('POST', /experiment-previews$/, () => problem(400, 'INVALID_EXPERIMENT', 'seeds must be unique: 42', 'seeds[1]'))
    await user.click(screen.getByRole('button', { name: 'Preview matrix' }))
    await screen.findByRole('alert')
    await expectNoViolations(container)
  })

  it('a read-only session has no axe violations where actions are unavailable', async () => {
    const { container } = await renderApp(`/runs/${IDS.run}`, service, { scopes: [READ_SCOPE] })
    await screen.findByRole('button', { name: 'Resume run' })
    await expectNoViolations(container)
  })
})

describe('keyboard operation', () => {
  it('starts with a skip link and reaches the main content', async () => {
    const user = userEvent.setup()
    await renderApp('/experiments', service)
    await screen.findByRole('table')

    await user.tab()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus()
  })

  it('previews from the keyboard and reaches the reason an action is unavailable', async () => {
    const user = userEvent.setup()
    saveDraft({ draft: manifestToDraft(manifest()), basedOn: null })
    await renderApp('/experiments/new', service)

    screen.getByRole('button', { name: 'Preview matrix' }).focus()
    await user.keyboard('{Enter}')
    await screen.findByRole('heading', { name: 'Matrix preview' })

    const freeze = screen.getByRole('button', { name: 'Freeze this experiment…' })
    freeze.focus()
    expect(freeze).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: 'Confirm and freeze' })).toBeInTheDocument()
    await user.keyboard('{Tab}')
    expect(screen.getByRole('button', { name: 'Confirm and freeze' })).toHaveFocus()
  })
})

describe('accessibility of reports, comparisons, replay and reproduction', () => {
  beforeEach(() => {
    service.comparisons.set(COMPARISON_ID, comparison({ complete: false, unmatchedCases: 1 }))
    service.reports.set(IDS.run, report({ complete: false, caseCounts: { requested: 4, pending: 1, running: 0, succeeded: 3, failed: 0, cancelled: 0 } }))
  })

  it.each([
    ['a partial report', `/runs/${IDS.run}/report`, 'Export CSV'],
    ['the comparisons list', '/comparisons', 'Comparisons, newest first'],
    ['a partial comparison', `/comparisons/${COMPARISON_ID}`, 'Declared differences'],
  ])('%s has no axe violations', async (_name, path, ready) => {
    const { container } = await renderApp(path, service)
    await screen.findAllByText(ready, { exact: false })
    await expectNoViolations(container)
  })

  it('a player replay, an observer replay, an observer denial and every reproduction outcome have no axe violations', async () => {
    const user = userEvent.setup()
    const { container } = await renderApp(`/runs/${IDS.run}/cases/${IDS.caseA}`, service, { scopes: [READ_SCOPE, 'simulation:write'] })

    await user.click(await screen.findByRole('button', { name: 'View as player' }))
    await screen.findByRole('heading', { name: 'Player view: seat 0' })
    await expectNoViolations(container)

    await user.click(screen.getByRole('button', { name: 'View as observer…' }))
    await screen.findByText(/Observer access denied/)
    await expectNoViolations(container)

    service.reproductionScript = ['DIVERGED']
    service.reproductionDivergence = { step: 12, kind: 'DECISION', expected: { candidate: 'a' }, actual: { candidate: 'b' } }
    await user.click(screen.getByRole('button', { name: 'Reproduce this case' }))
    await screen.findByText(/The first difference is at step 12/)
    await expectNoViolations(container)
  })

  it('an observer replay has no axe violations', async () => {
    const user = userEvent.setup()
    const { container } = await renderApp(`/runs/${IDS.run}/cases/${IDS.caseA}`, service)

    await user.click(await screen.findByRole('button', { name: 'View as observer…' }))
    await screen.findByRole('heading', { name: 'Observer evidence' })
    await expectNoViolations(container)
  })
})

describe('keyboard operation of the replay inspector', () => {
  it('chooses a seat, opens its replay and pages through it without a pointer', async () => {
    const user = userEvent.setup()
    await renderApp(`/runs/${IDS.run}/cases/${IDS.caseA}`, service)

    const seat = await screen.findByLabelText('Seat')
    seat.focus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'View as player' })).toHaveFocus()
    await user.keyboard('{Enter}')
    await screen.findByRole('heading', { name: 'Player view: seat 0' })

    const later = screen.getByRole('button', { name: 'Later steps' })
    later.focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { name: /^Step 3 / })).toBeInTheDocument()
  })

  it('reaches the reason an unavailable observer view is denied from the keyboard', async () => {
    const user = userEvent.setup()
    await renderApp(`/runs/${IDS.run}/cases/${IDS.caseA}`, service, { scopes: [READ_SCOPE] })

    const observer = await screen.findByRole('button', { name: 'View as observer…' })
    observer.focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent('Observer access denied.')
  })
})
