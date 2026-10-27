import type { FidelityCase, FidelityCheck, FidelityEnvironment, SimulatorFidelity } from '../api/generated'
import { json } from './fakeService'
import type { FakeService } from './fakeService'

export const FIDELITY_IDS = {
  check: 'b1d2c3e4-5f60-4a71-8b92-a3b4c5d6e7f8',
  other: 'c2e3d4f5-6071-4b82-9ca3-b4c5d6e7f809',
  experiment: 'd3f4e5a6-7182-4c93-8db4-c5d6e7f8091a',
  run: 'e4a5f6b7-8293-4da4-9ec5-d6e7f8091a2b',
} as const

const digest = (character: string) => character.repeat(64)

export function environment(image: string, revision = 'abcdef1'): FidelityEnvironment {
  return {
    services: {
      gameService: { imageDigest: `sha256:${digest(image)}`, sourceRevision: revision },
      timelineService: { imageDigest: `sha256:${digest(image)}`, sourceRevision: 'abcdef2' },
      readService: { imageDigest: `sha256:${digest(image)}`, sourceRevision: 'abcdef3' },
    },
    contentDigest: digest('b'),
    contracts: { 'session-api': '3.0.0' },
  }
}

export function check(overrides: Partial<FidelityCheck> = {}): FidelityCheck {
  return {
    fidelityCheckId: FIDELITY_IDS.check,
    seed: '42',
    state: 'RUNNING',
    verdict: null,
    simulator: environment('1'),
    lane: environment('9'),
    experimentId: FIDELITY_IDS.experiment,
    runId: FIDELITY_IDS.run,
    variants: [
      { label: 'baseline', effectiveRulesDigest: digest('a'), overrides: {} },
      { label: 'low', effectiveRulesDigest: digest('c'), overrides: { 'game.rules.win-score-threshold': 16 } },
      { label: 'high', effectiveRulesDigest: digest('d'), overrides: { 'game.rules.win-score-threshold': 26 } },
    ],
    counts: { total: 330, matched: 100, diverged: 0, unreproducible: 0, pending: 230 },
    failure: null,
    createdAt: '2026-01-01T00:00:00Z',
    startedAt: '2026-01-01T00:00:05Z',
    finishedAt: null,
    ...overrides,
  }
}

export function fidelityCase(overrides: Partial<FidelityCase> = {}): FidelityCase {
  return {
    caseKey: '0b9d9d2c-6a0e-4a50-9a3e-3c1d5b6a7e21',
    variantLabel: 'baseline',
    playerCount: 3,
    factionSet: ['ACTIVISTS', 'ERASERS', 'PROPHETS'],
    seatRotation: 0,
    policyId: 'faction-greedy',
    result: 'MATCH',
    firstDivergence: null,
    failure: null,
    ...overrides,
  }
}

export function gate(established: boolean, record: FidelityCheck | null = null): SimulatorFidelity {
  return {
    sourceRevisions: { gameService: 'abcdef1', timelineService: 'abcdef2', readService: 'abcdef3' },
    established,
    record,
  }
}

/** Answers the fidelity reads of a test from the given records; the fake service knows no fidelity operation by itself. */
export function serveFidelity(
  service: FakeService,
  state: { gate?: SimulatorFidelity; checks?: FidelityCheck[]; cases?: FidelityCase[] } = {},
): void {
  const checks = state.checks ?? [check()]
  service.override('GET', /^\/api\/v1\/simulator\/fidelity$/, () => json(state.gate ?? gate(false)))
  service.override('GET', /^\/api\/v1\/fidelity-checks$/, () => json({ items: checks, total: checks.length }))
  service.override('GET', /^\/api\/v1\/fidelity-checks\/([^/]+)$/, (_request, match) => {
    const found = checks.find((item) => item.fidelityCheckId === match[1])
    return found ? json(found) : json({ type: 'about:blank', title: 'Problem', status: 404, detail: 'No such check.', code: 'RESOURCE_NOT_FOUND' }, 404)
  })
  service.override('GET', /^\/api\/v1\/fidelity-checks\/[^/]+\/cases$/, (request) => {
    const cases = (state.cases ?? [fidelityCase()]).filter(
      (item) =>
        (request.query.get('variantLabel') === null || item.variantLabel === request.query.get('variantLabel')) &&
        (request.query.get('result') === null || item.result === request.query.get('result')),
    )
    return json({ items: cases, total: cases.length })
  })
}
