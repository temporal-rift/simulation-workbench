import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { FakeService, problem } from '../test/fakeService'
import { expectNoViolations } from '../test/axe'
import { IDS, evaluation } from '../test/fixtures'
import { renderApp } from '../test/renderApp'
import { BOT_RESULTS_STATEMENT } from './evaluationView'

const path = `/runs/${IDS.run}`
const EVALUATION = /\/evaluation$/
let service: FakeService

beforeEach(() => {
  service = new FakeService()
})

async function panel() {
  const heading = await screen.findByRole('heading', { name: 'Evaluation' })
  return within(heading.closest('section')!)
}

function evaluationReads() {
  return service.requests.filter((request) => request.method === 'GET' && EVALUATION.test(request.path))
}

describe('the evaluation panel', () => {
  it('states that bot results say nothing about balance among human players, before and after loading', async () => {
    await renderApp(path, service)

    const evaluationPanel = await panel()
    expect(evaluationPanel.getByText(BOT_RESULTS_STATEMENT)).toBeInTheDocument()
    await evaluationPanel.findByText(/Verdict:/)
    expect(evaluationPanel.getByText(BOT_RESULTS_STATEMENT)).toBeInTheDocument()
  })

  it('shows the verdict, the score and every evaluated line of the first variant and the latest criterion version', async () => {
    await renderApp(path, service)

    const evaluationPanel = await panel()
    expect(await evaluationPanel.findByText('Verdict: Rejected: a gate failed')).toBeInTheDocument()
    expect(evaluationPanel.getByText('0.02963')).toBeInTheDocument()
    const table = evaluationPanel.getByRole('table', { name: 'Evaluated lines' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    const [first] = rows
    const cells = within(first!).getAllByRole('cell').map((cell) => cell.textContent)
    expect(within(first!).getByRole('rowheader')).toHaveTextContent('G-05')
    expect(cells).toEqual([
      'Gate',
      'faction-greedy-v1 · 3 players · all faction sets · ERASERS',
      '2.4',
      '2.2 to 2.6 (95%)',
      '0.5 to 2',
      '0.266667',
      'Fails',
    ])
    expect(evaluationReads()[0]!.query.get('variantLabel')).toBe('baseline')
    expect(evaluationReads()[0]!.query.get('criterionVersion')).toBe('1')
    expect(evaluationReads()[0]!.query.get('mode')).toBe('SCREENING')
  })

  it('filters the lines to failing and to inconclusive ones', async () => {
    const user = userEvent.setup()
    await renderApp(path, service)
    const evaluationPanel = await panel()
    await evaluationPanel.findByRole('table', { name: 'Evaluated lines' })

    await user.selectOptions(evaluationPanel.getByLabelText('Show'), 'FAILS')

    let rows = within(evaluationPanel.getByRole('table', { name: 'Evaluated lines' })).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent)).toEqual(['G-05'])

    await user.selectOptions(evaluationPanel.getByLabelText('Show'), 'INCONCLUSIVE')

    rows = within(evaluationPanel.getByRole('table', { name: 'Evaluated lines' })).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent)).toEqual(['L-07'])
  })

  it('reads the evaluation again for the variant and the mode the designer picks, and shows the confirmation sample', async () => {
    const user = userEvent.setup()
    service.evaluationFor = (variantLabel, criterionVersion, mode) =>
      evaluation({
        variantLabel,
        criterionVersion,
        mode,
        verdict: 'UNCONFIRMED',
        confirmationSample: mode === 'CONFIRMATION' ? { requiredSeeds: 100, seeds: 60, fullMatrix: true, satisfied: false } : null,
      })
    await renderApp(path, service)
    const evaluationPanel = await panel()
    await evaluationPanel.findByText(/Verdict:/)
    expect(evaluationPanel.queryByText('Confirmation sample')).not.toBeInTheDocument()

    await user.selectOptions(evaluationPanel.getByLabelText('Variant to evaluate'), 'candidate')
    await user.selectOptions(evaluationPanel.getByLabelText('Mode'), 'CONFIRMATION')

    expect(await evaluationPanel.findByText(/60 of 100 seeds, full case matrix: not satisfied/)).toBeInTheDocument()
    const last = evaluationReads().at(-1)!
    expect(last.query.get('variantLabel')).toBe('candidate')
    expect(last.query.get('mode')).toBe('CONFIRMATION')
  })

  it('lists every validity reason of an invalid evaluation and shows no score', async () => {
    service.evaluationFor = () =>
      evaluation({
        verdict: 'INVALID',
        score: null,
        validity: {
          valid: false,
          reasons: [
            { code: 'UNFINISHED_CASES', detail: '1 pending' },
            { code: 'CONFIGURATION_DRIFT', detail: '1 case(s) failed with CONFIGURATION_DRIFT' },
          ],
        },
      })
    await renderApp(path, service)

    const evaluationPanel = await panel()
    expect(await evaluationPanel.findByText(/Verdict: Invalid/)).toBeInTheDocument()
    expect(evaluationPanel.getByText(/Unfinished cases: 1 pending/)).toBeInTheDocument()
    expect(evaluationPanel.getByText(/Configuration drift: 1 case\(s\) failed with CONFIGURATION_DRIFT/)).toBeInTheDocument()
    expect(evaluationPanel.getByText(/No score: an invalid evaluation is not scored/)).toBeInTheDocument()
  })

  it('explains a refused evaluation and offers a retry, keeping the human balance statement', async () => {
    service.failOnce('GET', EVALUATION, () => problem(500, 'INTERNAL', 'Broken.'))
    await renderApp(path, service)

    const evaluationPanel = await panel()
    await waitFor(() => expect(evaluationPanel.getByRole('button', { name: 'Retry' })).toBeInTheDocument())
    expect(evaluationPanel.getByText(BOT_RESULTS_STATEMENT)).toBeInTheDocument()
  })

  it('has no axe violations once the evaluation is shown', async () => {
    const { container } = await renderApp(path, service)
    const evaluationPanel = await panel()
    await evaluationPanel.findByRole('table', { name: 'Evaluated lines' })

    await expectNoViolations(container)
  })
})
