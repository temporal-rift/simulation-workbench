import { SessionExpiredError } from '../auth/authenticatedFetch'
import { ApiProblemError, ServiceUnavailableError } from './client'

/** What a failed call is about, so its message can name the action and the scope that action needs. */
export interface FailureContext {
  /** Completes "Could not …", for example "freeze the experiment". */
  readonly action: string
  /** The scope the action needs, named when the service answers that it is missing. */
  readonly requiredScope?: string
}

const GENERIC = 'The service had a problem. Try again in a moment.'

/**
 * Words a failure for the designer: plain, recoverable, and free of stack traces, credentials, storage details
 * or raw payloads. Only a refusal the designer can act on carries the service's own explanation; anything else
 * gets a fixed message.
 */
export function describeFailure(error: unknown, context: FailureContext): string {
  if (error instanceof SessionExpiredError) {
    return 'Your session expired. Sign in again to continue.'
  }
  if (error instanceof ServiceUnavailableError) {
    return error.reason === 'unreachable'
      ? `Could not ${context.action}: the service could not be reached. Check the connection and retry.`
      : `Could not ${context.action}: the service answered something unexpected. Reload and retry.`
  }
  if (!(error instanceof ApiProblemError)) {
    return `Could not ${context.action}. Try again.`
  }
  const explanation = error.message ? ` ${error.message}` : ''
  switch (error.code) {
    case 'INVALID_EXPERIMENT':
      return `Could not ${context.action}: the experiment configuration was refused.${explanation}`
    case 'MANIFEST_MISMATCH':
      return `Could not ${context.action}: a reference in the configuration does not match what the service holds.${explanation}`
    case 'EXPERIMENT_IMMUTABLE':
      return `Could not ${context.action}: a frozen experiment cannot be changed. Start a new draft from it instead.`
    case 'IDEMPOTENCY_CONFLICT':
      return `Could not ${context.action}: this request was already submitted with different content. Reload and try again.`
    case 'INVALID_RUN_STATE':
      return `Could not ${context.action}: the run's current state does not allow it.`
    case 'RESOURCE_NOT_FOUND':
      return `Could not ${context.action}: it does not exist or is not visible to you.`
    case 'UNAUTHORIZED':
      return 'Your session expired. Sign in again to continue.'
    case 'INSUFFICIENT_SCOPE':
      return context.requiredScope
        ? `You cannot ${context.action}: your session lacks the ${context.requiredScope} scope.`
        : `You cannot ${context.action}: your session lacks a required scope.`
    case 'INVALID_COMPARISON':
    case 'INVALID_REPLAY_PERSPECTIVE':
    case 'INCOMPARABLE_RUNS':
      return `Could not ${context.action}: the request was refused.${explanation}`
    default:
      return error.status >= 500 ? GENERIC : `Could not ${context.action}. Try again.`
  }
}

/** True when the failure means the service was not reached, so the data on screen may be out of date. */
export function isUnreachable(error: unknown): boolean {
  return error instanceof ServiceUnavailableError && error.reason === 'unreachable'
}
