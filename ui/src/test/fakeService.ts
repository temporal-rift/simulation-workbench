import type { Case, Comparison, Experiment, ExperimentManifest, Report, Reproduction, ReproductionState, Run } from '../api/generated'
import { IDS, REPORT_CSV, SEAT_STEPS, caseDetail, comparison, eventStep, experiment, policyCatalog, preview, report, run } from './fixtures'

export interface RecordedRequest {
  readonly method: string
  readonly path: string
  readonly query: URLSearchParams
  readonly headers: Headers
  readonly body: unknown
}

type Handler = (request: RecordedRequest, match: RegExpExecArray) => Response | Promise<Response>

interface Route {
  readonly method: string
  readonly pattern: RegExp
  readonly handler: Handler
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } })
}

export function problem(status: number, code: string, detail: string, field?: string): Response {
  return json({ type: 'about:blank', title: 'Problem', status, detail, code, ...(field ? { field } : {}) }, status)
}

/** A stand-in for the workbench service that holds state and honours idempotency keys, so flows can run end to end. */
export class FakeService {
  readonly requests: RecordedRequest[] = []
  readonly experiments = new Map<string, Experiment>()
  readonly runs = new Map<string, Run>()
  readonly cases = new Map<string, Case[]>()
  readonly comparisons = new Map<string, Comparison>()
  readonly reports = new Map<string, Report>()
  /** The states successive reproduction requests report, the last one repeating. */
  reproductionScript: ReproductionState[] = ['QUEUED', 'MATCH']
  reproductionDivergence: Reproduction['firstDivergence'] = null
  private reproductionCalls = 0
  unreachable = false
  private readonly answers = new Map<string, Response>()
  private readonly overrides: Route[] = []
  private created = 0

  constructor() {
    this.experiments.set(IDS.experiment, experiment())
    this.runs.set(IDS.run, run())
  }

  /** Answers the next request to one operation with `response`, then behaves normally again. */
  failOnce(method: string, pattern: RegExp, response: () => Response): void {
    const route: Route = {
      method,
      pattern,
      handler: () => {
        this.overrides.splice(this.overrides.indexOf(route), 1)
        return response()
      },
    }
    this.overrides.unshift(route)
  }

  /** Replaces the answer to one operation for the rest of the test. */
  override(method: string, pattern: RegExp, handler: Handler): void {
    this.overrides.unshift({ method, pattern, handler })
  }

  count(method: string, pattern: RegExp): number {
    return this.requests.filter((request) => request.method === method && pattern.test(request.path)).length
  }

  readonly fetch = async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    if (this.unreachable) {
      throw new TypeError('Failed to fetch')
    }
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
    const method = (init.method ?? 'GET').toUpperCase()
    const text = typeof init.body === 'string' ? init.body : ''
    const request: RecordedRequest = {
      method,
      path: url.pathname,
      query: url.searchParams,
      headers: new Headers(init.headers),
      body: text ? (JSON.parse(text) as unknown) : undefined,
    }
    this.requests.push(request)
    for (const route of [...this.overrides, ...this.routes()]) {
      const match = route.pattern.exec(url.pathname)
      if (route.method === method && match) {
        return route.handler(request, match)
      }
    }
    return problem(404, 'RESOURCE_NOT_FOUND', 'No such resource.')
  }

  /** The first answer to an idempotency key is the answer to every repeat of it. */
  private once(request: RecordedRequest, create: () => Response): Response {
    const key = `${request.method} ${request.path} ${request.headers.get('Idempotency-Key') ?? ''}`
    const known = this.answers.get(key)
    if (known) {
      return known.clone()
    }
    const answer = create()
    this.answers.set(key, answer.clone())
    return answer
  }

  private routes(): Route[] {
    const get = (pattern: RegExp, handler: Handler): Route => ({ method: 'GET', pattern, handler })
    const post = (pattern: RegExp, handler: Handler): Route => ({ method: 'POST', pattern, handler })
    return [
      get(/^\/api\/v1\/policies$/, () => json({ items: policyCatalog })),
      post(/^\/api\/v1\/experiment-previews$/, () => json(preview())),
      post(/^\/api\/v1\/experiments$/, (request) =>
        this.once(request, () => {
          this.created += 1
          const created = experiment({
            experimentId: `00000000-0000-4000-8000-${String(this.created).padStart(12, '0')}`,
            manifest: request.body as ExperimentManifest,
          })
          this.experiments.set(created.experimentId, created)
          return json(created, 201)
        }),
      ),
      get(/^\/api\/v1\/experiments$/, () =>
        json({
          items: [...this.experiments.values()].map(({ experimentId, manifest, manifestDigest, createdAt }) => ({
            experimentId,
            name: manifest.name,
            manifestDigest,
            createdAt,
          })),
          total: this.experiments.size,
        }),
      ),
      get(/^\/api\/v1\/experiments\/([^/]+)$/, (_request, match) => {
        const found = this.experiments.get(match[1]!)
        return found ? json(found) : problem(404, 'RESOURCE_NOT_FOUND', 'Unknown experiment.')
      }),
      post(/^\/api\/v1\/experiments\/([^/]+)\/runs$/, (request, match) =>
        this.once(request, () => {
          if (!this.experiments.has(match[1]!)) {
            return problem(404, 'RESOURCE_NOT_FOUND', 'Unknown experiment.')
          }
          const started = run({ runId: `10000000-0000-4000-8000-${String(this.runs.size + 1).padStart(12, '0')}`, experimentId: match[1]!, state: 'QUEUED' })
          this.runs.set(started.runId, started)
          return json(started, 202)
        }),
      ),
      get(/^\/api\/v1\/runs$/, (request) => {
        const experimentId = request.query.get('experimentId')
        const state = request.query.get('state')
        const items = [...this.runs.values()].filter((candidate) => (!experimentId || candidate.experimentId === experimentId) && (!state || candidate.state === state))
        return json({ items, total: items.length })
      }),
      get(/^\/api\/v1\/runs\/([^/]+)$/, (_request, match) => {
        const found = this.runs.get(match[1]!)
        return found ? json(found) : problem(404, 'RESOURCE_NOT_FOUND', 'Unknown run.')
      }),
      post(/^\/api\/v1\/runs\/([^/]+)\/resume$/, (request, match) =>
        this.once(request, () => {
          const found = this.runs.get(match[1]!)
          if (!found) {
            return problem(404, 'RESOURCE_NOT_FOUND', 'Unknown run.')
          }
          if (found.state !== 'INTERRUPTED') {
            return problem(409, 'INVALID_RUN_STATE', `Run is ${found.state}; resume is legal only from INTERRUPTED.`)
          }
          const resumed = { ...found, state: 'RUNNING' as const }
          this.runs.set(resumed.runId, resumed)
          return json(resumed, 202)
        }),
      ),
      post(/^\/api\/v1\/runs\/([^/]+)\/cancel$/, (request, match) =>
        this.once(request, () => {
          const found = this.runs.get(match[1]!)
          if (!found) {
            return problem(404, 'RESOURCE_NOT_FOUND', 'Unknown run.')
          }
          const cancelling = { ...found, state: 'CANCELLING' as const }
          this.runs.set(cancelling.runId, cancelling)
          return json(cancelling, 202)
        }),
      ),
      get(/^\/api\/v1\/runs\/([^/]+)\/cases$/, (request, match) => {
        const state = request.query.get('state')
        const all = this.cases.get(match[1]!) ?? []
        const items = all
          .filter((candidate) => !state || candidate.state === state)
          .map((candidate) => ({
            caseId: candidate.caseId,
            caseKey: candidate.caseKey,
            runId: candidate.runId,
            variantLabel: candidate.variantLabel,
            seed: candidate.seed,
            playerCount: candidate.playerCount,
            state: candidate.state,
            endReason: candidate.result?.endReason ?? null,
          }))
        return json({ items, total: items.length })
      }),
      get(/^\/api\/v1\/runs\/([^/]+)\/report$/, (request, match) => {
        const found = this.reports.get(match[1]!) ?? (this.runs.has(match[1]!) ? report({ runId: match[1]! }) : undefined)
        if (!found) {
          return problem(404, 'RESOURCE_NOT_FOUND', 'Unknown run.')
        }
        return request.headers.get('Accept') === 'text/csv'
          ? new Response(REPORT_CSV, { status: 200, headers: { 'Content-Type': 'text/csv; charset=UTF-8' } })
          : json(found)
      }),
      post(/^\/api\/v1\/comparisons$/, (request) =>
        this.once(request, () => {
          const asked = request.body as Comparison
          const created = comparison({ baseline: asked.baseline, candidate: asked.candidate })
          this.comparisons.set(created.comparisonId, created)
          return json(created, 201)
        }),
      ),
      get(/^\/api\/v1\/comparisons$/, () => {
        const items = [...this.comparisons.values()].map(({ comparisonId, baseline, candidate, analysisVersion, analysisSeed }) => ({
          comparisonId,
          baseline,
          candidate,
          analysisVersion,
          analysisSeed,
          createdAt: '2026-10-11T10:00:00Z',
        }))
        return json({ items, total: items.length })
      }),
      get(/^\/api\/v1\/comparisons\/([^/]+)$/, (_request, match) => {
        const found = this.comparisons.get(match[1]!)
        return found ? json(found) : problem(404, 'RESOURCE_NOT_FOUND', 'Unknown comparison.')
      }),
      get(/^\/api\/v1\/runs\/([^/]+)\/cases\/([^/]+)\/replay$/, (request) => {
        const afterStep = request.query.get('afterStep')
        if (request.query.get('perspective') === 'OBSERVER') {
          const seatSteps = Object.values(SEAT_STEPS)
            .flat()
            .sort((a, b) => a.step - b.step)
          const steps = [...seatSteps, eventStep(6, 'game.events', 0), eventStep(7, 'timeline.events', 0), eventStep(8, 'game.events', 1)]
          return json({ caseId: IDS.caseA, manifestDigest: '7'.repeat(64), perspective: 'OBSERVER', steps, nextStep: null })
        }
        // Pages of one step, so paging is visible: a seat's steps keep their case-wide numbers across pages.
        const all = SEAT_STEPS[Number(request.query.get('seatIndex'))] ?? []
        const steps = all.filter((step) => afterStep === null || step.step > Number(afterStep)).slice(0, 1)
        const last = steps[steps.length - 1]
        const nextStep = last && last !== all[all.length - 1] ? last.step : null
        return json({ caseId: IDS.caseA, manifestDigest: '7'.repeat(64), perspective: 'PLAYER', steps, nextStep })
      }),
      post(/^\/api\/v1\/runs\/([^/]+)\/cases\/([^/]+)\/reproductions$/, () => {
        const state = this.reproductionScript[Math.min(this.reproductionCalls, this.reproductionScript.length - 1)]!
        this.reproductionCalls += 1
        return json(
          {
            reproductionId: '5e6f7081-92a3-4b4c-8d5e-6f708192a3b4',
            attemptId: IDS.other,
            state,
            firstDivergence: state === 'DIVERGED' ? this.reproductionDivergence : null,
          },
          202,
        )
      }),
      get(/^\/api\/v1\/runs\/([^/]+)\/cases\/([^/]+)$/, (_request, match) => {
        const found = (this.cases.get(match[1]!) ?? []).find((candidate) => candidate.caseId === match[2]!)
        return found ? json(found) : problem(404, 'RESOURCE_NOT_FOUND', 'Unknown case.')
      }),
    ]
  }

  /** Adds a failed case to the default run, as a run with failures would hold. */
  addFailedCase(overrides: Partial<Case> = {}): Case {
    const added = caseDetail(overrides)
    this.cases.set(added.runId, [...(this.cases.get(added.runId) ?? []), added])
    return added
  }
}
