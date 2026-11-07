import type { EnumsFaction, LaneBundle, ParameterSpace, PolicyBundle } from '../api/generated'
import { Field, TextField } from '../components/Field'
import {
  BUNDLES_FIELD,
  FACTIONS,
  PLAYER_COUNTS,
  SERVICE_NAMES,
  bundleFromLane,
  bundleKey,
  bundleLabel,
  SEAT_COUNT,
  emptyPolicy,
  emptyTable,
  policyKey,
  emptyVariant,
  errorAt,
  errorWithin,
  isServed,
  pinnedProvenance,
  type Draft,
  type DraftArtifact,
  type DraftBundle,
  type FieldErrors,
} from './draft'
import { spaceOf } from './parameterSpace'
import { VariantOverrides } from './VariantOverrides'

export type EditDraft = (mutate: (draft: Draft) => void) => void

interface Props {
  readonly draft: Draft
  readonly edit: EditDraft
  readonly errors: FieldErrors
  readonly catalog: readonly PolicyBundle[]
  /** The bundles the configured lanes serve, or `null` while they are not known. */
  readonly bundles: readonly LaneBundle[] | null
  /** The parameter space versions overrides are chosen from, or `null` while they are not known. */
  readonly spaces: readonly ParameterSpace[] | null
  /** The effective rules digest the service computed for each variant that has overrides, by variant. */
  readonly rulesDigests: readonly (string | undefined)[]
}

export function ExperimentForm({ draft, edit, errors, catalog, bundles, spaces, rulesDigests }: Props) {
  return (
    <div className="form-sections">
      <section aria-labelledby="general-heading">
        <h2 id="general-heading">General</h2>
        <TextField
          label="Experiment name"
          value={draft.name}
          error={errorAt(errors, 'name')}
          hint="1 to 120 characters."
          onChange={(value) => edit((d) => void (d.name = value))}
        />
      </section>
      <VariantsSection draft={draft} edit={edit} errors={errors} bundles={bundles} spaces={spaces} rulesDigests={rulesDigests} />
      <ProvenanceSection draft={draft} errors={errors} />
      <PoliciesSection draft={draft} edit={edit} errors={errors} catalog={catalog} />
      <TablesSection draft={draft} edit={edit} errors={errors} />
      <MatrixSection draft={draft} edit={edit} errors={errors} />
      <BoundsSection draft={draft} edit={edit} errors={errors} />
    </div>
  )
}

type SectionProps = Pick<Props, 'draft' | 'edit' | 'errors'>

/** The service reports a refused variant value on the path of that value; all of them belong to the bundle choice. */
function variantBundleError(errors: FieldErrors, path: string): string | undefined {
  return (
    errorAt(errors, path) ??
    errorWithin(errors, `${path}.rulesArtifact`) ??
    errorWithin(errors, `${path}.contentArtifact`) ??
    errorAt(errors, `${path}.effectiveRulesDigest`) ??
    errorAt(errors, `${path}.effectiveContentDigest`)
  )
}

function VariantsSection({ draft, edit, errors, bundles, spaces, rulesDigests }: SectionProps & Pick<Props, 'bundles' | 'spaces' | 'rulesDigests'>) {
  const noLane = bundles !== null && bundles.length === 0
  return (
    <section aria-labelledby="variants-heading">
      <h2 id="variants-heading">Variants</h2>
      {errorAt(errors, 'variants') && <p className="field-error">{errorAt(errors, 'variants')}</p>}
      {noLane && (
        <p className="notice notice-warning">
          No lane is configured, so there is no bundle to run a variant on. An experiment can be frozen once the deployment
          configures a lane.
        </p>
      )}
      {draft.variants.map((variant, index) => {
        const path = `variants[${index}]`
        return (
          <fieldset key={index}>
            <legend>Variant {index + 1}</legend>
            <TextField
              label="Label"
              value={variant.label}
              error={errorAt(errors, `${path}.label`)}
              hint="Unique within the experiment."
              onChange={(value) => edit((d) => void (d.variants[index]!.label = value))}
            />
            {bundles !== null && !noLane && (
              <BundleSelect
                bundles={bundles}
                selected={variant.bundle}
                error={variantBundleError(errors, path)}
                onSelect={(bundle) => edit((d) => void (d.variants[index]!.bundle = bundle))}
              />
            )}
            {variant.bundle && <BundleFacts bundle={variant.bundle} />}
            {variant.bundle && spaces !== null && spaceOf(spaces, variant.parameterSpaceVersion) !== null && (
              <VariantOverrides
                index={index}
                variant={variant}
                space={spaceOf(spaces, variant.parameterSpaceVersion)!}
                edit={edit}
                errors={errors}
                rulesDigest={variant.overrides.length === 0 ? variant.bundle.effectiveRulesDigest : rulesDigests[index]}
              />
            )}
            {draft.variants.length > 1 && (
              <button type="button" onClick={() => edit((d) => void d.variants.splice(index, 1))}>
                Remove variant {index + 1}
              </button>
            )}
          </fieldset>
        )
      })}
      <button type="button" onClick={() => edit((d) => void d.variants.push(emptyVariant()))}>
        Add variant
      </button>
    </section>
  )
}

interface BundleSelectProps {
  readonly bundles: readonly LaneBundle[]
  readonly selected: DraftBundle | null
  readonly error: string | undefined
  readonly onSelect: (bundle: DraftBundle) => void
}

/** Stands for a selected bundle no lane serves as selected, so choosing a served one always changes the value. */
const UNSERVED = 'unserved'

function BundleSelect({ bundles, selected, error, onSelect }: BundleSelectProps) {
  const served = selected === null || isServed(selected, bundles)
  const value = selected === null ? '' : served ? bundleKey(selected) : UNSERVED
  return (
    <Field label="Bundle" error={error} hint="The rules and content this variant runs, as a configured lane serves them.">
      {(props) => (
        <select
          {...props}
          value={value}
          onChange={(event) => {
            const chosen = bundles.find((bundle) => bundleKey(bundle) === event.target.value)
            if (chosen) {
              onSelect(bundleFromLane(chosen))
            }
          }}
        >
          <option value="">Choose a bundle…</option>
          {selected && !served && (
            <option value={UNSERVED}>{bundleLabel(selected)} (not served by any configured lane)</option>
          )}
          {bundles.map((bundle) => (
            <option key={bundleKey(bundle)} value={bundleKey(bundle)}>
              {bundleLabel(bundle)} ({bundle.laneCount} {bundle.laneCount === 1 ? 'lane' : 'lanes'})
            </option>
          ))}
        </select>
      )}
    </Field>
  )
}

function Artifact({ artifact }: { readonly artifact: DraftArtifact }) {
  return (
    <>
      {artifact.name && <>{artifact.name} </>}
      <code>{artifact.digest}</code>
    </>
  )
}

function BundleFacts({ bundle }: { readonly bundle: DraftBundle }) {
  return (
    <dl className="facts">
      <div>
        <dt>Rules artifact</dt>
        <dd>
          <Artifact artifact={bundle.rulesArtifact} />
        </dd>
      </div>
      <div>
        <dt>Content artifact</dt>
        <dd>
          <Artifact artifact={bundle.contentArtifact} />
        </dd>
      </div>
      <div>
        <dt>Effective rules digest</dt>
        <dd>
          <code>{bundle.effectiveRulesDigest}</code>
        </dd>
      </div>
      <div>
        <dt>Effective content digest</dt>
        <dd>
          <code>{bundle.effectiveContentDigest}</code>
        </dd>
      </div>
    </dl>
  )
}

function ProvenanceSection({ draft, errors }: Pick<Props, 'draft' | 'errors'>) {
  const pinned = pinnedProvenance(draft)
  const incomparable = errorAt(errors, BUNDLES_FIELD)
  const refused = errorWithin(errors, 'services') ?? errorWithin(errors, 'contracts')
  return (
    <section aria-labelledby="provenance-heading">
      <h2 id="provenance-heading">Service provenance and contracts</h2>
      {incomparable && <p className="notice notice-error">{incomparable}</p>}
      {refused && <p className="field-error">{refused}</p>}
      {pinned === null ? (
        <p className="hint">Taken from the selected bundles once a variant has one.</p>
      ) : (
        <>
          <p className="hint">Pinned by the selected bundles{incomparable ? ' (shown for the first variant)' : ''}.</p>
          <dl className="facts">
            {SERVICE_NAMES.map((name) => (
              <div key={name}>
                <dt>{name}</dt>
                <dd>
                  <code>{pinned.services[name].imageDigest}</code> at revision <code>{pinned.services[name].sourceRevision}</code>
                </dd>
              </div>
            ))}
            {Object.entries(pinned.contracts).map(([module, version]) => (
              <div key={module}>
                <dt>{module}</dt>
                <dd>{version}</dd>
              </div>
            ))}
          </dl>
        </>
      )}
    </section>
  )
}

function TablesSection({ draft, edit, errors }: SectionProps) {
  const chosen = draft.policies.filter((policy) => policy.id !== '')
  const seatCount = draft.playerCounts.length > 0 ? Math.max(...draft.playerCounts) : SEAT_COUNT
  return (
    <section aria-labelledby="tables-heading">
      <h2 id="tables-heading">Seat tables</h2>
      <p className="hint">A table names the policy that plays each seat; a case with p players uses the first p seats of its table.</p>
      {errorAt(errors, 'tables') && <p className="field-error">{errorAt(errors, 'tables')}</p>}
      {draft.tables.length === 0 && <p>No table yet. Add at least one.</p>}
      {draft.tables.map((table, index) => {
        const path = `tables[${index}]`
        return (
          <fieldset key={index}>
            <legend>Table {index + 1}</legend>
            <TextField
              label="Table id"
              value={table.id}
              error={errorAt(errors, `${path}.id`)}
              onChange={(value) => edit((d) => void (d.tables[index]!.id = value))}
            />
            {errorAt(errors, `${path}.seats`) && <p className="field-error">{errorAt(errors, `${path}.seats`)}</p>}
            {Array.from({ length: seatCount }, (_, seat) => (
              <Field
                key={seat}
                label={`Seat ${seat + 1} policy of table ${index + 1}`}
                error={errorAt(errors, `${path}.seats[${seat}].policyId`) ?? errorAt(errors, `${path}.seats[${seat}]`)}
              >
                {(props) => (
                  <select {...props} value={table.seats[seat] ?? ''} onChange={(event) => edit((d) => void (d.tables[index]!.seats[seat] = event.target.value))}>
                    <option value="">Choose a policy…</option>
                    {table.seats[seat] && !chosen.some((policy) => policyKey(policy) === table.seats[seat]) && (
                      <option value={table.seats[seat]}>{table.seats[seat]} (not among the policies)</option>
                    )}
                    {chosen.map((policy) => (
                      <option key={policyKey(policy)} value={policyKey(policy)}>
                        {policyKey(policy)}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
            ))}
            <button type="button" onClick={() => edit((d) => void d.tables.splice(index, 1))}>
              Remove table {index + 1}
            </button>
          </fieldset>
        )
      })}
      <button type="button" onClick={() => edit((d) => void d.tables.push(emptyTable()))}>
        Add table
      </button>
    </section>
  )
}

function PoliciesSection({ draft, edit, errors, catalog }: SectionProps & { readonly catalog: readonly PolicyBundle[] }) {
  return (
    <section aria-labelledby="policies-heading">
      <h2 id="policies-heading">Bot policies</h2>
      {errorAt(errors, 'policies') && <p className="field-error">{errorAt(errors, 'policies')}</p>}
      {draft.policies.length === 0 && <p>No policy chosen yet. Add at least one.</p>}
      {draft.policies.map((policy, index) => {
        const path = `policies[${index}]`
        const selected = `${policy.id}@${policy.version}`
        const inCatalog = catalog.some((bundle) => `${bundle.id}@${bundle.version}` === selected)
        const bundle = catalog.find((entry) => `${entry.id}@${entry.version}` === selected)
        return (
          <fieldset key={index}>
            <legend>Policy {index + 1}</legend>
            <Field
              label="Policy"
              error={errorAt(errors, `${path}.id`) ?? errorAt(errors, `${path}.version`) ?? errorAt(errors, `${path}.artifactDigest`)}
              hint={bundle?.description}
            >
              {(props) => (
                <select
                  {...props}
                  value={policy.id ? selected : ''}
                  onChange={(event) => {
                    const chosen = catalog.find((entry) => `${entry.id}@${entry.version}` === event.target.value)
                    if (chosen) {
                      edit((d) => {
                        const target = d.policies[index]!
                        target.id = chosen.id
                        target.version = chosen.version
                        target.artifactDigest = chosen.artifactDigest
                      })
                    }
                  }}
                >
                  <option value="">Choose a bot policy…</option>
                  {policy.id && !inCatalog && <option value={selected}>{selected} (not in the catalog)</option>}
                  {catalog.map((entry) => (
                    <option key={`${entry.id}@${entry.version}`} value={`${entry.id}@${entry.version}`}>
                      {entry.id}@{entry.version}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <TextField
              label="Parameters (JSON object)"
              multiline
              monospace
              rows={3}
              value={policy.parameters}
              error={errorAt(errors, `${path}.parameters`)}
              hint={
                bundle && Object.keys(bundle.parameters).length > 0
                  ? `This policy accepts: ${Object.keys(bundle.parameters).join(', ')}.`
                  : 'Use {} when the policy accepts none.'
              }
              onChange={(value) => edit((d) => void (d.policies[index]!.parameters = value))}
            />
            <button type="button" onClick={() => edit((d) => void d.policies.splice(index, 1))}>
              Remove policy {index + 1}
            </button>
          </fieldset>
        )
      })}
      <button type="button" onClick={() => edit((d) => void d.policies.push(emptyPolicy()))}>
        Add policy
      </button>
    </section>
  )
}

function MatrixSection({ draft, edit, errors }: SectionProps) {
  const playerCountsError = errorWithin(errors, 'playerCounts')
  return (
    <section aria-labelledby="matrix-heading">
      <h2 id="matrix-heading">Seeds, player counts and factions</h2>
      <TextField
        label="Seeds"
        multiline
        monospace
        value={draft.seeds}
        error={errorWithin(errors, 'seeds')}
        hint="Whole numbers, one per line or separated by commas."
        onChange={(value) => edit((d) => void (d.seeds = value))}
      />
      <fieldset>
        <legend>Player counts</legend>
        {PLAYER_COUNTS.map((count) => (
          <label key={count} className="check">
            <input
              type="checkbox"
              checked={draft.playerCounts.includes(count)}
              onChange={(event) =>
                edit((d) => {
                  d.playerCounts = event.target.checked ? [...d.playerCounts, count] : d.playerCounts.filter((value) => value !== count)
                })
              }
            />
            {count} players
          </label>
        ))}
        {playerCountsError && <p className="field-error">{playerCountsError}</p>}
      </fieldset>
      <h3>Faction sets</h3>
      <p className="hint">Each set's size must match a selected player count.</p>
      {errorAt(errors, 'factionSets') && <p className="field-error">{errorAt(errors, 'factionSets')}</p>}
      {draft.factionSets.map((set, index) => {
        const setError = errorAt(errors, `factionSets[${index}]`) ?? errorWithin(errors, `factionSets[${index}]`)
        return (
          <fieldset key={index}>
            <legend>
              Faction set {index + 1} ({set.length} selected)
            </legend>
            {FACTIONS.map((faction) => (
              <label key={faction} className="check">
                <input
                  type="checkbox"
                  checked={set.includes(faction)}
                  onChange={(event) =>
                    edit((d) => {
                      const target = d.factionSets[index]!
                      d.factionSets[index] = event.target.checked
                        ? [...target, faction]
                        : target.filter((value: EnumsFaction) => value !== faction)
                    })
                  }
                />
                {faction}
              </label>
            ))}
            {setError && <p className="field-error">{setError}</p>}
            <button type="button" onClick={() => edit((d) => void d.factionSets.splice(index, 1))}>
              Remove faction set {index + 1}
            </button>
          </fieldset>
        )
      })}
      <button type="button" onClick={() => edit((d) => void d.factionSets.push([]))}>
        Add faction set
      </button>
      <p className="hint">Seats rotate cyclically and time is logical; both are fixed by the service.</p>
    </section>
  )
}

function BoundsSection({ draft, edit, errors }: SectionProps) {
  return (
    <section aria-labelledby="bounds-heading">
      <h2 id="bounds-heading">Execution bounds</h2>
      <TextField
        label="Concurrency"
        inputMode="numeric"
        value={draft.concurrency}
        error={errorAt(errors, 'concurrency')}
        hint="How many cases run at once."
        onChange={(value) => edit((d) => void (d.concurrency = value))}
      />
      <TextField
        label="Case wall-time timeout (seconds)"
        inputMode="numeric"
        value={draft.caseWallTimeoutSeconds}
        error={errorAt(errors, 'caseWallTimeoutSeconds')}
        onChange={(value) => edit((d) => void (d.caseWallTimeoutSeconds = value))}
      />
      <TextField
        label="Maximum rejected candidates per decision window"
        inputMode="numeric"
        value={draft.maxRejectedCandidatesPerWindow}
        error={errorAt(errors, 'maxRejectedCandidatesPerWindow')}
        onChange={(value) => edit((d) => void (d.maxRejectedCandidatesPerWindow = value))}
      />
    </section>
  )
}
