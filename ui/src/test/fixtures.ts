import type { BalanceCriterion, Case, Comparison, EvaluatedLine, Experiment, ExperimentManifest, ExperimentPreview, ExperimentTemplate, LaneBundle, Metric, PolicyBundle, ReplayStep, Report, Run, RunEvaluation } from '../api/generated'

export const IDS = {
  experiment: '6f3f2a52-4f0a-4c69-9a39-2a8f0e2b9d11',
  run: '0b9d9d2c-6a0e-4a50-9a3e-3c1d5b6a7e21',
  caseA: '8a1c1e2f-3b4d-4c5e-8f60-71829a3b4c5d',
  caseB: '9b2d2f30-4c5e-4d6f-9071-8293b4c5d6e7',
  other: '1d2e3f40-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
} as const

const digest = (character: string) => character.repeat(64)

export function manifest(overrides: Partial<ExperimentManifest> = {}): ExperimentManifest {
  return {
    schemaVersion: 1,
    name: 'threshold experiment',
    variants: [
      {
        label: 'baseline',
        rulesArtifact: { digest: digest('a'), name: 'threshold-20' },
        contentArtifact: { digest: digest('b') },
        effectiveRulesDigest: digest('c'),
        effectiveContentDigest: digest('d'),
      },
      {
        label: 'candidate',
        rulesArtifact: { digest: digest('e'), name: 'threshold-22' },
        contentArtifact: { digest: digest('b') },
        effectiveRulesDigest: digest('f'),
        effectiveContentDigest: digest('d'),
      },
    ],
    services: {
      gameService: { imageDigest: `sha256:${digest('1')}`, sourceRevision: 'abcdef1' },
      timelineService: { imageDigest: `sha256:${digest('2')}`, sourceRevision: 'abcdef2' },
      readService: { imageDigest: `sha256:${digest('3')}`, sourceRevision: 'abcdef3' },
    },
    contracts: { 'session-api': '3.0.0' },
    policies: [
      {
        id: 'random',
        version: 'v1',
        artifactDigest: digest('9'),
        parameters: {},
        seatAssignment: [
          { seatIndex: 0, policyId: 'random', policyVersion: 'v1' },
          { seatIndex: 1, policyId: 'random', policyVersion: 'v1' },
          { seatIndex: 2, policyId: 'random', policyVersion: 'v1' },
        ],
      },
    ],
    seeds: ['42', '43'],
    playerCounts: [3],
    factionSets: [['ERASERS', 'PROPHETS', 'WEAVERS']],
    seatRotationMode: 'CYCLIC',
    timingMode: 'LOGICAL',
    concurrency: 2,
    caseWallTimeoutSeconds: 300,
    maxRejectedCandidatesPerWindow: 5,
    ...overrides,
  }
}

export const policyCatalog: PolicyBundle[] = [
  { id: 'random', version: 'v1', artifactDigest: digest('9'), description: 'Uniform random choice', parameters: {} },
  { id: 'faction-greedy', version: 'v1', artifactDigest: digest('8'), description: 'Plays toward the faction objective', parameters: { greed: 'weight' } },
]

/** The bundles the lanes serve: the fixture manifest's baseline and candidate variants, in digest order. */
export function laneBundles(): LaneBundle[] {
  const { variants, services, contracts } = manifest()
  return variants.map((variant, index) => ({
    rulesArtifact: { ...variant.rulesArtifact },
    contentArtifact: { ...variant.contentArtifact },
    effectiveRulesDigest: variant.effectiveRulesDigest,
    effectiveContentDigest: variant.effectiveContentDigest,
    services: structuredClone(services),
    contracts: { ...contracts },
    laneCount: index === 0 ? 2 : 1,
  }))
}

const EVERY_FACTION_SET: ExperimentManifest['factionSets'] = (() => {
  const factions = ['ERASERS', 'PROPHETS', 'REVISIONISTS', 'WEAVERS', 'ACTIVISTS'] as const
  const sets: ExperimentManifest['factionSets'] = []
  const collect = (size: number, from: number, chosen: (typeof factions)[number][]) => {
    if (chosen.length === size) {
      sets.push([...chosen])
      return
    }
    for (let index = from; index < factions.length; index += 1) {
      collect(size, index + 1, [...chosen, factions[index]!])
    }
  }
  ;[3, 4, 5].forEach((size) => collect(size, 0, []))
  return sets
})()

function everySeat(id: string, version: string) {
  return [0, 1, 2, 3, 4].map((seatIndex) => ({ seatIndex, policyId: id, policyVersion: version }))
}

export function experimentTemplates(): ExperimentTemplate[] {
  const defaults = (policies: ExperimentTemplate['defaults']['policies']): ExperimentTemplate['defaults'] => ({
    seeds: ['42'],
    playerCounts: [3, 4, 5],
    factionSets: EVERY_FACTION_SET.map((set) => [...set]),
    seatRotationMode: 'CYCLIC',
    timingMode: 'LOGICAL',
    policies,
    concurrency: 3,
    caseWallTimeoutSeconds: 3600,
    maxRejectedCandidatesPerWindow: 10,
  })
  const random = { id: 'random', version: 'v1', artifactDigest: digest('9'), parameters: {}, seatAssignment: everySeat('random', 'v1') }
  const greedy = { id: 'faction-greedy', version: 'v1', artifactDigest: digest('8'), parameters: {}, seatAssignment: everySeat('faction-greedy', 'v1') }
  return [
    {
      templateId: 'baseline-policy-comparison',
      name: 'Baseline policy comparison',
      description: 'Compares the random and faction-greedy baseline policies under one bundle.',
      variantCount: 1,
      defaults: defaults([random, greedy]),
    },
    {
      templateId: 'rules-variant-comparison',
      name: 'Rules variant comparison',
      description: 'Compares two bundles with the random baseline policy on every seat.',
      variantCount: 2,
      defaults: defaults([random]),
    },
  ]
}

export function experiment(overrides: Partial<Experiment> = {}): Experiment {
  return {
    experimentId: IDS.experiment,
    manifest: manifest(),
    manifestDigest: digest('7'),
    createdAt: '2026-10-11T09:00:00Z',
    ...overrides,
  }
}

export function preview(overrides: Partial<ExperimentPreview> = {}): ExperimentPreview {
  return {
    manifestDigest: digest('7'),
    caseCount: 4,
    breakdown: [
      { variantLabel: 'baseline', playerCount: 3, caseCount: 2 },
      { variantLabel: 'candidate', playerCount: 3, caseCount: 2 },
    ],
    cases: [
      {
        caseKey: IDS.caseA,
        variantLabel: 'baseline',
        seed: '42',
        playerCount: 3,
        assignments: [
          { seatIndex: 0, faction: 'ERASERS', policyId: 'random', policyVersion: 'v1' },
          { seatIndex: 1, faction: 'PROPHETS', policyId: 'random', policyVersion: 'v1' },
          { seatIndex: 2, faction: 'WEAVERS', policyId: 'random', policyVersion: 'v1' },
        ],
      },
    ],
    limit: 20,
    offset: 0,
    ...overrides,
  }
}

export function run(overrides: Partial<Run> = {}): Run {
  return {
    runId: IDS.run,
    experimentId: IDS.experiment,
    state: 'RUNNING',
    counts: { requested: 4, pending: 1, running: 1, succeeded: 2, failed: 0, cancelled: 0 },
    createdAt: '2026-10-11T09:05:00Z',
    startedAt: '2026-10-11T09:05:01Z',
    finishedAt: null,
    failure: null,
    ...overrides,
  }
}

export function caseDetail(overrides: Partial<Case> = {}): Case {
  return {
    caseId: IDS.caseA,
    caseKey: IDS.caseB,
    runId: IDS.run,
    variantLabel: 'baseline',
    seed: '42',
    playerCount: 3,
    assignments: [
      { seatIndex: 0, faction: 'ERASERS', policyId: 'random', policyVersion: 'v1' },
      { seatIndex: 1, faction: 'PROPHETS', policyId: 'random', policyVersion: 'v1' },
      { seatIndex: 2, faction: 'WEAVERS', policyId: 'random', policyVersion: 'v1' },
    ],
    state: 'FAILED',
    attempts: [
      {
        attemptId: IDS.other,
        ordinal: 1,
        state: 'FAILED',
        gameId: null,
        checkpointArtifact: null,
        failure: { code: 'CONTRACT_MISMATCH', message: 'The game service answered with an unexpected contract.' },
      },
    ],
    result: null,
    ...overrides,
  }
}

const counts = (overrides: Partial<Report['caseCounts']> = {}): Report['caseCounts'] => ({
  requested: 4,
  pending: 0,
  running: 0,
  succeeded: 4,
  failed: 0,
  cancelled: 0,
  ...overrides,
})

export function metric(overrides: Partial<Metric> = {}): Metric {
  return {
    name: 'faction_win_rate',
    statistic: 'RATE',
    dimensions: { faction: 'ERASERS' },
    value: 0.5,
    numerator: 2,
    denominator: 4,
    unit: 'share',
    status: 'AVAILABLE',
    interval: { level: 0.95, lower: 0.25, upper: 0.75, method: 'BLOCK_BOOTSTRAP' },
    unknownCount: 0,
    ...overrides,
  }
}

export function report(overrides: Partial<Report> = {}): Report {
  return {
    formatVersion: 2,
    runId: IDS.run,
    manifestDigest: digest('7'),
    analysisVersion: '1',
    analysisSeed: '7',
    complete: true,
    caseCounts: counts(),
    cohorts: [
      {
        variantLabel: 'baseline',
        policyId: 'random',
        policyVersion: 'v1',
        playerCount: 3,
        factionSet: null,
        seatIndex: null,
        caseCounts: counts(),
        eligibleGames: 4,
        independentBlocks: 2,
        metrics: [
          metric(),
          metric({ name: 'score', statistic: 'MEAN', dimensions: {}, value: 11.5, numerator: null, denominator: 4, unit: 'points', status: 'INSUFFICIENT_SAMPLE', interval: null }),
          metric({ name: 'card_playable_rate', dimensions: { cardType: 'INTERCEPT' }, value: null, numerator: null, denominator: 0, status: 'NO_DATA', interval: null, unknownCount: 3 }),
        ],
      },
    ],
    ...overrides,
  }
}

/** Exactly what the fake service answers for the CSV representation; a test compares downloaded bytes with it. */
export const REPORT_CSV = 'format_version,run_id,metric\r\n1,0b9d9d2c,faction_win_rate\r\n'

export const COMPARISON_ID = '4d5e6f70-8192-4a3b-9c4d-5e6f708192a3'

export function comparison(overrides: Partial<Comparison> = {}): Comparison {
  return {
    formatVersion: 1,
    comparisonId: COMPARISON_ID,
    baseline: { runId: IDS.run, variantLabel: 'baseline' },
    candidate: { runId: IDS.run, variantLabel: 'candidate' },
    declaredDifferences: [{ field: 'RULES', baselineDigest: digest('c'), candidateDigest: digest('f') }],
    analysisVersion: '1',
    analysisSeed: '7',
    complete: true,
    matchedBlocks: 2,
    unmatchedCases: 0,
    failedCounterparts: 0,
    excludedPairs: [],
    cohorts: [
      {
        policyId: 'random',
        policyVersion: 'v1',
        playerCount: 3,
        factionSet: null,
        seatIndex: null,
        matchedBlocks: 2,
        excludedBlocks: 0,
        metricDifferences: [
          { name: 'faction_win_rate', statistic: 'RATE', dimensions: { faction: 'ERASERS' }, unit: 'share', baselineValue: 0.5, candidateValue: 0.75, difference: 0.25, status: 'AVAILABLE', interval: { level: 0.95, lower: 0.05, upper: 0.45, method: 'BLOCK_BOOTSTRAP' } },
        ],
      },
    ],
    ...overrides,
  }
}

/** A fact only seat 1 may know. It is in seat 1's view and the observer's, and must never reach seat 0's. */
export const HIDDEN_HAND = 'HIDDEN-HAND-OF-SEAT-1'

function playerStep(step: number, seatIndex: number, fact: Record<string, unknown>): ReplayStep {
  return {
    step,
    eraNumber: 1,
    roundNumber: 1,
    phase: 'ACTION',
    logicalTime: '2026-10-11T09:00:00Z',
    observations: { seatIndex, ...fact },
    decision: { seatIndex, window: 'play', candidate: 'card-1', entropy: null },
    acceptedResult: { outcome: 'ACCEPTED' },
  }
}

export const SEAT_STEPS: Readonly<Record<number, ReplayStep[]>> = {
  0: [playerStep(0, 0, { hand: ['seat-0-card'] }), playerStep(3, 0, { hand: ['seat-0-card-b'] })],
  1: [playerStep(1, 1, { hand: [HIDDEN_HAND], faction: 'PROPHETS' }), playerStep(4, 1, { hand: [HIDDEN_HAND] })],
  2: [playerStep(2, 2, { hand: ['seat-2-card'] }), playerStep(5, 2, { hand: ['seat-2-card-b'] })],
}

export function eventStep(step: number, source: string, offset: number): ReplayStep {
  return {
    step,
    eraNumber: null,
    roundNumber: null,
    phase: 'EVENT',
    logicalTime: '2026-10-11T09:00:00Z',
    observations: { source, partition: 0, offset, eventType: 'CardPlayed', payload: { note: `${source}-${offset}` } },
    decision: null,
    acceptedResult: null,
  }
}

export function criterion(overrides: Partial<BalanceCriterion> = {}): BalanceCriterion {
  return {
    version: 1,
    digest: digest('5'),
    title: 'Balance criterion version 1',
    analysisVersion: '2',
    lines: [],
    aggregation: 'WEIGHTED_SUM_OF_SQUARED_DEVIATIONS',
    evidence: {
      confidenceLevel: 0.95,
      executionFailedShareLimit: 0.05,
      invalidatingFailureCodes: ['CONTRACT_MISMATCH', 'CONFIGURATION_DRIFT'],
      invalidatingEndReasons: ['ALL_PLAYERS_ABANDONED'],
      confirmationMinimumSeeds: 100,
    },
    ...overrides,
  }
}

export function evaluatedLine(overrides: Partial<EvaluatedLine> = {}): EvaluatedLine {
  return {
    lineId: 'L-01',
    role: 'SCORED',
    metric: 'faction_win_rate',
    dimensions: { faction: 'ERASERS' },
    policyId: 'faction-greedy-v1',
    playerCount: 3,
    factionSet: null,
    band: { lower: 0.8, upper: 1.25, scale: null },
    value: 1,
    interval: { level: 0.95, lower: 0.9, upper: 1.1, method: 'BLOCK_BOOTSTRAP' },
    status: 'AVAILABLE',
    deviation: 0,
    verdict: 'MEETS',
    weight: 0.266667,
    ...overrides,
  }
}

export function evaluation(overrides: Partial<RunEvaluation> = {}): RunEvaluation {
  return {
    runId: IDS.run,
    variantLabel: 'baseline',
    criterionVersion: 1,
    criterionDigest: digest('5'),
    analysisVersion: '2',
    analysisSeed: '1',
    mode: 'SCREENING',
    validity: { valid: true, reasons: [] },
    verdict: 'REJECTED',
    score: 0.029630,
    confirmationSample: null,
    lines: [
      evaluatedLine({
        lineId: 'G-05',
        role: 'GATE',
        band: { lower: 0.5, upper: 2, scale: null },
        value: 2.4,
        interval: { level: 0.95, lower: 2.2, upper: 2.6, method: 'BLOCK_BOOTSTRAP' },
        deviation: 0.266667,
        verdict: 'FAILS',
        weight: null,
      }),
      evaluatedLine({ lineId: 'L-01', dimensions: { faction: 'PROPHETS' } }),
      evaluatedLine({
        lineId: 'L-07',
        metric: 'paradox_cascade_rate',
        dimensions: {},
        band: { lower: 0.25, upper: 0.75, scale: null },
        value: null,
        interval: null,
        status: 'NO_DATA',
        verdict: 'INCONCLUSIVE',
      }),
    ],
    ...overrides,
  }
}
