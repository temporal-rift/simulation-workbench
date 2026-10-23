import { useCallback, useRef } from 'react'

export interface IntentKeys {
  /** The idempotency key of one intent: the same until cleared, so a retry after a lost response repeats the command. */
  readonly keyFor: (scope: string) => string
  /** Ends the intent, so the next request under this scope is a new command. */
  readonly clear: (scope: string) => void
}

export function useIntentKeys(): IntentKeys {
  const keys = useRef(new Map<string, string>())
  const keyFor = useCallback((scope: string) => {
    let key = keys.current.get(scope)
    if (key === undefined) {
      key = crypto.randomUUID()
      keys.current.set(scope, key)
    }
    return key
  }, [])
  const clear = useCallback((scope: string) => {
    keys.current.delete(scope)
  }, [])
  return { keyFor, clear }
}
