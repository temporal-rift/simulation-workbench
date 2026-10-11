import type { EnumsFaction, ExperimentManifest } from '../api/generated'

export const FACTIONS: readonly EnumsFaction[] = ['ERASERS', 'PROPHETS', 'REVISIONISTS', 'WEAVERS', 'ACTIVISTS']
export const PLAYER_COUNTS = [3, 4, 5] as const
export const SERVICE_NAMES = ['gameService', 'timelineService', 'readService'] as const
export type ServiceName = (typeof SERVICE_NAMES)[number]

export interface DraftArtifact {
  digest: string
  mediaType?: string | null
  name?: string | null
}

export interface DraftVariant {
  label: string
  rulesArtifact: DraftArtifact
  contentArtifact: DraftArtifact
  effectiveRulesDigest: string
  effectiveContentDigest: string
}

export interface DraftService {
  imageDigest: string
  sourceRevision: string
}

export interface DraftContract {
  module: string
  version: string
}

export interface DraftPolicy {
  id: string
  version: string
  artifactDigest: string
  /** Edited as JSON text; it must be a JSON object to be sent. */
  parameters: string
  /** Seat indexes as typed, so a half-edited value is kept. */
  seats: string[]
}

/**
 * The experiment under edit, shaped like the manifest it becomes so that the path the service reports for a
 * refused value (`policies[0].seatAssignment[1].seatIndex`) leads straight to the input holding it. Values the
 * designer types are kept as text until the draft is sent.
 */
export interface Draft {
  name: string
  variants: DraftVariant[]
  services: Record<ServiceName, DraftService>
  contracts: DraftContract[]
  policies: DraftPolicy[]
  seeds: string
  playerCounts: number[]
  factionSets: EnumsFaction[][]
  concurrency: string
  caseWallTimeoutSeconds: string
  maxRejectedCandidatesPerWindow: string
}

export function emptyVariant(): DraftVariant {
  return {
    label: '',
    rulesArtifact: { digest: '' },
    contentArtifact: { digest: '' },
    effectiveRulesDigest: '',
    effectiveContentDigest: '',
  }
}

export function emptyPolicy(): DraftPolicy {
  return { id: '', version: '', artifactDigest: '', parameters: '{}', seats: [''] }
}

export function emptyDraft(): Draft {
  const service = (): DraftService => ({ imageDigest: '', sourceRevision: '' })
  return {
    name: '',
    variants: [emptyVariant()],
    services: { gameService: service(), timelineService: service(), readService: service() },
    contracts: [{ module: '', version: '' }],
    policies: [],
    seeds: '',
    playerCounts: [],
    factionSets: [],
    concurrency: '',
    caseWallTimeoutSeconds: '',
    maxRejectedCandidatesPerWindow: '',
  }
}

/** Seeds are typed one per line or separated by commas or spaces. */
export function seedList(seeds: string): string[] {
  return seeds.split(/[\s,]+/).filter(Boolean)
}

function wholeNumber(value: string): number | undefined {
  const trimmed = value.trim()
  return trimmed === '' ? undefined : Number(trimmed)
}

export type FieldErrors = Readonly<Record<string, string>>

function parsedParameters(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text.trim() || '{}')
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/** Problems found before sending: the parts of a draft that cannot be turned into a manifest at all. */
export function localProblems(draft: Draft): FieldErrors {
  const problems: Record<string, string> = {}
  draft.policies.forEach((policy, index) => {
    if (parsedParameters(policy.parameters) === null) {
      problems[`policies[${index}].parameters`] = 'Parameters must be a JSON object, for example {}.'
    }
  })
  return problems
}

function artifactOf(artifact: DraftArtifact) {
  return {
    digest: artifact.digest.trim(),
    ...(artifact.mediaType ? { mediaType: artifact.mediaType } : {}),
    ...(artifact.name ? { name: artifact.name } : {}),
  }
}

/**
 * The manifest a draft stands for. Values that are not yet valid are carried as they are (a missing number is
 * left out, a non-number becomes `null`) so the service, which owns validation, reports them per field.
 */
export function draftToManifest(draft: Draft): ExperimentManifest {
  const service = (name: ServiceName) => ({
    imageDigest: draft.services[name].imageDigest.trim(),
    sourceRevision: draft.services[name].sourceRevision.trim(),
  })
  const manifest = {
    schemaVersion: 1,
    name: draft.name.trim(),
    variants: draft.variants.map((variant) => ({
      label: variant.label.trim(),
      rulesArtifact: artifactOf(variant.rulesArtifact),
      contentArtifact: artifactOf(variant.contentArtifact),
      effectiveRulesDigest: variant.effectiveRulesDigest.trim(),
      effectiveContentDigest: variant.effectiveContentDigest.trim(),
    })),
    services: { gameService: service('gameService'), timelineService: service('timelineService'), readService: service('readService') },
    contracts: Object.fromEntries(
      draft.contracts.filter((row) => row.module.trim() !== '').map((row) => [row.module.trim(), row.version.trim()]),
    ),
    policies: draft.policies.map((policy) => ({
      id: policy.id,
      version: policy.version,
      artifactDigest: policy.artifactDigest,
      parameters: parsedParameters(policy.parameters) ?? {},
      seatAssignment: policy.seats.map((seat) => ({ seatIndex: wholeNumber(seat), policyId: policy.id, policyVersion: policy.version })),
    })),
    seeds: seedList(draft.seeds),
    playerCounts: [...draft.playerCounts].sort((a, b) => a - b),
    factionSets: draft.factionSets.map((set) => [...set]),
    seatRotationMode: 'CYCLIC',
    timingMode: 'LOGICAL',
    concurrency: wholeNumber(draft.concurrency),
    caseWallTimeoutSeconds: wholeNumber(draft.caseWallTimeoutSeconds),
    maxRejectedCandidatesPerWindow: wholeNumber(draft.maxRejectedCandidatesPerWindow),
  }
  // The shape is the manifest's; only its values may still be incomplete, which the service reports.
  return manifest as unknown as ExperimentManifest
}

/** The draft that reproduces a manifest, for starting a new experiment from a frozen one. */
export function manifestToDraft(manifest: ExperimentManifest): Draft {
  return {
    name: manifest.name,
    variants: manifest.variants.map((variant) => ({
      label: variant.label,
      rulesArtifact: { ...variant.rulesArtifact },
      contentArtifact: { ...variant.contentArtifact },
      effectiveRulesDigest: variant.effectiveRulesDigest,
      effectiveContentDigest: variant.effectiveContentDigest,
    })),
    services: {
      gameService: { ...manifest.services.gameService },
      timelineService: { ...manifest.services.timelineService },
      readService: { ...manifest.services.readService },
    },
    contracts: Object.entries(manifest.contracts).map(([module, version]) => ({ module, version })),
    policies: manifest.policies.map((policy) => ({
      id: policy.id,
      version: policy.version,
      artifactDigest: policy.artifactDigest,
      parameters: JSON.stringify(policy.parameters),
      seats: policy.seatAssignment.map((seat) => String(seat.seatIndex)),
    })),
    seeds: manifest.seeds.join('\n'),
    playerCounts: [...manifest.playerCounts],
    factionSets: manifest.factionSets.map((set) => [...set]),
    concurrency: String(manifest.concurrency),
    caseWallTimeoutSeconds: String(manifest.caseWallTimeoutSeconds),
    maxRejectedCandidatesPerWindow: String(manifest.maxRejectedCandidatesPerWindow),
  }
}

/** Identifies the content that was previewed, so a preview is never mistaken for a draft edited since. */
export function fingerprint(manifest: ExperimentManifest): string {
  return JSON.stringify(manifest)
}

export function errorAt(errors: FieldErrors, path: string): string | undefined {
  return errors[path]
}

/** The error on `path` or on anything inside it, for inputs that edit a whole list (such as the seeds). */
export function errorWithin(errors: FieldErrors, path: string): string | undefined {
  const key = Object.keys(errors).find((candidate) => candidate === path || candidate.startsWith(`${path}[`) || candidate.startsWith(`${path}.`))
  return key === undefined ? undefined : errors[key]
}

const STORAGE_KEY = 'workbench.experiment-draft.v1'

export interface StoredDraft {
  readonly draft: Draft
  /** The frozen experiment this draft started from, when it did. */
  readonly basedOn: { readonly experimentId: string; readonly name: string } | null
}

/**
 * The unsent draft is kept in the tab's own session storage so that it survives an expired session and a
 * reload. It holds the manifest being edited and never credentials.
 */
export function saveDraft(stored: StoredDraft): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
  } catch {
    // Storage is full or blocked: editing continues, the draft just will not survive a reload.
  }
}

export function loadDraft(): StoredDraft | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null || typeof (parsed as { draft?: unknown }).draft !== 'object') {
      return null
    }
    const stored = parsed as { draft: Partial<Draft>; basedOn?: StoredDraft['basedOn'] }
    return { draft: { ...emptyDraft(), ...stored.draft }, basedOn: stored.basedOn ?? null }
  } catch {
    return null
  }
}

export function clearDraft(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Storage is blocked: nothing was persisted to clear.
  }
}

export function hasUnsentDraft(): boolean {
  return loadDraft() !== null
}
