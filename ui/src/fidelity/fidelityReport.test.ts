import { describe, expect, it } from 'vitest'
import { check, fidelityCase } from '../test/fidelityFixtures'
import { fidelityReport, reportFilename } from './fidelityReport'

const passed = check({
  state: 'PASSED',
  verdict: 'PASSED',
  counts: { total: 330, matched: 330, diverged: 0, unreproducible: 0, pending: 0 },
  finishedAt: '2026-01-01T01:00:00Z',
})

describe('the report of a check', () => {
  it('is named for the seed and the game-service revision', () => {
    expect(reportFilename(passed)).toBe('report-42-abcdef1.md')
  })

  it('carries the fields of the served record', () => {
    const report = fidelityReport(passed, [])

    expect(report).toContain('# Simulator fidelity report, seed 42')
    expect(report).toContain(`- Check: \`${passed.fidelityCheckId}\``)
    expect(report).toContain('- State: Passed')
    expect(report).toContain('- Verdict: PASSED')
    expect(report).toContain('| 330 | 330 | 0 | 0 | 0 |')
    expect(report).toContain('| Simulator | gameService | `abcdef1` |')
    expect(report).toContain('| Real lane | readService | `abcdef3` |')
    expect(report).toContain('| low | `' + 'c'.repeat(64) + '` | `game.rules.win-score-threshold` = 16 |')
    expect(report).toContain('| baseline | `' + 'a'.repeat(64) + '` | none |')
    expect(report).not.toContain('Cases that did not match')
  })

  it('lists what a failed check failed on', () => {
    const failed = check({ state: 'FAILED', verdict: 'FAILED', counts: { total: 330, matched: 328, diverged: 1, unreproducible: 1, pending: 0 } })
    const report = fidelityReport(failed, [
      fidelityCase({ result: 'DIVERGED', firstDivergence: { step: 12, kind: 'DECISION', expected: {}, actual: {} } }),
      fidelityCase({ result: 'UNREPRODUCIBLE', failure: { code: 'RUNNER_TIMEOUT', message: 'timed out' } }),
    ])

    expect(report).toContain('- Verdict: FAILED')
    expect(report).toContain('## Cases that did not match')
    expect(report).toContain('differs at step 12: DECISION')
    expect(report).toContain('timed out (RUNNER_TIMEOUT)')
  })

  it('names the failure of a check that could not complete', () => {
    const report = fidelityReport(check({ state: 'FAILED', verdict: 'FAILED', failure: { code: 'RUN_FAILED', message: 'lane crashed' } }), [])

    expect(report).toContain('## Failure')
    expect(report).toContain('lane crashed (`RUN_FAILED`)')
  })

  it('says a check that has not ended has no verdict yet', () => {
    expect(fidelityReport(check(), [])).toContain('- Verdict: not yet decided')
  })
})
