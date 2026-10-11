import { describe, expect, it } from 'vitest'
import { grantedScopes } from './scopes'

function token(claims: Record<string, unknown>): string {
  const encode = (value: unknown) => btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${encode({ alg: 'none' })}.${encode(claims)}.signature`
}

describe('grantedScopes', () => {
  it('reads a space-separated scope claim', () => {
    expect(grantedScopes(token({ scope: 'openid simulation:read  simulation:write' }))).toEqual(new Set(['openid', 'simulation:read', 'simulation:write']))
  })

  it('reads a scp list', () => {
    expect(grantedScopes(token({ scp: ['simulation:read', 'simulation:observe'] }))).toEqual(new Set(['simulation:read', 'simulation:observe']))
  })

  it('reads a scp string', () => {
    expect(grantedScopes(token({ scp: 'simulation:read' }))).toEqual(new Set(['simulation:read']))
  })

  it('combines both claims when an issuer sends both', () => {
    expect(grantedScopes(token({ scope: 'simulation:read', scp: ['simulation:write'] }))).toEqual(new Set(['simulation:read', 'simulation:write']))
  })

  it('falls back to the token response when the access token is opaque', () => {
    expect(grantedScopes('opaque-token', 'simulation:read simulation:write')).toEqual(new Set(['simulation:read', 'simulation:write']))
  })

  it('grants nothing when no scope is stated', () => {
    expect(grantedScopes(token({ sub: 'someone' })).size).toBe(0)
    expect(grantedScopes('not.a.jwt').size).toBe(0)
  })
})
