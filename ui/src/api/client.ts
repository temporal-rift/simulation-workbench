/**
 * The generated contract client wired to the authenticated fetch. Every response is validated against the
 * contract by the generated Zod schemas; this module only turns the ways a call can fail into errors the
 * pages can explain. Callers branch on the stable problem `code`, never on free-text `detail`.
 */

import { ZodError } from 'zod'
import { SessionExpiredError } from '../auth/authenticatedFetch'
import { createClient, createConfig, type Client } from './generated/client'

export type AuthenticatedFetchFn = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

/** An authoritative rejection: the service answered with a problem. */
export class ApiProblemError extends Error {
  readonly status: number
  readonly code: string | null
  /** The offending manifest value, when the service attributed the refusal to one. */
  readonly field: string | null

  constructor(status: number, code: string | null, detail: string, field: string | null) {
    super(detail)
    this.name = 'ApiProblemError'
    this.status = status
    this.code = code
    this.field = field
  }

  isCode(code: string): boolean {
    return this.code === code
  }
}

/** The service could not be reached, or answered something the contract does not describe. */
export class ServiceUnavailableError extends Error {
  readonly reason: 'unreachable' | 'unexpected-response'

  constructor(reason: 'unreachable' | 'unexpected-response') {
    super(reason === 'unreachable' ? 'The service could not be reached.' : 'The service answered unexpectedly.')
    this.name = 'ServiceUnavailableError'
    this.reason = reason
  }
}

export function isAbortError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { name?: unknown }).name === 'AbortError'
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function classify(error: unknown, response: Response | undefined): unknown {
  if (isAbortError(error) || error instanceof SessionExpiredError) {
    return error
  }
  if (response && !response.ok) {
    const problem = typeof error === 'object' && error !== null ? (error as Record<string, unknown>) : {}
    return new ApiProblemError(response.status, text(problem['code']), text(problem['detail']) ?? '', text(problem['field']))
  }
  if (response || error instanceof ZodError) {
    return new ServiceUnavailableError('unexpected-response')
  }
  return new ServiceUnavailableError('unreachable')
}

function adaptFetch(fetchFn: AuthenticatedFetchFn) {
  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input, init)
    const body = (await request.text()) || undefined
    // Server state is polled and reconciled here: a heuristically cached answer must never stand in for it.
    return fetchFn(request.url, { method: request.method, headers: request.headers, body, signal: request.signal, cache: 'no-store' })
  }
}

/** Creates the contract client for one signed-in session; the service is the page's own origin. */
export function createApiClient(fetchFn: AuthenticatedFetchFn): Client {
  const client = createClient(createConfig({ baseUrl: window.location.origin, fetch: adaptFetch(fetchFn), parseAs: 'json', throwOnError: true }))
  client.interceptors.error.use((error, response) => classify(error, response))
  return client
}

const clients = new WeakMap<AuthenticatedFetchFn, Client>()

/** One client per fetch function, so repeated renders share it. */
export function apiClientFor(fetchFn: AuthenticatedFetchFn): Client {
  let client = clients.get(fetchFn)
  if (!client) {
    client = createApiClient(fetchFn)
    clients.set(fetchFn, client)
  }
  return client
}

/** Unwraps a generated call; its failures are already the typed errors above. */
export async function unwrap<T>(run: () => Promise<{ readonly data?: T }>): Promise<T> {
  const answer = await run()
  if (answer.data === undefined) {
    throw new ServiceUnavailableError('unexpected-response')
  }
  return answer.data
}
