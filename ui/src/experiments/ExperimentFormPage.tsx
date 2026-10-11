import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiProblemError } from '../api/client'
import type { ExperimentPreview } from '../api/generated'
import { describeFailure } from '../api/problems'
import { freezeManifest, policiesQuery, previewManifest, useApiClient } from '../api/queries'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { ScopedButton } from '../components/ScopedButton'
import {
  clearDraft,
  draftToManifest,
  emptyDraft,
  fingerprint,
  loadDraft,
  localProblems,
  saveDraft,
  type Draft,
  type FieldErrors,
} from './draft'
import { ExperimentForm, type EditDraft } from './ExperimentForm'
import { PreviewPanel } from './PreviewPanel'

interface Submission {
  /** The draft the problem was reported for; it applies only while the draft is unchanged. */
  readonly fingerprint: string
  readonly error: unknown
  readonly action: string
}

interface Taken {
  readonly fingerprint: string
  readonly preview: ExperimentPreview
}

export function ExperimentFormPage() {
  const client = useApiClient()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  const restored = useMemo(() => loadDraft(), [])
  const [draft, setDraft] = useState<Draft>(restored?.draft ?? emptyDraft())
  const [basedOn] = useState(restored?.basedOn ?? null)
  const [taken, setTaken] = useState<Taken | null>(null)
  const [submission, setSubmission] = useState<Submission | null>(null)
  const [confirming, setConfirming] = useState<{ readonly idempotencyKey: string } | null>(null)
  const [blocked, setBlocked] = useState<string | null>(null)
  const summary = useRef<HTMLDivElement>(null)
  const confirmation = useRef<HTMLDivElement>(null)

  const catalog = useQuery(policiesQuery(client))
  const manifest = useMemo(() => draftToManifest(draft), [draft])
  const current = useMemo(() => fingerprint(manifest), [manifest])
  const local = useMemo(() => localProblems(draft), [draft])

  useEffect(() => {
    saveDraft({ draft, basedOn })
  }, [draft, basedOn])

  const edit: EditDraft = (mutate) => {
    setDraft((previous) => {
      const next = structuredClone(previous)
      mutate(next)
      return next
    })
    setConfirming(null)
  }

  const preview = useMutation({
    mutationFn: ({ offset }: { readonly offset: number }) => previewManifest(client, manifest, offset),
    onSuccess: (data) => {
      setSubmission(null)
      setTaken({ fingerprint: current, preview: data })
      announce(`Preview ready: ${data.caseCount} cases. Nothing is frozen.`)
    },
    onError: (error) => setSubmission({ fingerprint: current, error, action: 'preview the matrix' }),
  })

  const freeze = useMutation({
    mutationFn: ({ idempotencyKey }: { readonly idempotencyKey: string }) => freezeManifest(client, manifest, idempotencyKey),
    onSuccess: async (experiment) => {
      clearDraft()
      await queryClient.invalidateQueries({ queryKey: ['experiments'] })
      announce('Experiment frozen.')
      await navigate({ to: '/experiments/$experimentId', params: { experimentId: experiment.experimentId } })
    },
    onError: (error) => {
      setConfirming(null)
      setSubmission({ fingerprint: current, error, action: 'freeze the experiment' })
    },
  })

  const problem = submission && submission.fingerprint === current ? submission : null
  const errors = useMemo<FieldErrors>(() => {
    const fromService = problem?.error instanceof ApiProblemError && problem.error.field ? { [problem.error.field]: problem.error.message } : {}
    return { ...fromService, ...local }
  }, [problem, local])

  const upToDate = taken !== null && taken.fingerprint === current
  const hasLocalProblems = Object.keys(local).length > 0
  const blockedNow = blocked === current && hasLocalProblems

  useEffect(() => {
    if (problem || blockedNow) {
      summary.current?.focus()
    }
  }, [problem, blockedNow])

  const confirmingNow = confirming !== null
  useEffect(() => {
    // The button that opened the confirmation is gone, so focus moves to what replaced it.
    if (confirmingNow) {
      confirmation.current?.focus()
    }
  }, [confirmingNow])

  function requestPreview(offset: number) {
    if (hasLocalProblems) {
      setSubmission(null)
      setBlocked(current)
      return
    }
    setBlocked(null)
    preview.mutate({ offset })
  }

  function confirmFreeze() {
    if (freeze.isPending || confirming === null) {
      return
    }
    freeze.mutate({ idempotencyKey: confirming.idempotencyKey })
  }

  return (
    <>
      <h1>New experiment</h1>
      {basedOn && (
        <p className="notice">
          Started from the frozen experiment “{basedOn.name}”. That experiment is unchanged; freezing this draft creates a new one.
        </p>
      )}
      {restored && <p role="status">Your unsent draft was restored.</p>}
      <div ref={summary} tabIndex={-1} className="summary">
        {problem && <ErrorNotice message={describeFailure(problem.error, { action: problem.action, requiredScope: WRITE_SCOPE })} />}
        {blockedNow && <ErrorNotice message="Fix the highlighted fields before previewing the matrix." />}
      </div>
      {catalog.isError && <ErrorNotice message={describeFailure(catalog.error, { action: 'load the bot policy catalog' })} onRetry={() => void catalog.refetch()} />}
      <ExperimentForm draft={draft} edit={edit} errors={errors} catalog={catalog.data?.items ?? []} />
      <div className="actions">
        <ScopedButton scope={WRITE_SCOPE} aria-busy={preview.isPending} onAction={() => requestPreview(0)}>
          Preview matrix
        </ScopedButton>
        <button
          type="button"
          onClick={() => {
            clearDraft()
            setDraft(emptyDraft())
            setTaken(null)
            setSubmission(null)
            setConfirming(null)
          }}
        >
          Discard draft
        </button>
      </div>
      {taken && <PreviewPanel preview={taken.preview} outdated={!upToDate} busy={preview.isPending} onPage={(offset) => requestPreview(offset)} />}
      <section aria-labelledby="freeze-heading">
        <h2 id="freeze-heading">Freeze</h2>
        {confirming === null || !taken || !upToDate ? (
          <>
            <ScopedButton scope={WRITE_SCOPE} disabled={!upToDate} onAction={() => setConfirming({ idempotencyKey: crypto.randomUUID() })}>
              Freeze this experiment…
            </ScopedButton>
            {!upToDate && <p className="hint">Preview the current draft first; only a previewed matrix can be frozen.</p>}
          </>
        ) : (
          <div ref={confirmation} tabIndex={-1} role="group" aria-labelledby="confirm-heading" className="notice">
            <h3 id="confirm-heading">Confirm freeze</h3>
            <p>
              Freezing makes this configuration immutable: a later change needs a new experiment. It will freeze exactly {taken.preview.caseCount}{' '}
              {taken.preview.caseCount === 1 ? 'case' : 'cases'} with the digest:
            </p>
            <p>
              <code>{taken.preview.manifestDigest}</code>
            </p>
            <ScopedButton scope={WRITE_SCOPE} aria-busy={freeze.isPending} onAction={confirmFreeze}>
              Confirm and freeze
            </ScopedButton>
            <button type="button" onClick={() => setConfirming(null)}>
              Cancel
            </button>
          </div>
        )}
      </section>
    </>
  )
}
