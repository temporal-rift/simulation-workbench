import { createContext, useContext } from 'react'

export type AuthenticatedFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

/** What every page under a signed-in designer can rely on. */
export interface SignedInSession {
  readonly fetch: AuthenticatedFetch
  readonly scopes: ReadonlySet<string>
  readonly signOut: () => void
}

const SessionContext = createContext<SignedInSession | null>(null)

/** Provides an already-established session; the sign-in gate uses it, and so do tests. */
export const SessionProvider = SessionContext.Provider

export function useSession(): SignedInSession {
  const session = useContext(SessionContext)
  if (!session) {
    throw new Error('useSession must be used inside a signed-in session.')
  }
  return session
}

export function useCan(scope: string): boolean {
  return useSession().scopes.has(scope)
}
