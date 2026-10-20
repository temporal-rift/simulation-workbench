import { useId } from 'react'
import type { ParameterSpace } from '../api/generated'
import { Field } from '../components/Field'
import { errorAt, overridePath, type DraftOverride, type DraftVariant, type FieldErrors } from './draft'
import type { EditDraft } from './ExperimentForm'
import { describeParameter, gridValues } from './parameterSpace'

interface Props {
  readonly index: number
  readonly variant: DraftVariant
  /** The parameter space overrides are chosen from. */
  readonly space: ParameterSpace
  readonly edit: EditDraft
  readonly errors: FieldErrors
  /** The effective rules digest of this variant, or undefined while the service is working it out. */
  readonly rulesDigest: string | undefined
}

/**
 * The parameters a variant changes from its baseline. Each row names a parameter of the space and a value; the value
 * is typed, with the values on the step grid offered, and the service says on the row why a value is refused.
 */
export function VariantOverrides({ index, variant, space, edit, errors, rulesDigest }: Props) {
  const used = new Set(variant.overrides.map((override) => override.key))
  const unused = space.parameters.filter((parameter) => !used.has(parameter.key))
  const groupError = errorAt(errors, `variants[${index}].overrides`) ?? errorAt(errors, `variants[${index}].parameterSpaceVersion`)

  function add() {
    const parameter = unused[0]
    if (parameter) {
      edit((d) => {
        const target = d.variants[index]!
        target.parameterSpaceVersion = space.version
        target.overrides.push({ key: parameter.key, value: String(parameter.baseline) })
      })
    }
  }

  return (
    <fieldset className="overrides">
      <legend>Overrides of variant {index + 1}</legend>
      {variant.overrides.length === 0 ? (
        <p className="hint">No overrides: this variant runs the baseline rules of its bundle.</p>
      ) : (
        variant.overrides.map((override, row) => (
          <OverrideRow
            key={override.key}
            index={index}
            row={row}
            override={override}
            space={space}
            unused={unused.map((parameter) => parameter.key)}
            edit={edit}
            error={errorAt(errors, overridePath(index, override.key))}
          />
        ))
      )}
      {groupError && <p className="field-error">{groupError}</p>}
      <button type="button" onClick={add} disabled={unused.length === 0}>
        Add override
      </button>
      <dl className="facts">
        <div>
          <dt>Rules digest of variant {index + 1}</dt>
          <dd>{rulesDigest ? <code>{rulesDigest}</code> : 'Checking the rules…'}</dd>
        </div>
      </dl>
    </fieldset>
  )
}

interface RowProps {
  readonly index: number
  readonly row: number
  readonly override: DraftOverride
  readonly space: ParameterSpace
  readonly unused: readonly string[]
  readonly edit: EditDraft
  readonly error: string | undefined
}

function OverrideRow({ index, row, override, space, unused, edit, error }: RowProps) {
  const listId = useId()
  const parameter = space.parameters.find((candidate) => candidate.key === override.key)
  const choices = [override.key, ...unused].sort()
  return (
    <div className="override-row">
      <Field label={`Parameter ${row + 1}`}>
        {(props) => (
          <select
            {...props}
            value={override.key}
            onChange={(event) => {
              const chosen = space.parameters.find((candidate) => candidate.key === event.target.value)
              if (chosen) {
                edit((d) => {
                  d.variants[index]!.overrides[row] = { key: chosen.key, value: String(chosen.baseline) }
                })
              }
            }}
          >
            {choices.map((key) => (
              <option key={key} value={key}>
                {key}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label={`Value of parameter ${row + 1}`} error={error} hint={parameter ? describeParameter(parameter) : undefined}>
        {(props) => (
          <>
            <input
              {...props}
              type="text"
              inputMode="decimal"
              autoComplete="off"
              spellCheck={false}
              list={listId}
              value={override.value}
              onChange={(event) => edit((d) => void (d.variants[index]!.overrides[row]!.value = event.target.value))}
            />
            <datalist id={listId}>{parameter && gridValues(parameter).map((value) => <option key={value} value={value} />)}</datalist>
          </>
        )}
      </Field>
      <button
        type="button"
        onClick={() =>
          edit((d) => {
            const target = d.variants[index]!
            target.overrides.splice(row, 1)
            if (target.overrides.length === 0) {
              target.parameterSpaceVersion = null
            }
          })
        }
      >
        Remove override {row + 1} of variant {index + 1}
      </button>
    </div>
  )
}
