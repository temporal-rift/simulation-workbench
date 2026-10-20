import type { EnumsFaction, ExperimentManifest, ExperimentTemplate, LaneBundle } from '../api/generated'

export const FACTIONS: readonly EnumsFaction[] = ['ERASERS', 'PROPHETS', 'REVISIONISTS', 'WEAVERS', 'ACTIVISTS']
export const PLAYER_COUNTS = [3, 4, 5] as const
export const SERVICE_NAMES = ['gameService', 'timelineService', 'readService'] as const
export type ServiceName = (typeof SERVICE_NAMES)[number]

export interface DraftArtifact {
  digest: string
  mediaType?: string | null
  name?: string | null
}

export interface DraftService {
  imageDigest: string
  sourceRevision: string
}

/**
 * What a variant runs, exactly as a lane bundle (or the frozen experiment it was copied from) states it. It is chosen,
 * never typed, so it is kept whole: a draft restored later still sends the values that were chosen.
 */
export interface DraftBundle {
  rulesArtifact: DraftArtifact
  contentArtifact: DraftArtifact
  effectiveRulesDigest: string
  effectiveContentDigest: string
  services: Record<ServiceName, DraftService>
  contracts: Record<string, string>
}

/** One parameter the variant sets, with the value as typed so a half-edited number is kept. */
export interface DraftOverride {
  key: string
  value: string
}

export interface DraftVariant {
  label: string
  /** `null` until the designer selects the bundle this variant runs; the bundle is the variant baseline. */
  bundle: DraftBundle | null
  /** The parameter space the overrides are chosen from and validated against; `null` while there are none. */
  parameterSpaceVersion: number | null
  overrides: DraftOverride[]
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
 * designer types are kept as text until the draft is sent; the manifest's services and contracts are those of the
 * selected bundles.
 */
export interface Draft {
  name: string
  variants: DraftVariant[]
  policies: DraftPolicy[]
  seeds: string
  playerCounts: number[]
  factionSets: EnumsFaction[][]
  concurrency: string
  caseWallTimeoutSeconds: string
  maxRejectedCandidatesPerWindow: string
}

export function emptyVariant(): DraftVariant {
  return { label: '', bundle: null, parameterSpaceVersion: null, overrides: [] }
}

export function emptyPolicy(): DraftPolicy {
  return { id: '', version: '', artifactDigest: '', parameters: '{}', seats: [''] }
}

export function emptyDraft(): Draft {
  return {
    name: '',
    variants: [emptyVariant()],
    policies: [],
    seeds: '',
    playerCounts: [],
    factionSets: [],
    concurrency: '',
    caseWallTimeoutSeconds: '',
    maxRejectedCandidatesPerWindow: '',
  }
}

/** Identifies a bundle by the effective digests a lane serves it under. */
export function bundleKey(bundle: Pick<DraftBundle, 'effectiveRulesDigest' | 'effectiveContentDigest'>): string {
  return `${bundle.effectiveRulesDigest}/${bundle.effectiveContentDigest}`
}

function shortDigest(digest: string): string {
  return `${digest.slice(0, 12)}…`
}

/** A bundle as a designer recognises it: its artifacts' names, or the start of their effective digests. */
export function bundleLabel(bundle: DraftBundle): string {
  const rules = bundle.rulesArtifact.name ?? `rules ${shortDigest(bundle.effectiveRulesDigest)}`
  const content = bundle.contentArtifact.name ?? `content ${shortDigest(bundle.effectiveContentDigest)}`
  return `${rules} · ${content}`
}

export function bundleFromLane(lane: LaneBundle): DraftBundle {
  return {
    rulesArtifact: { ...lane.rulesArtifact },
    contentArtifact: { ...lane.contentArtifact },
    effectiveRulesDigest: lane.effectiveRulesDigest,
    effectiveContentDigest: lane.effectiveContentDigest,
    services: {
      gameService: { ...lane.services.gameService },
      timelineService: { ...lane.services.timelineService },
      readService: { ...lane.services.readService },
    },
    contracts: { ...lane.contracts },
  }
}

function sameArtifact(a: DraftArtifact, b: DraftArtifact): boolean {
  return a.digest === b.digest && (a.name ?? null) === (b.name ?? null) && (a.mediaType ?? null) === (b.mediaType ?? null)
}

function sameContracts(a: Record<string, string>, b: Record<string, string>): boolean {
  const modules = Object.keys(a)
  return modules.length === Object.keys(b).length && modules.every((module) => a[module] === b[module])
}

/** Whether two bundles name the same artifacts, effective digests, service builds and contract versions. */
export function sameBundle(a: DraftBundle, b: DraftBundle): boolean {
  return (
    bundleKey(a) === bundleKey(b) &&
    sameArtifact(a.rulesArtifact, b.rulesArtifact) &&
    sameArtifact(a.contentArtifact, b.contentArtifact) &&
    SERVICE_NAMES.every(
      (name) => a.services[name].imageDigest === b.services[name].imageDigest && a.services[name].sourceRevision === b.services[name].sourceRevision,
    ) &&
    sameContracts(a.contracts, b.contracts)
  )
}

/**
 * Whether a configured lane serves exactly this bundle. A restored or copied bundle whose digests a lane still serves
 * under other provenance is not served: freezing it would pin builds no lane runs.
 */
export function isServed(bundle: DraftBundle, lanes: readonly LaneBundle[]): boolean {
  return lanes.some((lane) => sameBundle(bundleFromLane(lane), bundle))
}

/**
 * The draft with each variant that carries overrides rebased onto the lane bundle it was copied from. A frozen variant
 * with overrides states the digest of its effective rules, not that of its baseline, so it matches no lane until it is
 * matched by the artifacts it names. Returns the same draft when nothing changes.
 */
export function alignToLanes(draft: Draft, lanes: readonly LaneBundle[]): Draft {
  let changed = false
  const variants = draft.variants.map((variant) => {
    const bundle = variant.bundle
    if (bundle === null || variant.overrides.length === 0 || isServed(bundle, lanes)) {
      return variant
    }
    const baseline = lanes.find(
      (lane) =>
        lane.rulesArtifact.digest === bundle.rulesArtifact.digest &&
        lane.contentArtifact.digest === bundle.contentArtifact.digest &&
        lane.effectiveContentDigest === bundle.effectiveContentDigest,
    )
    if (baseline === undefined) {
      return variant
    }
    changed = true
    return { ...variant, bundle: bundleFromLane(baseline) }
  })
  return changed ? { ...draft, variants } : draft
}

function selectedBundles(draft: Draft): DraftBundle[] {
  return draft.variants.flatMap((variant) => (variant.bundle ? [variant.bundle] : []))
}

/**
 * The manifest fields in which the selected bundles disagree, such as `contracts.session-api`. A manifest pins one
 * set of services and contracts, so variants are comparable only when their bundles share them.
 */
export function incomparableFields(draft: Draft): string[] {
  const [first, ...others] = selectedBundles(draft)
  if (!first) {
    return []
  }
  const fields = new Set<string>()
  for (const other of others) {
    for (const name of SERVICE_NAMES) {
      for (const key of ['imageDigest', 'sourceRevision'] as const) {
        if (first.services[name][key] !== other.services[name][key]) {
          fields.add(`services.${name}.${key}`)
        }
      }
    }
    for (const module of new Set([...Object.keys(first.contracts), ...Object.keys(other.contracts)])) {
      if (first.contracts[module] !== other.contracts[module]) {
        fields.add(`contracts.${module}`)
      }
    }
  }
  return [...fields].sort()
}

/** The services and contracts the manifest pins: those every selected bundle shares, taken from the first. */
export function pinnedProvenance(draft: Draft): Pick<DraftBundle, 'services' | 'contracts'> | null {
  const first = selectedBundles(draft)[0]
  return first ? { services: first.services, contracts: first.contracts } : null
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

/** Where a problem with the selected bundles as a whole is reported. */
export const BUNDLES_FIELD = 'bundles'

function parsedParameters(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text.trim() || '{}')
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/** The path a refusal of one override is reported at, so it leads to the row holding it. */
export function overridePath(index: number, key: string): string {
  return `variants[${index}].overrides.${key}`
}

/** A typed override value as a number, or `undefined` when it is not one. */
export function overrideNumber(value: string): number | undefined {
  const trimmed = value.trim()
  const parsed = trimmed === '' ? Number.NaN : Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

/**
 * Problems found before sending: the parts of a draft that cannot be turned into a manifest at all, and, once the
 * lane bundles are known (`lanes` not `null`), a selected bundle no lane serves as selected. `rulesDigests` holds, by
 * variant, the effective rules digest the service computed for a variant that has overrides.
 */
export function localProblems(draft: Draft, lanes: readonly LaneBundle[] | null, rulesDigests: readonly (string | undefined)[] = []): FieldErrors {
  const problems: Record<string, string> = {}
  draft.variants.forEach((variant, index) => {
    if (variant.bundle === null) {
      problems[`variants[${index}]`] = 'Choose the bundle this variant runs.'
    } else if (lanes !== null && !isServed(variant.bundle, lanes)) {
      problems[`variants[${index}]`] = 'No configured lane serves this bundle as selected. Choose the bundle again.'
    }
    variant.overrides.forEach((override) => {
      if (overrideNumber(override.value) === undefined) {
        problems[overridePath(index, override.key)] = 'Enter a number.'
      }
    })
    if (variant.overrides.length > 0 && variant.bundle !== null && rulesDigests[index] === undefined) {
      problems[`variants[${index}].overrides`] = 'The rules of this variant are not known yet.'
    }
  })
  const incomparable = incomparableFields(draft)
  if (incomparable.length > 0) {
    problems[BUNDLES_FIELD] = `The selected bundles are not comparable: they differ in ${incomparable.join(', ')}.`
  }
  draft.policies.forEach((policy, index) => {
    if (parsedParameters(policy.parameters) === null) {
      problems[`policies[${index}].parameters`] = 'Parameters must be a JSON object, for example {}.'
    }
  })
  return problems
}

function artifactOf(artifact: DraftArtifact) {
  return {
    digest: artifact.digest,
    ...(artifact.mediaType ? { mediaType: artifact.mediaType } : {}),
    ...(artifact.name ? { name: artifact.name } : {}),
  }
}

/**
 * The manifest a draft stands for. Values that are not yet valid are carried as they are (a missing number is
 * left out, a non-number becomes `null`) so the service, which owns validation, reports them per field.
 */
export function draftToManifest(draft: Draft, rulesDigests: readonly (string | undefined)[] = []): ExperimentManifest {
  const pinned = pinnedProvenance(draft)
  const service = (name: ServiceName) => ({ ...(pinned?.services[name] ?? { imageDigest: '', sourceRevision: '' }) })
  const manifest = {
    schemaVersion: 1,
    name: draft.name.trim(),
    variants: draft.variants.map((variant, index) => ({
      label: variant.label.trim(),
      rulesArtifact: artifactOf(variant.bundle?.rulesArtifact ?? { digest: '' }),
      contentArtifact: artifactOf(variant.bundle?.contentArtifact ?? { digest: '' }),
      // With overrides the digest is the one the service computed for the rules they make, never that of the baseline.
      effectiveRulesDigest: (variant.overrides.length > 0 ? rulesDigests[index] : undefined) ?? variant.bundle?.effectiveRulesDigest ?? '',
      effectiveContentDigest: variant.bundle?.effectiveContentDigest ?? '',
      ...(variant.overrides.length > 0
        ? {
            ...(variant.parameterSpaceVersion === null ? {} : { parameterSpaceVersion: variant.parameterSpaceVersion }),
            overrides: Object.fromEntries(variant.overrides.map((override) => [override.key, overrideNumber(override.value) ?? null])),
          }
        : {}),
    })),
    services: { gameService: service('gameService'), timelineService: service('timelineService'), readService: service('readService') },
    contracts: { ...(pinned?.contracts ?? {}) },
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
      parameterSpaceVersion: variant.parameterSpaceVersion ?? null,
      overrides: Object.entries(variant.overrides ?? {}).map(([key, value]) => ({ key, value: String(value) })),
      bundle: {
        rulesArtifact: { ...variant.rulesArtifact },
        contentArtifact: { ...variant.contentArtifact },
        effectiveRulesDigest: variant.effectiveRulesDigest,
        effectiveContentDigest: variant.effectiveContentDigest,
        services: {
          gameService: { ...manifest.services.gameService },
          timelineService: { ...manifest.services.timelineService },
          readService: { ...manifest.services.readService },
        },
        contracts: { ...manifest.contracts },
      },
    })),
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

function templateLabel(index: number, variantCount: number): string {
  if (index === 0) {
    return 'baseline'
  }
  return variantCount === 2 ? 'candidate' : `candidate-${index}`
}

/**
 * The draft a template proposes: its defaults and one variant slot per variant it needs, each slot holding the next
 * distinct bundle while there are bundles left. The name stays the designer's.
 */
export function applyTemplate(draft: Draft, template: ExperimentTemplate, bundles: readonly LaneBundle[]): Draft {
  const { defaults } = template
  return {
    name: draft.name,
    variants: Array.from({ length: template.variantCount }, (_, index) => {
      const bundle = bundles[index]
      return { ...emptyVariant(), label: templateLabel(index, template.variantCount), bundle: bundle ? bundleFromLane(bundle) : null }
    }),
    policies: defaults.policies.map((policy) => ({
      id: policy.id,
      version: policy.version,
      artifactDigest: policy.artifactDigest,
      parameters: JSON.stringify(policy.parameters),
      seats: policy.seatAssignment.map((seat) => String(seat.seatIndex)),
    })),
    seeds: defaults.seeds.join('\n'),
    playerCounts: [...defaults.playerCounts],
    factionSets: defaults.factionSets.map((set) => [...set]),
    concurrency: String(defaults.concurrency),
    caseWallTimeoutSeconds: String(defaults.caseWallTimeoutSeconds),
    maxRejectedCandidatesPerWindow: String(defaults.maxRejectedCandidatesPerWindow),
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

const STORAGE_KEY = 'workbench.experiment-draft.v2'

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
    const draft = { ...emptyDraft(), ...stored.draft }
    // A draft kept before variants could carry overrides has none.
    draft.variants = draft.variants.map((variant) => ({ ...emptyVariant(), ...variant }))
    return { draft, basedOn: stored.basedOn ?? null }
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
