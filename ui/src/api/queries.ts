import { queryOptions } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useSession } from '../auth/sessionContext'
import { apiClientFor, unwrap } from './client'
import {
  cancelRun,
  createExperiment,
  getCase,
  getExperiment,
  getRun,
  listCases,
  listExperiments,
  listPolicies,
  listRuns,
  previewExperiment,
  resumeRun,
  startRun,
  type CaseState,
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

export const runCasesQuery = (client: Client, runId: string, state: CaseState | undefined, offset: number, active: boolean) =>
  queryOptions({
    queryKey: ['run-cases', runId, { state }, { offset }],
    queryFn: () => unwrap(() => listCases({ client, path: { runId }, query: { state, limit: PAGE_SIZE, offset } })),
    refetchInterval: active ? RUN_POLL_MS : false,
  })

export const caseQuery = (client: Client, runId: string, caseId: string) =>
  queryOptions({
    queryKey: ['case', runId, caseId],
    queryFn: () => unwrap(() => getCase({ client, path: { runId, caseId } })),
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
