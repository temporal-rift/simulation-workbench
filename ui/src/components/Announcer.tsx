import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { AnnouncerContext, type Announce } from './announceContext'

/** Announces changes the designer is not looking at (progress, results of actions) through one polite live region. */
export function AnnouncerProvider({ children }: { readonly children: ReactNode }) {
  const [message, setMessage] = useState('')
  const announce = useCallback<Announce>((next) => {
    // Clearing first makes an identical repeated message announce again.
    setMessage('')
    setTimeout(() => setMessage(next), 0)
  }, [])
  const value = useMemo(() => announce, [announce])
  return (
    <AnnouncerContext.Provider value={value}>
      {children}
      <div role="status" aria-live="polite" aria-atomic="true" className="visually-hidden">
        {message}
      </div>
    </AnnouncerContext.Provider>
  )
}
