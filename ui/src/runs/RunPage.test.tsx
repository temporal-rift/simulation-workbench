import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { FakeService } from '../test/fakeService'
import { IDS, caseDetail, run } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const path = `/runs/${IDS.run}`
let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

function counts() {
  const table = screen.getByRole('table', { name: 'Case counts' })
  const values = within(table).getAllByRole('cell').map((cell) => Number(cell.textContent))
  const [requested, pending, running, succeeded, failed, cancelled] = values
  return { requested, pending, running, succeeded, failed, cancelled }
}

describe('a run in progress', () => {
  it('shows its state and counts and offers cancel but not resume', async () => {
    await renderApp(path, service)

    expect(await screen.findByText('State: Running')).toBeInTheDocument()
    expect(counts()).toEqual({ requested: 4, pending: 1, running: 1, succeeded: 2, failed: 0, cancelled: 0 })
    expect(screen.getByRole('button', { name: 'Cancel run' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume run' })).not.toBeInTheDocument()
  })

  it('reads the cases page but not the failed list while nothing has failed', async () => {
    await renderApp(path, service)

    await screen.findByRole('table', { name: 'Cases of this run' })
    expect(service.requests.filter((request) => request.query.get('state') === 'FAILED')).toHaveLength(0)
  })

  it('marks the data partial and never presents unfinished cases as results', async () => {
    service.cases.set(IDS.run, [
      caseDetail({ caseId: '00000000-0000-4000-8000-00000000000a', state: 'SUCCEEDED', attempts: [], result: {
        endReason: 'WIN_CONDITION_MET',
        winners: [{ seatIndex: 0, faction: 'ERASERS', winType: 'SCORE_THRESHOLD' }],
        finalScores: [{ seatIndex: 0, faction: 'ERASERS', score: 12 }],
        eras: 2,
        rounds: 6,
        decisions: 40,
        semanticDigest: 'a'.repeat(64),
      } }),
      caseDetail({ caseId: '00000000-0000-4000-8000-00000000000b', state: 'PENDING', attempts: [] }),
      caseDetail({ caseId: '00000000-0000-4000-8000-00000000000c', state: 'RUNNING', attempts: [] }),
      caseDetail({ caseId: '00000000-0000-4000-8000-00000000000d', state: 'CANCELLED', attempts: [] }),
    ])
    await renderApp(path, service)

    expect(await screen.findByText(/Partial: 2 of 4 cases have succeeded/)).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Cases of this run' })
    const rows = within(table).getAllByRole('row').slice(1)
    const results = rows.map((row) => [within(row).getAllByRole('cell')[1]!.textContent, within(row).getAllByRole('cell')[2]!.textContent])
    expect(results).toEqual([
      ['Succeeded', 'WIN_CONDITION_MET'],
      ['Pending', 'No result'],
      ['Running', 'No result'],
      ['Cancelled', 'No result'],
    ])
  })

  it('shows cancel unavailable, naming the scope, to a read-only designer', async () => {
    await renderApp(path, service, { scopes: [READ_SCOPE] })

    const cancel = await screen.findByRole('button', { name: 'Cancel run' })
    expect(cancel).toHaveAttribute('aria-disabled', 'true')
    expect(cancel).toHaveAccessibleDescription(`Unavailable: requires the ${WRITE_SCOPE} scope.`)
  })

  it('cancels with one idempotency key and shows the cancelling state', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Cancel run' }))

    expect(await screen.findByText('State: Cancelling')).toBeInTheDocument()
    expect(service.count('POST', /\/cancel$/)).toBe(1)
    expect(screen.queryByRole('button', { name: 'Cancel run' })).not.toBeInTheDocument()
  })

  it('refreshes while the run is open and announces its change of state', async () => {
    await renderApp(path, service)
    await screen.findByText('State: Running')

    service.runs.set(IDS.run, run({ state: 'COMPLETED', counts: { requested: 4, pending: 0, running: 0, succeeded: 4, failed: 0, cancelled: 0 }, finishedAt: '2026-10-11T09:10:00Z' }))

    await waitFor(() => expect(screen.getByText('State: Completed')).toBeInTheDocument(), { timeout: 6000 })
    await waitFor(() => expect(screen.getAllByRole('status').some((region) => region.textContent === 'Run is now completed.')).toBe(true))
    const requestsAfterSettling = service.count('GET', new RegExp(`/runs/${IDS.run}$`))
    await new Promise((resolve) => setTimeout(resolve, 3500))
    expect(service.count('GET', new RegExp(`/runs/${IDS.run}$`))).toBe(requestsAfterSettling)
  }, 20000)
})

describe('recovering a run', () => {
  const interrupted = () =>
    run({ state: 'INTERRUPTED', counts: { requested: 4, pending: 2, running: 0, succeeded: 2, failed: 0, cancelled: 0 } })

  it('resumes an interrupted run without presenting retained successes as pending', async () => {
    const user = userEvent.setup()
    service.runs.set(IDS.run, interrupted())
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Resume run' }))

    expect(await screen.findByText('State: Running')).toBeInTheDocument()
    expect(counts()).toMatchObject({ pending: 2, succeeded: 2 })
    expect(screen.queryByRole('button', { name: 'Resume run' })).not.toBeInTheDocument()
  })

  it('explains a refused resume with the current state and stays usable', async () => {
    const user = userEvent.setup()
    service.runs.set(IDS.run, interrupted())
    await renderApp(path, service)
    const resume = await screen.findByRole('button', { name: 'Resume run' })
    service.runs.set(IDS.run, run({ state: 'COMPLETED', counts: { requested: 4, pending: 0, running: 0, succeeded: 4, failed: 0, cancelled: 0 } }))

    await user.click(resume)

    const refusal = await screen.findByText(/Could not resume the run: it is completed\./)
    expect(refusal).toHaveTextContent('A run can be resumed only while it is interrupted.')
    expect(refusal).toHaveAttribute('role', 'alert')
    expect(screen.getByText('State: Completed')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Experiment' })).toBeInTheDocument()
  })
})

describe('a run with failures', () => {
  beforeEach(() => {
    service.runs.set(
      IDS.run,
      run({ state: 'FAILED', counts: { requested: 4, pending: 0, running: 0, succeeded: 2, failed: 2, cancelled: 0 }, failure: { code: 'CONFIGURATION_DRIFT', message: 'The game service no longer matches the manifest.' } }),
    )
  })

  it('lists each failure with its code and message and links to the failed case', async () => {
    service.addFailedCase({ caseId: IDS.caseA })
    service.addFailedCase({
      caseId: IDS.caseB,
      seed: '43',
      attempts: [{ attemptId: IDS.other, ordinal: 1, state: 'FAILED', gameId: null, checkpointArtifact: null, failure: { code: 'CASE_TIMEOUT', message: 'The case exceeded its wall-time limit.' } }],
    })
    await renderApp(path, service)

    const section = await screen.findByRole('region', { name: 'Failed cases' })
    expect(await within(section).findByText('CONTRACT_MISMATCH')).toBeInTheDocument()
    expect(within(section).getByText(/The game service answered with an unexpected contract\./)).toBeInTheDocument()
    expect(await within(section).findByText('CASE_TIMEOUT')).toBeInTheDocument()
    const link = within(section).getByRole('link', { name: /seed 43/ })
    expect(link).toHaveAttribute('href', `/runs/${IDS.run}/cases/${IDS.caseB}`)
    expect(screen.getByText('CONFIGURATION_DRIFT')).toBeInTheDocument()
  })

  it('reads the failed list once and each failed case once', async () => {
    service.addFailedCase({ caseId: IDS.caseA })
    service.addFailedCase({ caseId: IDS.caseB, seed: '43' })
    await renderApp(path, service)

    const section = await screen.findByRole('region', { name: 'Failed cases' })
    await within(section).findAllByText('CONTRACT_MISMATCH')
    expect(service.requests.filter((request) => request.query.get('state') === 'FAILED')).toHaveLength(1)
    expect(service.count('GET', new RegExp(`/cases/${IDS.caseA}$`))).toBe(1)
    expect(service.count('GET', new RegExp(`/cases/${IDS.caseB}$`))).toBe(1)
  })

  it('opens the failed case and says it has no result', async () => {
    const user = userEvent.setup()
    service.addFailedCase({ caseId: IDS.caseA })
    await renderApp(path, service)

    await user.click(await screen.findByRole('link', { name: /Case baseline, seed 42/ }))

    expect(await screen.findByText(/This case has no result: it is failed/)).toBeInTheDocument()
    expect(screen.getByText('CONTRACT_MISMATCH')).toBeInTheDocument()
  })
})

describe('an unreachable service', () => {
  it('keeps the last data marked stale and recovers on retry', async () => {
    const user = userEvent.setup()
    const { queryClient } = await renderApp(path, service)
    await screen.findByText('State: Running')
    await screen.findByText(/Verdict:/)

    service.unreachable = true
    await queryClient.refetchQueries({ queryKey: ['run', IDS.run] })

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the run: the service could not be reached.')
    expect(screen.getByText('State: Running')).toBeInTheDocument()
    expect(screen.getByText(/\(stale/)).toBeInTheDocument()

    service.unreachable = false
    await user.click(screen.getAllByRole('button', { name: 'Retry' })[0]!)

    await waitFor(() => expect(screen.queryByText(/\(stale/)).not.toBeInTheDocument())
  })
})

describe('the runs list', () => {
  it('filters by state and experiment and marks partial runs', async () => {
    const user = userEvent.setup()
    service.runs.set('20000000-0000-4000-8000-000000000001', run({ runId: '20000000-0000-4000-8000-000000000001', state: 'COMPLETED', counts: { requested: 4, pending: 0, running: 0, succeeded: 4, failed: 0, cancelled: 0 } }))
    await renderApp('/runs', service)

    const table = await screen.findByRole('table', { name: 'Runs, newest first' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(within(table).getByText('(partial)')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'State' }), 'COMPLETED')

    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Runs, newest first' })).getAllByRole('row')).toHaveLength(2))
    expect(within(screen.getByRole('table')).queryByText('(partial)')).not.toBeInTheDocument()
  })
})
