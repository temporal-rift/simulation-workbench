import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { User } from 'oidc-client-ts'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthGate } from './session'
import { useSession } from './sessionContext'

const manager = {
  getUser: vi.fn(),
  signinRedirect: vi.fn(),
  signinCallback: vi.fn(),
  signinSilent: vi.fn(),
  signoutRedirect: vi.fn(),
  removeUser: vi.fn(),
  events: {
    addUserLoaded: vi.fn(),
    removeUserLoaded: vi.fn(),
    addAccessTokenExpired: vi.fn(),
    removeAccessTokenExpired: vi.fn(),
  },
}

vi.mock('./oidcClient', () => ({ createOidcClient: () => manager }))

const config = { oidcIssuerUrl: 'https://issuer.example.test/', oidcClientId: 'workbench', oidcAudience: undefined }

function user(scope: string, state?: string): User {
  return { access_token: 'opaque', scope, expired: false, state } as unknown as User
}

function Probe() {
  const { scopes, fetch } = useSession()
  return (
    <>
      <p>Granted: {[...scopes].join(',')}</p>
      <button type="button" onClick={() => void fetch('/api/v1/runs').catch(() => undefined)}>
        Call the service
      </button>
    </>
  )
}

function renderGate() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthGate config={config}>
        <Probe />
      </AuthGate>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/runs?state=RUNNING')
})

afterEach(() => vi.unstubAllGlobals())

describe('the sign-in gate', () => {
  it('asks a signed-out designer to sign in and returns them to the page they were on', async () => {
    manager.getUser.mockResolvedValue(null)
    renderGate()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: '/runs?state=RUNNING' })
  })

  it('shows the application with the granted scopes once signed in', async () => {
    manager.getUser.mockResolvedValue(user('simulation:read simulation:write'))
    renderGate()

    expect(await screen.findByText('Granted: simulation:read,simulation:write')).toBeInTheDocument()
  })

  it('completes the sign-in on the callback and leaves for the page that asked', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=xyz')
    manager.signinCallback.mockResolvedValue(user('simulation:read', '/runs/abc'))
    renderGate()

    expect(await screen.findByText('Granted: simulation:read')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/runs/abc')
  })

  it('never follows a return target outside the application', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=xyz')
    manager.signinCallback.mockResolvedValue(user('simulation:read', '//evil.example.test/x'))
    renderGate()

    await screen.findByText('Granted: simulation:read')
    expect(window.location.pathname).toBe('/experiments')
  })

  it('explains a sign-in that did not complete', async () => {
    window.history.replaceState(null, '', '/auth/callback?error=access_denied')
    manager.signinCallback.mockRejectedValue(new Error('access denied'))
    renderGate()

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign-in did not complete: access denied Try again.')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('sends the designer back to sign-in, keeping the draft, when the service rejects the session', async () => {
    window.sessionStorage.setItem('workbench.experiment-draft.v1', '{"draft":{}}')
    manager.getUser.mockResolvedValue(user('simulation:read'))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
    vi.spyOn(manager, 'getUser').mockResolvedValue({ ...user('simulation:read'), access_token: 'expired-token' } as User)
    renderGate()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Call the service' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Your session expired. Sign in again to continue; any unsent experiment draft is kept.')
    expect(manager.removeUser).toHaveBeenCalled()
    await waitFor(() => expect(window.sessionStorage.getItem('workbench.experiment-draft.v1')).not.toBeNull())
  })
})
