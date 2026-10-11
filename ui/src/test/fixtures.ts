import type { Case, Experiment, ExperimentManifest, ExperimentPreview, PolicyBundle, Run } from '../api/generated'

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
        rulesArtifact: { digest: digest('a') },
        contentArtifact: { digest: digest('b') },
        effectiveRulesDigest: digest('c'),
        effectiveContentDigest: digest('d'),
      },
      {
        label: 'candidate',
        rulesArtifact: { digest: digest('e') },
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
