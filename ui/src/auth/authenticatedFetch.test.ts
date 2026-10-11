import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionExpiredError, createAuthenticatedFetch } from './authenticatedFetch'

afterEach(() => vi.unstubAllGlobals())

describe('createAuthenticatedFetch', () => {
  it('sends the current token as a bearer credential', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}'))
    vi.stubGlobal('fetch', fetchMock)

    await createAuthenticatedFetch(async () => 'token-1', () => undefined)('/api/v1/runs')

    const headers = fetchMock.mock.calls[0]![1].headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('reports an unauthorized answer so the designer can sign in again', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })))
    const onUnauthorized = vi.fn()

    const response = await createAuthenticatedFetch(async () => 'token-1', onUnauthorized)('/api/v1/runs')

    expect(response.status).toBe(401)
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not call the service without a token', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const onUnauthorized = vi.fn()

    await expect(createAuthenticatedFetch(async () => undefined, onUnauthorized)('/api/v1/runs')).rejects.toBeInstanceOf(SessionExpiredError)
    await expect(createAuthenticatedFetch(() => Promise.reject(new Error('renewal failed')), onUnauthorized)('/api/v1/runs')).rejects.toBeInstanceOf(
      SessionExpiredError,
    )

    expect(fetchMock).not.toHaveBeenCalled()
    expect(onUnauthorized).toHaveBeenCalledTimes(2)
  })
})
