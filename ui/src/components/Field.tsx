import { useId, type ReactNode } from 'react'

interface FieldProps {
  readonly label: string
  readonly error?: string | undefined
  readonly hint?: string | undefined
  readonly children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby': string | undefined }) => ReactNode
}

/** A labelled input whose problem and hint are tied to it for assistive technology. */
export function Field({ label, error, hint, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={error ? 'field field-invalid' : 'field'}>
      <label htmlFor={id}>{label}</label>
      {children({ id, 'aria-invalid': Boolean(error), 'aria-describedby': describedBy })}
      {hint && (
        <p id={hintId} className="hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
    </div>
  )
}

interface TextFieldProps {
  readonly label: string
  readonly value: string
  readonly onChange: (value: string) => void
  readonly error?: string | undefined
  readonly hint?: string | undefined
  readonly multiline?: boolean
  readonly rows?: number
  readonly inputMode?: 'numeric' | 'text'
  readonly monospace?: boolean
}

export function TextField({ label, value, onChange, error, hint, multiline, rows = 4, inputMode, monospace }: TextFieldProps) {
  return (
    <Field label={label} error={error} hint={hint}>
      {(props) =>
        multiline ? (
          <textarea {...props} className={monospace ? 'mono' : undefined} rows={rows} value={value} onChange={(event) => onChange(event.target.value)} />
        ) : (
          <input
            {...props}
            className={monospace ? 'mono' : undefined}
            type="text"
            inputMode={inputMode}
            autoComplete="off"
            spellCheck={false}
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
        )
      }
    </Field>
  )
}
