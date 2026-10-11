import { describe, expect, it } from 'vitest'
import { resolveAppConfig } from './appConfig'

describe('resolveAppConfig', () => {
  it('accepts a complete configuration', () => {
    expect(resolveAppConfig({ oidcIssuerUrl: 'https://issuer.example.test/', oidcClientId: 'workbench', oidcAudience: 'simulation' })).toEqual({
      ok: true,
      config: { oidcIssuerUrl: 'https://issuer.example.test/', oidcClientId: 'workbench', oidcAudience: 'simulation' },
    })
  })

  it('treats an empty audience as none', () => {
    const result = resolveAppConfig({ oidcIssuerUrl: 'https://issuer.example.test/', oidcClientId: 'workbench', oidcAudience: ' ' })

    expect(result.ok && result.config.oidcAudience).toBeUndefined()
  })

  it('names each missing or malformed value', () => {
    expect(resolveAppConfig({ oidcIssuerUrl: 'not a url' })).toEqual({
      ok: false,
      errors: ['oidcIssuerUrl must be an absolute http(s) URL.', 'oidcClientId is not configured.'],
    })
  })

  it('reads a broken configuration as nothing configured', () => {
    expect(resolveAppConfig(undefined)).toEqual({
      ok: false,
      errors: ['oidcIssuerUrl is not configured.', 'oidcClientId is not configured.'],
    })
  })
})
