import type { FidelityCaseResult, FidelityCheckState, FidelityVariantLabel } from '../api/generated'

export const FIDELITY_STATE_LABELS: Readonly<Record<FidelityCheckState, string>> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  PASSED: 'Passed',
  FAILED: 'Failed',
}

export const FIDELITY_RESULT_LABELS: Readonly<Record<FidelityCaseResult, string>> = {
  PENDING: 'Pending',
  MATCH: 'Match',
  DIVERGED: 'Diverged',
  UNREPRODUCIBLE: 'Cannot be reproduced',
}

export const FIDELITY_RESULTS = Object.keys(FIDELITY_RESULT_LABELS) as FidelityCaseResult[]
export const FIDELITY_VARIANTS: readonly FidelityVariantLabel[] = ['baseline', 'low', 'high']

/** A check that can still end: the service allows one at a time, and a new one waits for it. */
export function isOpenCheck(state: FidelityCheckState | undefined): boolean {
  return state === 'QUEUED' || state === 'RUNNING'
}

const UINT64_MAX = 18446744073709551615n

/** Whether the text is a decimal unsigned 64-bit integer without leading zeros, as the service accepts a seed. */
export function isSeed(text: string): boolean {
  return /^(0|[1-9][0-9]*)$/.test(text) && BigInt(text) <= UINT64_MAX
}
