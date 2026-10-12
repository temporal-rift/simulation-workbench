import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakeService } from '../test/fakeService'
import { IDS, REPORT_CSV, report, run } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const path = `/runs/${IDS.run}/report`
let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('a complete report', () => {
  it('shows each cohort with its denominators, blocks and intervals', async () => {
    await renderApp(path, service)

    expect(await screen.findByRole('heading', { name: /baseline · random@v1 · 3 players · all faction sets · all seats/ })).toBeInTheDocument()
    expect(screen.getByText(/4 eligible games in 2 independent blocks/)).toBeInTheDocument()
    const table = screen.getByRole('table', { name: /Metrics of baseline/ })
    const win = within(table).getByRole('row', { name: /faction_win_rate/ })
    expect(within(win).getByText('0.25 to 0.75 (95%)')).toBeInTheDocument()
    expect(within(win).getByText('2 / 4')).toBeInTheDocument()
    expect(screen.queryByText(/Partial report/)).not.toBeInTheDocument()
  })

  it('labels a metric with fewer than two blocks as an insufficient sample, without an interval', async () => {
    await renderApp(path, service)

    const table = await screen.findByRole('table', { name: /Metrics of baseline/ })
    const score = within(table).getByRole('row', { name: /score/ })
    expect(within(score).getByText('No interval')).toBeInTheDocument()
    expect(within(score).getByText(/Insufficient sample/)).toBeInTheDocument()
  })

  it('shows no data and the unknown count rather than a value', async () => {
    await renderApp(path, service)

    const table = await screen.findByRole('table', { name: /Metrics of baseline/ })
    const playable = within(table).getByRole('row', { name: /card_playable_rate/ })
    expect(within(playable).getByText(/No data/)).toBeInTheDocument()
    expect(within(playable).getAllByRole('cell').at(-1)).toHaveTextContent('3')
  })
})

describe('an incomplete report', () => {
  it('is marked partial with what has not succeeded', async () => {
    service.runs.set(IDS.run, run({ state: 'COMPLETED' }))
    service.reports.set(
      IDS.run,
      report({ complete: false, caseCounts: { requested: 4, pending: 0, running: 0, succeeded: 2, failed: 1, cancelled: 1 } }),
    )
    await renderApp(path, service)

    expect(await screen.findByText(/Partial report: 2 of 4 requested cases have succeeded \(0 pending, 0 running, 1 failed, 1 cancelled\)/)).toBeInTheDocument()
  })
})

describe('exports', () => {
  function captureDownloads() {
    const saved: { blob: Blob; name: string }[] = []
    let name = ''
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      saved.push({ blob: blob as Blob, name })
      return 'blob:report'
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved[saved.length - 1]!.name = this.download
      name = this.download
    })
    return saved
  }

  it('downloads the CSV exactly as the service returned it', async () => {
    const user = userEvent.setup()
    const saved = captureDownloads()
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Export CSV' }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]!.name).toBe(`report-${IDS.run}.csv`)
    expect(Array.from(new Uint8Array(await saved[0]!.blob.arrayBuffer()))).toEqual(Array.from(new TextEncoder().encode(REPORT_CSV)))
    const request = service.requests.filter((candidate) => candidate.path.endsWith('/report')).at(-1)!
    expect(request.headers.get('Accept')).toBe('text/csv')
  })

  it('downloads the JSON bytes of the service, not a re-serialisation', async () => {
    const user = userEvent.setup()
    const saved = captureDownloads()
    // Valid JSON whose layout a parse-and-stringify round trip would change.
    const raw = JSON.stringify(report(), null, 4) + '\n'
    service.override('GET', /\/report$/, () => new Response(raw, { headers: { 'Content-Type': 'application/json' } }))
    await renderApp(path, service)

    await user.click(await screen.findByRole('button', { name: 'Export JSON' }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(await saved[0]!.blob.text()).toBe(raw)
  })

  it('says so when the export cannot be fetched', async () => {
    const user = userEvent.setup()
    captureDownloads()
    await renderApp(path, service)
    await screen.findByRole('button', { name: 'Export CSV' })

    service.unreachable = true
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not export/)
  })
})
