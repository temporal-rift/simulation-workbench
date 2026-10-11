import { useId, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { useCan } from '../auth/sessionContext'

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> {
  /** The scope the action needs. Without it the action is shown unavailable and naming the scope, never hidden. */
  readonly scope: string
  readonly onAction: () => void
  readonly children: ReactNode
}

/**
 * An action button gated on a granted scope. It stays focusable when unavailable so that keyboard and screen
 * reader users meet the reason; the service still decides on every request.
 */
export function ScopedButton({ scope, onAction, children, ...rest }: Props) {
  const allowed = useCan(scope)
  const reasonId = useId()
  return (
    <span className="scoped-action">
      <button
        {...rest}
        type="button"
        aria-disabled={allowed ? rest.disabled : true}
        aria-describedby={allowed ? undefined : reasonId}
        onClick={() => {
          if (allowed && !rest.disabled) {
            onAction()
          }
        }}
      >
        {children}
      </button>
      {!allowed && (
        <span id={reasonId} className="hint">
          Unavailable: requires the {scope} scope.
        </span>
      )}
    </span>
  )
}
