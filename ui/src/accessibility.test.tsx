import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE } from './auth/scopes'
import { manifestToDraft, saveDraft } from './experiments/draft'
import { FakeService, problem } from './test/fakeService'
import { expectNoViolations } from './test/axe'
import { IDS, manifest, run } from './test/fixtures'
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
