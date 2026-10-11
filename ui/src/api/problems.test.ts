import { describe, expect, it } from 'vitest'
import { SessionExpiredError } from '../auth/authenticatedFetch'
import { ApiProblemError, ServiceUnavailableError } from './client'
import { describeFailure, isUnreachable } from './problems'

const context = { action: 'freeze the experiment', requiredScope: 'simulation:write' }
const problem = (status: number, code: string | null, detail = 'the service said so') => new ApiProblemError(status, code, detail, null)

describe('describeFailure', () => {
  it.each([
    ['INVALID_EXPERIMENT', 400, 'Could not freeze the experiment: the experiment configuration was refused. the service said so'],
    ['MANIFEST_MISMATCH', 409, 'Could not freeze the experiment: a reference in the configuration does not match what the service holds. the service said so'],
    ['EXPERIMENT_IMMUTABLE', 409, 'Could not freeze the experiment: a frozen experiment cannot be changed. Start a new draft from it instead.'],
    ['IDEMPOTENCY_CONFLICT', 409, 'Could not freeze the experiment: this request was already submitted with different content. Reload and try again.'],
    ['INVALID_RUN_STATE', 409, "Could not freeze the experiment: the run's current state does not allow it."],
    ['RESOURCE_NOT_FOUND', 404, 'Could not freeze the experiment: it does not exist or is not visible to you.'],
    ['UNAUTHORIZED', 401, 'Your session expired. Sign in again to continue.'],
    ['INSUFFICIENT_SCOPE', 403, 'You cannot freeze the experiment: your session lacks the simulation:write scope.'],
    ['INVALID_COMPARISON', 400, 'Could not freeze the experiment: the request was refused. the service said so'],
    ['INVALID_REPLAY_PERSPECTIVE', 400, 'Could not freeze the experiment: the request was refused. the service said so'],
    ['INCOMPARABLE_RUNS', 422, 'Could not freeze the experiment: the request was refused. the service said so'],
  ])('words %s plainly', (code, status, expected) => {
    expect(describeFailure(problem(status, code), context)).toBe(expected)
  })

  it('names no scope when the action does not declare one', () => {
    expect(describeFailure(problem(403, 'INSUFFICIENT_SCOPE'), { action: 'read it' })).toBe('You cannot read it: your session lacks a required scope.')
  })

  it('never repeats a server error as the explanation', () => {
    const message = describeFailure(problem(500, null, 'java.lang.NullPointerException at Foo.bar(Foo.java:12)'), context)

    expect(message).toBe('The service had a problem. Try again in a moment.')
  })

  it('words an unreachable service as retryable', () => {
    expect(describeFailure(new ServiceUnavailableError('unreachable'), context)).toBe(
      'Could not freeze the experiment: the service could not be reached. Check the connection and retry.',
    )
    expect(isUnreachable(new ServiceUnavailableError('unreachable'))).toBe(true)
    expect(isUnreachable(new ServiceUnavailableError('unexpected-response'))).toBe(false)
  })

  it('words an unexpected answer and an expired session', () => {
    expect(describeFailure(new ServiceUnavailableError('unexpected-response'), context)).toContain('answered something unexpected')
    expect(describeFailure(new SessionExpiredError(), context)).toBe('Your session expired. Sign in again to continue.')
  })

  it('words an unknown failure without exposing it', () => {
    expect(describeFailure(new Error('stack trace here'), context)).toBe('Could not freeze the experiment. Try again.')
  })
})
