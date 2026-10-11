/**
 * Authenticated HTTP for the designer application. Every call carries the current access token as a Bearer
 * credential; when the session cannot supply one, or the service answers 401, the caller is told so it can
 * send the designer back to sign-in rather than retry with a stale identity.
 */

export type AccessTokenProvider = () => Promise<string | undefined>

export class SessionExpiredError extends Error {
  constructor() {
    super('The session expired. Sign in again to continue.')
    this.name = 'SessionExpiredError'
  }
}

export function createAuthenticatedFetch(getAccessToken: AccessTokenProvider, onUnauthorized: () => void) {
  return async function authenticatedFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
    let accessToken: string | undefined
    try {
      accessToken = await getAccessToken()
    } catch {
      accessToken = undefined
    }
    if (!accessToken) {
      onUnauthorized()
      throw new SessionExpiredError()
    }
    const headers = new Headers(init.headers)
    headers.set('Authorization', `Bearer ${accessToken}`)
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json')
    }
    const response = await fetch(input, { ...init, headers })
    if (response.status === 401) {
      onUnauthorized()
    }
    return response
  }
}
