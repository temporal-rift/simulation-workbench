import type { EnumsFaction, PolicyBundle } from '../api/generated'
import { Field, TextField } from '../components/Field'
import {
  FACTIONS,
  PLAYER_COUNTS,
  SERVICE_NAMES,
  emptyPolicy,
  emptyVariant,
  errorAt,
  errorWithin,
  type Draft,
  type FieldErrors,
} from './draft'

export type EditDraft = (mutate: (draft: Draft) => void) => void

interface Props {
  readonly draft: Draft
  readonly edit: EditDraft
  readonly errors: FieldErrors
  readonly catalog: readonly PolicyBundle[]
}

const DIGEST_HINT = 'Lowercase SHA-256 hex digest (64 characters).'

export function ExperimentForm({ draft, edit, errors, catalog }: Props) {
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
      <VariantsSection draft={draft} edit={edit} errors={errors} />
      <PoliciesSection draft={draft} edit={edit} errors={errors} catalog={catalog} />
      <MatrixSection draft={draft} edit={edit} errors={errors} />
      <BoundsSection draft={draft} edit={edit} errors={errors} />
      <ProvenanceSection draft={draft} edit={edit} errors={errors} />
    </div>
  )
}

type SectionProps = Omit<Props, 'catalog'>

function VariantsSection({ draft, edit, errors }: SectionProps) {
  return (
    <section aria-labelledby="variants-heading">
      <h2 id="variants-heading">Variants</h2>
      {errorAt(errors, 'variants') && <p className="field-error">{errorAt(errors, 'variants')}</p>}
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
            <TextField
              label="Rules artifact digest"
              monospace
              value={variant.rulesArtifact.digest}
              error={errorAt(errors, `${path}.rulesArtifact`) ?? errorAt(errors, `${path}.rulesArtifact.digest`)}
              hint={DIGEST_HINT}
              onChange={(value) => edit((d) => void (d.variants[index]!.rulesArtifact.digest = value))}
            />
            <TextField
              label="Content artifact digest"
              monospace
              value={variant.contentArtifact.digest}
              error={errorAt(errors, `${path}.contentArtifact`) ?? errorAt(errors, `${path}.contentArtifact.digest`)}
              hint={DIGEST_HINT}
              onChange={(value) => edit((d) => void (d.variants[index]!.contentArtifact.digest = value))}
            />
            <TextField
              label="Effective rules digest"
              monospace
              value={variant.effectiveRulesDigest}
              error={errorAt(errors, `${path}.effectiveRulesDigest`)}
              hint={DIGEST_HINT}
              onChange={(value) => edit((d) => void (d.variants[index]!.effectiveRulesDigest = value))}
            />
            <TextField
              label="Effective content digest"
              monospace
              value={variant.effectiveContentDigest}
              error={errorAt(errors, `${path}.effectiveContentDigest`)}
              hint={DIGEST_HINT}
              onChange={(value) => edit((d) => void (d.variants[index]!.effectiveContentDigest = value))}
            />
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
            <div role="group" aria-labelledby={`${path}-seats`}>
              <p id={`${path}-seats`}>Seats played by this policy</p>
              {errorAt(errors, `${path}.seatAssignment`) && <p className="field-error">{errorAt(errors, `${path}.seatAssignment`)}</p>}
              {policy.seats.map((seat, seatIndex) => (
                <div key={seatIndex} className="inline">
                  <TextField
                    label={`Seat ${seatIndex + 1} index`}
                    inputMode="numeric"
                    value={seat}
                    error={errorAt(errors, `${path}.seatAssignment[${seatIndex}].seatIndex`) ?? errorAt(errors, `${path}.seatAssignment[${seatIndex}]`)}
                    onChange={(value) => edit((d) => void (d.policies[index]!.seats[seatIndex] = value))}
                  />
                  {policy.seats.length > 1 && (
                    <button
                      type="button"
                      onClick={() => edit((d) => void d.policies[index]!.seats.splice(seatIndex, 1))}
                    >
                      Remove seat {seatIndex + 1}
                    </button>
                  )}
                </div>
              ))}
              <button type="button" onClick={() => edit((d) => void d.policies[index]!.seats.push(''))}>
                Add seat to policy {index + 1}
              </button>
            </div>
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

function ProvenanceSection({ draft, edit, errors }: SectionProps) {
  return (
    <section aria-labelledby="provenance-heading">
      <h2 id="provenance-heading">Service provenance and contracts</h2>
      {SERVICE_NAMES.map((name) => (
        <fieldset key={name}>
          <legend>{name}</legend>
          <TextField
            label={`${name} image digest`}
            monospace
            value={draft.services[name].imageDigest}
            error={errorAt(errors, `services.${name}`) ?? errorAt(errors, `services.${name}.imageDigest`)}
            hint="sha256: followed by 64 lowercase hex characters."
            onChange={(value) => edit((d) => void (d.services[name].imageDigest = value))}
          />
          <TextField
            label={`${name} source revision`}
            monospace
            value={draft.services[name].sourceRevision}
            error={errorAt(errors, `services.${name}.sourceRevision`)}
            hint="7 to 64 lowercase hex characters."
            onChange={(value) => edit((d) => void (d.services[name].sourceRevision = value))}
          />
        </fieldset>
      ))}
      <fieldset>
        <legend>Pinned contract versions</legend>
        {errorAt(errors, 'contracts') && <p className="field-error">{errorAt(errors, 'contracts')}</p>}
        {draft.contracts.map((row, index) => (
          <div key={index} className="inline">
            <TextField
              label={`Contract ${index + 1} module`}
              value={row.module}
              onChange={(value) => edit((d) => void (d.contracts[index]!.module = value))}
            />
            <TextField
              label={`Contract ${index + 1} version`}
              value={row.version}
              error={row.module.trim() ? errorAt(errors, `contracts.${row.module.trim()}`) : undefined}
              hint="Exact version such as 3.1.0."
              onChange={(value) => edit((d) => void (d.contracts[index]!.version = value))}
            />
            {draft.contracts.length > 1 && (
              <button type="button" onClick={() => edit((d) => void d.contracts.splice(index, 1))}>
                Remove contract {index + 1}
              </button>
            )}
          </div>
        ))}
        <button type="button" onClick={() => edit((d) => void d.contracts.push({ module: '', version: '' }))}>
          Add contract
        </button>
      </fieldset>
    </section>
  )
}
