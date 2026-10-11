import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { AppConfig } from '../config/appConfig'
import { AUTH_CALLBACK_PATH, HOME_PATH } from '../routing/paths'

/**
 * Builds the `UserManager` that owns the whole OIDC protocol against the configured issuer (discovery,
 * authorization code with PKCE, token exchange, ID-token validation, renewal).
 *
 * Tokens are held in memory only and never written to browser storage: a reload signs in again, which the
 * issuer's own session makes seamless. Only the short-lived login transaction lives in `sessionStorage`.
 *
 * The redirect URIs are fixed rather than derived from the current page so an issuer can register them by
 * exact match; the page to return to travels in the sign-in `state`.
 */
export function createOidcClient(config: AppConfig): UserManager {
  return new UserManager({
    authority: config.oidcIssuerUrl,
    client_id: config.oidcClientId,
    redirect_uri: `${window.location.origin}${AUTH_CALLBACK_PATH}`,
    post_logout_redirect_uri: `${window.location.origin}${HOME_PATH}`,
    response_type: 'code',
    scope: 'openid profile offline_access simulation:read simulation:write simulation:observe',
    ...(config.oidcAudience ? { extraQueryParams: { audience: config.oidcAudience } } : {}),
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
  })
}
