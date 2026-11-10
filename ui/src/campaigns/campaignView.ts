import type { CampaignEvaluation, CampaignLogDecision, CampaignLogReason, CampaignState } from '../api/generated'

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
  GATES_NOT_MET: 'Still fails a gate when the search ended',
  BUDGET_EXHAUSTED: 'Budget exhausted',
}

/** A log entry's evaluation in words: its verdict, its score and, when it has one, how far it misses the gates. */
export function evaluationText(evaluation: CampaignEvaluation): string {
  const score = evaluation.score === null ? '' : `, score ${evaluation.score}`
  const gateMiss = evaluation.gateMiss === null || evaluation.gateMiss === undefined ? '' : `, gate miss ${evaluation.gateMiss}`
  return `${evaluation.verdict}${score}${gateMiss}`
}

/** A campaign that is still moving by itself or can be moved by the designer; its page keeps reading it. */
export function isLive(state: CampaignState | undefined): boolean {
  return state === 'QUEUED' || state === 'RUNNING' || state === 'PAUSED' || state === 'CONFIRMING'
}

/** Confirmation on fresh seeds can start only once the search has produced its short list. */
export function isConfirmable(state: CampaignState): boolean {
  return state === 'SEARCH_COMPLETED'
}

/** The name a confirmation run goes by: the literal subject `baseline`, or a candidate id. */
export function describeSubject(subject: string): string {
  return subject === 'baseline' ? 'Baseline' : `Candidate ${subject}`
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

/** Parallelism matters only while the search or the confirmation still has games to play. */
export function isTuneable(state: CampaignState): boolean {
  return state === 'QUEUED' || state === 'RUNNING' || state === 'PAUSED' || state === 'CONFIRMING'
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

const UINT64_MAX = 18446744073709551615n

/** Whether the text is a decimal unsigned 64-bit integer without leading zeros, as the service accepts a master seed. */
export function isMasterSeed(text: string): boolean {
  return /^(0|[1-9][0-9]*)$/.test(text) && BigInt(text) <= UINT64_MAX
}

export function isWholeNumber(text: string, minimum: number): boolean {
  return /^[0-9]+$/.test(text) && Number(text) >= minimum
}
