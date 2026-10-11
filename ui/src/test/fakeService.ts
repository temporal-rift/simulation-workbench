import type { Case, Experiment, ExperimentManifest, Run } from '../api/generated'
import { IDS, caseDetail, experiment, policyCatalog, preview, run } from './fixtures'

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
