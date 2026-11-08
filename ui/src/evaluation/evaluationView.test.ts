import { describe, expect, it } from 'vitest'
import { evaluatedLine } from '../test/fixtures'
import { describeCohort } from './evaluationView'

describe('the cohort of an evaluated line', () => {
  it('names only the policy when the table is named for it', () => {
    expect(describeCohort(evaluatedLine())).toBe('faction-greedy-v1 · 3 players · all faction sets · ERASERS')
  })

  it('names the table and the seats its policy plays when the table mixes policies', () => {
    const line = evaluatedLine({
      lineId: 'G-07',
      tableId: 'random-seat-vs-faction-greedy-v1',
      policyId: 'random-v1',
      playerCount: 5,
    })

    expect(describeCohort(line)).toBe(
      'random-seat-vs-faction-greedy-v1 (random-v1 seats) · 5 players · all faction sets · ERASERS',
    )
  })
})
