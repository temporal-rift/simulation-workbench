import type { CaseCounts, CaseState, RunState } from '../api/generated'

export const RUN_STATE_LABELS: Readonly<Record<RunState, string>> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  INTERRUPTED: 'Interrupted',
  CANCELLING: 'Cancelling',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
}

export const CASE_STATE_LABELS: Readonly<Record<CaseState, string>> = {
  PENDING: 'Pending',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export const RUN_STATES = Object.keys(RUN_STATE_LABELS) as RunState[]
export const CASE_STATES = Object.keys(CASE_STATE_LABELS) as CaseState[]

export function isTerminalRunState(state: RunState | undefined): boolean {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED'
}

/** A run can be stopped until it settles; a run already stopping needs nothing more. */
export function isCancellable(state: RunState): boolean {
  return state === 'QUEUED' || state === 'RUNNING' || state === 'INTERRUPTED'
}

export function isResumable(state: RunState): boolean {
  return state === 'INTERRUPTED'
}

/**
 * Only succeeded cases are results. Whenever some requested case has not succeeded, whatever is shown for
 * the run covers part of it, and says so.
 */
export function partialNote(counts: CaseCounts): string | null {
  if (counts.succeeded >= counts.requested) {
    return null
  }
  const missing = [
    counts.pending > 0 ? `${counts.pending} pending` : null,
    counts.running > 0 ? `${counts.running} running` : null,
    counts.failed > 0 ? `${counts.failed} failed` : null,
    counts.cancelled > 0 ? `${counts.cancelled} cancelled` : null,
  ].filter(Boolean)
  return `Partial: ${counts.succeeded} of ${counts.requested} cases have succeeded (${missing.join(', ')}). Pending, running, failed and cancelled cases are not results.`
}
