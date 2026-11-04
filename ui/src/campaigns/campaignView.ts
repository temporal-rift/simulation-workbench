import type { CampaignLogDecision, CampaignLogReason, CampaignState } from '../api/generated'

export const CAMPAIGN_STATE_LABELS: Readonly<Record<CampaignState, string>> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  PAUSED: 'Paused',
  SEARCH_COMPLETED: 'Search completed',
  CONFIRMING: 'Confirming',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  FAILED: 'Failed',
}

export const DECISION_LABELS: Readonly<Record<CampaignLogDecision, string>> = {
  GENERATED: 'Generated',
  KEPT: 'Kept',
  DISCARDED: 'Discarded',
  SHORT_LISTED: 'Short-listed',
}

export const REASON_LABELS: Readonly<Record<CampaignLogReason, string>> = {
  GATE_FAILS: 'A gate line fails',
  DOMINATED: 'Dominated',
  NOT_IN_TOP_FRACTION: 'Outside the top fraction',
  BUDGET_EXHAUSTED: 'Budget exhausted',
}

/** A campaign that is still moving by itself or can be moved by the designer; its page keeps reading it. */
export function isLive(state: CampaignState | undefined): boolean {
  return state === 'QUEUED' || state === 'RUNNING' || state === 'PAUSED'
}

export function isPausable(state: CampaignState): boolean {
  return state === 'QUEUED' || state === 'RUNNING'
}

export function isResumable(state: CampaignState): boolean {
  return state === 'PAUSED'
}

export function isCancellable(state: CampaignState): boolean {
  return state !== 'COMPLETED' && state !== 'CANCELLED' && state !== 'FAILED'
}

/** Parallelism may change until the campaign is over. */
export function isTuneable(state: CampaignState): boolean {
  return isCancellable(state)
}

/** Seconds as hours, minutes and seconds, leaving out the units that are zero from the front. */
export function formatSeconds(total: number): string {
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  if (hours > 0) {
    return `${hours} h ${String(minutes).padStart(2, '0')} min ${String(seconds).padStart(2, '0')} s`
  }
  if (minutes > 0) {
    return `${minutes} min ${String(seconds).padStart(2, '0')} s`
  }
  return `${seconds} s`
}

export function remainingSeconds(budgetSeconds: number, spentSeconds: number): number {
  return Math.max(0, budgetSeconds - spentSeconds)
}

const UINT64_MAX = 18446744073709551615n

/** Whether the text is a decimal unsigned 64-bit integer without leading zeros, as the service accepts a master seed. */
export function isMasterSeed(text: string): boolean {
  return /^(0|[1-9][0-9]*)$/.test(text) && BigInt(text) <= UINT64_MAX
}

export function isWholeNumber(text: string, minimum: number): boolean {
  return /^[0-9]+$/.test(text) && Number(text) >= minimum
}
