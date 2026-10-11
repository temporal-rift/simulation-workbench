export const READ_SCOPE = 'simulation:read'
export const WRITE_SCOPE = 'simulation:write'
export const OBSERVE_SCOPE = 'simulation:observe'

export type Scope = typeof READ_SCOPE | typeof WRITE_SCOPE | typeof OBSERVE_SCOPE

function payloadOf(accessToken: string): Record<string, unknown> {
  const segment = accessToken.split('.')[1]
  if (!segment) {
    return {}
  }
  try {
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')), (c) => c.charCodeAt(0))
    const claims: unknown = JSON.parse(new TextDecoder().decode(bytes))
    return typeof claims === 'object' && claims !== null ? (claims as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function words(value: unknown): string[] {
  if (typeof value === 'string') {
    return value.split(/\s+/).filter(Boolean)
  }
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

/**
 * The scopes granted to an access token. Issuers differ in where they put them (`scope` as a space-separated
 * string, `scp` as a list or a string), so both are read. This only decides what the UI offers: the service
 * decides what is allowed.
 */
export function grantedScopes(accessToken: string, tokenResponseScope?: string): ReadonlySet<string> {
  const claims = payloadOf(accessToken)
  const fromClaims = [...words(claims['scope']), ...words(claims['scp'])]
  return new Set(fromClaims.length > 0 ? fromClaims : words(tokenResponseScope))
}
