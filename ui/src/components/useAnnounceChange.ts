import { useEffect, useRef } from 'react'
import { useAnnounce } from './announceContext'

/** Announces `message(value)` whenever `value` changes after the first render; an undefined value announces nothing. */
export function useAnnounceChange<T>(value: T | undefined, message: (value: T) => string): void {
  const announce = useAnnounce()
  const previous = useRef(value)
  useEffect(() => {
    if (value !== undefined && previous.current !== value) {
      announce(message(value))
    }
    previous.current = value
  }, [value, message, announce])
}
