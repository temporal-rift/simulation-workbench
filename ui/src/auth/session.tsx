import { useQueryClient } from '@tanstack/react-query'
import type { User, UserManager } from 'oidc-client-ts'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { AppConfig } from '../config/appConfig'
import { AUTH_CALLBACK_PATH, safeReturnPath } from '../routing/paths'
import { createAuthenticatedFetch } from './authenticatedFetch'
import { createOidcClient } from './oidcClient'
import { grantedScopes } from './scopes'
import { SessionProvider, type SignedInSession } from './sessionContext'

type AuthState =
  | { readonly status: 'loading' }
  | { readonly status: 'signed-out'; readonly reason: 'new' | 'expired' }
  | { readonly status: 'failed'; readonly message: string }
  | { readonly status: 'signed-in'; readonly user: User }

// A sign-in callback can be consumed once: React strict mode runs the effect twice, so both share one exchange.
const callbacks = new WeakMap<UserManager, Promise<User | undefined>>()

function completeCallback(manager: UserManager): Promise<User | undefined> {
  let pending = callbacks.get(manager)
  if (!pending) {
    pending = manager.signinCallback()
    callbacks.set(manager, pending)
    const forget = () => callbacks.delete(manager)
    pending.then(forget, forget)
  }
  return pending
}

function isCallback(): boolean {
  if (window.location.pathname !== AUTH_CALLBACK_PATH) {
    return false
  }
  const params = new URLSearchParams(window.location.search)
  return params.has('code') || params.has('error')
}

/**
 * Establishes the designer's session with the configured identity provider before anything private renders,
 * and renders the sign-in screen otherwise. An expired session returns here without touching the unsent
 * draft, which lives in the tab's own session storage.
 */
export function AuthGate({ config, children }: { readonly config: AppConfig; readonly children: ReactNode }) {
  const queryClient = useQueryClient()
  const manager = useMemo(() => createOidcClient(config), [config])
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  useEffect(() => {
    let active = true
    const expire = () => {
      queryClient.clear()
      if (active) {
        setState({ status: 'signed-out', reason: 'expired' })
      }
    }
    async function start() {
      try {
        let user: User | null | undefined
        if (isCallback()) {
          user = await completeCallback(manager)
          window.history.replaceState(null, '', safeReturnPath(user?.state))
        } else {
          user = await manager.getUser()
        }
        if (active) {
          setState(user && !user.expired ? { status: 'signed-in', user } : { status: 'signed-out', reason: 'new' })
        }
      } catch (error) {
        window.history.replaceState(null, '', '/')
        if (active) {
          setState({ status: 'failed', message: error instanceof Error ? error.message : 'Sign-in failed.' })
        }
      }
    }
    void start()
    const renewed = (user: User) => {
      if (active) {
        setState({ status: 'signed-in', user })
      }
    }
    manager.events.addUserLoaded(renewed)
    manager.events.addAccessTokenExpired(expire)
    return () => {
      active = false
      manager.events.removeUserLoaded(renewed)
      manager.events.removeAccessTokenExpired(expire)
    }
  }, [manager, queryClient])

  const signIn = useCallback(() => {
    const returnTo = `${window.location.pathname}${window.location.search}`
    void manager.signinRedirect({ state: safeReturnPath(returnTo) })
  }, [manager])

  const user = state.status === 'signed-in' ? state.user : null
  const session = useMemo<SignedInSession | null>(() => {
    if (!user) {
      return null
    }
    const fetchWithToken = createAuthenticatedFetch(
      async () => {
        const current = await manager.getUser()
        if (current && !current.expired) {
          return current.access_token
        }
        return (await manager.signinSilent())?.access_token
      },
      () => {
        queryClient.clear()
        void manager.removeUser()
        setState({ status: 'signed-out', reason: 'expired' })
      },
    )
    return {
      fetch: fetchWithToken,
      scopes: grantedScopes(user.access_token, user.scope),
      signOut: () => {
        queryClient.clear()
        void manager.signoutRedirect()
      },
    }
  }, [manager, queryClient, user])

  if (session) {
    return <SessionProvider value={session}>{children}</SessionProvider>
  }
  if (state.status === 'loading') {
    return (
      <main className="centered" aria-busy="true">
        <p role="status">Signing in…</p>
      </main>
    )
  }
  return <SignInPanel state={state} onSignIn={signIn} />
}

function SignInPanel({ state, onSignIn }: { readonly state: AuthState; readonly onSignIn: () => void }) {
  return (
    <main className="centered">
      <h1>Temporal Rift Simulation Workbench</h1>
      {state.status === 'signed-out' && state.reason === 'expired' && (
        <p role="alert">Your session expired. Sign in again to continue; any unsent experiment draft is kept.</p>
      )}
      {state.status === 'failed' && (
        <p role="alert">Sign-in did not complete: {state.message} Try again.</p>
      )}
      <button type="button" onClick={onSignIn}>
        Sign in
      </button>
    </main>
  )
}
