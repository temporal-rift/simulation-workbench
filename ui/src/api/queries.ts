import { queryOptions } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useSession } from '../auth/sessionContext'
import { ApiProblemError, ServiceUnavailableError, apiClientFor, unwrap } from './client'
import {
  cancelRun,
  createComparison,
  createExperiment,
  getCase,
  getCaseReplay,
  getComparison,
  getExperiment,
  getRun,
  getRunEvaluation,
  getRunReport,
  listBalanceCriteria,
  listCases,
  listComparisons,
  listExperimentTemplates,
  listExperiments,
  listLaneBundles,
  listPolicies,
  listRuns,
  previewExperiment,
  reproduceCase,
  resumeRun,
  startRun,
  type CaseState,
  type ComparisonRequest,
  type EvaluationMode,
  type ExperimentManifest,
  type RunState,
} from './generated'
import type { Client } from './generated/client'

export const PAGE_SIZE = 20

/** How often a run that is not finished is read again, while the tab is visible. */
export const RUN_POLL_MS = 3000

export function useApiClient(): Client {
  const { fetch } = useSession()
  return useMemo(() => apiClientFor(fetch), [fetch])
}

export function isTerminalRunState(state: RunState | undefined): boolean {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED'
}

export const experimentsQuery = (client: Client, offset: number) =>
  queryOptions({
    queryKey: ['experiments', { offset }],
    queryFn: () => unwrap(() => listExperiments({ client, query: { limit: PAGE_SIZE, offset } })),
  })

export const experimentQuery = (client: Client, experimentId: string) =>
  queryOptions({
    queryKey: ['experiment', experimentId],
    queryFn: () => unwrap(() => getExperiment({ client, path: { experimentId } })),
    // A frozen experiment never changes.
    staleTime: Infinity,
  })

export const policiesQuery = (client: Client) =>
  queryOptions({
    queryKey: ['policies'],
    queryFn: () => unwrap(() => listPolicies({ client })),
    staleTime: 5 * 60_000,
  })

/** The largest page a discovery list serves. */
const MAX_PAGE = 500

/** Every item of a paged list, read page by page until its total is covered, so a choice is never silently missing. */
async function wholeList<T>(readPage: (offset: number) => Promise<{ readonly items: T[]; readonly total: number }>) {
  const items: T[] = []
  let total = 0
  do {
    const page = await readPage(items.length)
    items.push(...page.items)
    total = page.total
    if (page.items.length === 0) {
      break
    }
  } while (items.length < total)
  return { items, total }
}

export const laneBundlesQuery = (client: Client) =>
  queryOptions({
    queryKey: ['lane-bundles'],
    queryFn: () => wholeList((offset) => unwrap(() => listLaneBundles({ client, query: { limit: MAX_PAGE, offset } }))),
    staleTime: 60_000,
  })

export const experimentTemplatesQuery = (client: Client) =>
  queryOptions({
    queryKey: ['experiment-templates'],
    queryFn: () => wholeList((offset) => unwrap(() => listExperimentTemplates({ client, query: { limit: MAX_PAGE, offset } }))),
    staleTime: 60_000,
  })

export interface RunFilters {
  readonly experimentId?: string
  readonly state?: RunState
}

export const runsQuery = (client: Client, filters: RunFilters, offset: number) =>
  queryOptions({
    queryKey: ['runs', filters, { offset }],
    queryFn: () =>
      unwrap(() => listRuns({ client, query: { ...filters, limit: PAGE_SIZE, offset } })),
  })

export const runQuery = (client: Client, runId: string) =>
  queryOptions({
    queryKey: ['run', runId],
    queryFn: () => unwrap(() => getRun({ client, path: { runId } })),
    // Polling pauses while the tab is hidden (the default) and stops once the run can no longer change.
    refetchInterval: (query) => (isTerminalRunState(query.state.data?.state) ? false : RUN_POLL_MS),
  })

export interface CaseFilters {
  readonly state?: CaseState
  readonly variantLabel?: string
}

export const runCasesQuery = (client: Client, runId: string, filters: CaseFilters, offset: number, active: boolean) =>
  queryOptions({
    queryKey: ['run-cases', runId, filters, { offset }],
    queryFn: () => unwrap(() => listCases({ client, path: { runId }, query: { ...filters, limit: PAGE_SIZE, offset } })),
    refetchInterval: active ? RUN_POLL_MS : false,
  })

export const caseQuery = (client: Client, runId: string, caseId: string) =>
  queryOptions({
    queryKey: ['case', runId, caseId],
    queryFn: () => unwrap(() => getCase({ client, path: { runId, caseId } })),
    // A page of failed cases reads one case each; a refocus or remount should not repeat them all.
    staleTime: 30_000,
  })

export const previewManifest = (client: Client, manifest: ExperimentManifest, offset: number) =>
  unwrap(() => previewExperiment({ client, body: manifest, query: { limit: PAGE_SIZE, offset } }))

export const freezeManifest = (client: Client, manifest: ExperimentManifest, idempotencyKey: string) =>
  unwrap(() => createExperiment({ client, body: manifest, headers: { 'Idempotency-Key': idempotencyKey } }))

export const launchRun = (client: Client, experimentId: string, idempotencyKey: string) =>
  unwrap(() => startRun({ client, path: { experimentId }, body: {}, headers: { 'Idempotency-Key': idempotencyKey } }))

export const cancelRunCommand = (client: Client, runId: string, idempotencyKey: string) =>
  unwrap(() => cancelRun({ client, path: { runId }, headers: { 'Idempotency-Key': idempotencyKey } }))

export const resumeRunCommand = (client: Client, runId: string, idempotencyKey: string) =>
  unwrap(() => resumeRun({ client, path: { runId }, headers: { 'Idempotency-Key': idempotencyKey } }))

/** A report changes only while its run does, so it is read again on the run's cadence. */
export const reportQuery = (client: Client, runId: string, active: boolean) =>
  queryOptions({
    queryKey: ['report', runId],
    queryFn: () => unwrap(() => getRunReport({ client, path: { runId } })),
    refetchInterval: active ? RUN_POLL_MS : false,
  })

export const comparisonsQuery = (client: Client, offset: number) =>
  queryOptions({
    queryKey: ['comparisons', { offset }],
    queryFn: () => unwrap(() => listComparisons({ client, query: { limit: PAGE_SIZE, offset } })),
  })

export const comparisonQuery = (client: Client, comparisonId: string) =>
  queryOptions({
    queryKey: ['comparison', comparisonId],
    queryFn: () => unwrap(() => getComparison({ client, path: { comparisonId } })),
  })

export const createComparisonCommand = (client: Client, request: ComparisonRequest, idempotencyKey: string) =>
  unwrap(() => createComparison({ client, body: request, headers: { 'Idempotency-Key': idempotencyKey } }))

/** What a replay request names. A seat is present exactly for a player view, mirroring the contract. */
export type ReplayView = { readonly perspective: 'PLAYER'; readonly seatIndex: number } | { readonly perspective: 'OBSERVER' }

export const REPLAY_PAGE_SIZE = 50

/** Every replay of a case lives under this key, so all of it can be dropped at once. */
export const replayRootKey = (runId: string, caseId: string) => ['replay', runId, caseId] as const

/**
 * One page of a replay. The perspective and seat are part of the key and nothing is retained once the page is
 * off screen, so one perspective's facts are never served to another.
 */
export const replayQuery = (client: Client, runId: string, caseId: string, view: ReplayView, afterStep: number | undefined) =>
  queryOptions({
    queryKey: [...replayRootKey(runId, caseId), view, { afterStep }],
    queryFn: () =>
      unwrap(() =>
        getCaseReplay({
          client,
          path: { runId, caseId },
          query: {
            perspective: view.perspective,
            ...(view.perspective === 'PLAYER' ? { seatIndex: view.seatIndex } : {}),
            afterStep,
            limit: REPLAY_PAGE_SIZE,
          },
        }),
      ),
    gcTime: 0,
    staleTime: Infinity,
  })

/** Repeating the call with the same key is how a reproduction is read again: there is no polling endpoint. */
export const reproduceCommand = (client: Client, runId: string, caseId: string, idempotencyKey: string) =>
  unwrap(() => reproduceCase({ client, path: { runId, caseId }, headers: { 'Idempotency-Key': idempotencyKey } }))

/** The bytes the service returns for one representation, kept exactly as received for a download. */
export async function fetchExport(fetchFn: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>, path: string, accept: string): Promise<Blob> {
  const response = await fetchFn(new URL(path, window.location.origin).href, { method: 'GET', headers: { Accept: accept }, cache: 'no-store' })
  if (!response.ok) {
    throw await problemOf(response)
  }
  return response.blob()
}

/** A refused export keeps its problem details, so a missing scope or a missing resource is explained as such. */
async function problemOf(response: Response): Promise<Error> {
  try {
    const problem: unknown = await response.json()
    const fields = typeof problem === 'object' && problem !== null ? (problem as Record<string, unknown>) : {}
    const text = (value: unknown) => (typeof value === 'string' && value.length > 0 ? value : null)
    return new ApiProblemError(response.status, text(fields['code']), text(fields['detail']) ?? '', text(fields['field']))
  } catch {
    return new ServiceUnavailableError('unexpected-response')
  }
}

/** The criterion versions the workbench serves; they are configuration and change only with a release. */
export const balanceCriteriaQuery = (client: Client) =>
  queryOptions({
    queryKey: ['balance-criteria'],
    queryFn: () => unwrap(() => listBalanceCriteria({ client })),
    staleTime: 5 * 60_000,
  })

/** An evaluation is derived on every read from the run's cases, so it is read again when the selection changes. */
export const evaluationQuery = (client: Client, runId: string, variantLabel: string, criterionVersion: number, mode: EvaluationMode) =>
  queryOptions({
    queryKey: ['evaluation', runId, variantLabel, criterionVersion, mode],
    queryFn: () => unwrap(() => getRunEvaluation({ client, path: { runId }, query: { variantLabel, criterionVersion, mode } })),
  })
