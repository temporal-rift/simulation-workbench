import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useState } from 'react'
import type { SimulatorFidelity } from '../api/generated'
import { describeFailure } from '../api/problems'
import { PAGE_SIZE, fidelityChecksQuery, simulatorFidelityQuery, startFidelityCheckCommand, useApiClient } from '../api/queries'
import { useIntentKeys } from '../api/useIntentKeys'
import { WRITE_SCOPE } from '../auth/scopes'
import { useAnnounce } from '../components/announceContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatInstant, shortId } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { ScopedButton } from '../components/ScopedButton'
import { FIDELITY_STATE_LABELS, isSeed } from './fidelityView'

export function FidelityPage() {
  return (
    <>
      <h1>Simulator fidelity</h1>
      <p>
        The search plays on the in-process simulator, so its results hold only if the simulator plays the same game as the real services. A
        fidelity check plays a fixed set of cases on the simulator and reproduces each one on a real lane. It passes only when every case matches.
      </p>
      <GateSection />
      <StartSection />
      <ChecksSection />
    </>
  )
}

function GateSection() {
  const client = useApiClient()
  const query = useQuery(simulatorFidelityQuery(client))
  return (
    <section aria-labelledby="fidelity-gate">
      <h2 id="fidelity-gate">The simulator&apos;s source revisions</h2>
      <QueryView query={query} failure={{ action: 'read whether the simulator is established' }}>
        {(gate) => <Gate gate={gate} />}
      </QueryView>
    </section>
  )
}

function Gate({ gate }: { readonly gate: SimulatorFidelity }) {
  const { gameService, timelineService, readService } = gate.sourceRevisions
  return (
    <>
      {gate.established ? (
        <p className="notice">
          Established: a passing fidelity check exists for exactly these revisions
          {gate.record && (
            <>
              {' '}
              (<Link to="/fidelity/$checkId" params={{ checkId: gate.record.fidelityCheckId }}>check {shortId(gate.record.fidelityCheckId)}</Link>, seed{' '}
              {gate.record.seed}).
            </>
          )}
        </p>
      ) : (
        <p className="notice notice-warning">
          Not established: no passing check exists for exactly these revisions, so search campaigns are refused until one passes.
        </p>
      )}
      <table>
        <caption>Source revisions the simulator embeds</caption>
        <thead>
          <tr>
            <th scope="col">Service</th>
            <th scope="col">Source revision</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">game-service</th>
            <td>
              <code>{gameService}</code>
            </td>
          </tr>
          <tr>
            <th scope="row">timeline-service</th>
            <td>
              <code>{timelineService}</code>
            </td>
          </tr>
          <tr>
            <th scope="row">read-service</th>
            <td>
              <code>{readService}</code>
            </td>
          </tr>
        </tbody>
      </table>
    </>
  )
}

const START = 'start'

function StartSection() {
  const client = useApiClient()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const announce = useAnnounce()
  // One key per start intent, kept until it succeeds, so retrying a lost response cannot start a second check.
  const keys = useIntentKeys()
  const [seed, setSeed] = useState('42')
  const valid = isSeed(seed)

  const start = useMutation({
    mutationFn: () => startFidelityCheckCommand(client, seed, keys.keyFor(`${START}:${seed}`)),
    onSuccess: async (check) => {
      keys.clear(`${START}:${seed}`)
      queryClient.setQueryData(['fidelity-check', check.fidelityCheckId], check)
      await queryClient.invalidateQueries({ queryKey: ['fidelity-checks'] })
      announce('Fidelity check started.')
      await navigate({ to: '/fidelity/$checkId', params: { checkId: check.fidelityCheckId } })
    },
  })

  return (
    <section aria-labelledby="fidelity-start">
      <h2 id="fidelity-start">Start a check</h2>
      <p>
        Plays the full case matrix (3, 4 and 5 players, every faction set and seat rotation, both baseline policies) for the baseline and the two edge
        variants on this seed, then reproduces every case on one real lane.
      </p>
      <label>
        Seed{' '}
        <input value={seed} inputMode="numeric" aria-invalid={!valid} aria-describedby="seed-hint" onChange={(event) => setSeed(event.target.value.trim())} />
      </label>
      <p id="seed-hint" className={valid ? 'hint' : 'hint field-error'}>
        {valid ? 'A whole number from 0 to 18446744073709551615.' : 'The seed must be a whole number from 0 to 18446744073709551615.'}
      </p>
      <div className="actions">
        <ScopedButton scope={WRITE_SCOPE} aria-busy={start.isPending} disabled={start.isPending || !valid} onAction={() => start.mutate()}>
          Start a check
        </ScopedButton>
      </div>
      {start.isError && (
        <ErrorNotice
          message={describeFailure(start.error, { action: 'start the fidelity check', requiredScope: WRITE_SCOPE })}
          onRetry={() => start.mutate()}
        />
      )}
    </section>
  )
}

function ChecksSection() {
  const client = useApiClient()
  const navigate = useNavigate()
  const { offset = 0 } = useSearch({ from: '/fidelity' })
  const query = useQuery(fidelityChecksQuery(client, offset))
  return (
    <section aria-labelledby="fidelity-checks">
      <h2 id="fidelity-checks">Checks</h2>
      <QueryView query={query} failure={{ action: 'load the fidelity checks' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>No fidelity check has been started.</p>
          ) : (
            <>
              <table>
                <caption>Fidelity checks, newest first</caption>
                <thead>
                  <tr>
                    <th scope="col">Check</th>
                    <th scope="col">Seed</th>
                    <th scope="col">State</th>
                    <th scope="col">Cases matched</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((check) => (
                    <tr key={check.fidelityCheckId}>
                      <th scope="row">
                        <Link to="/fidelity/$checkId" params={{ checkId: check.fidelityCheckId }}>
                          {shortId(check.fidelityCheckId)}
                        </Link>
                      </th>
                      <td>{check.seed}</td>
                      <td>{FIDELITY_STATE_LABELS[check.state]}</td>
                      <td>
                        {check.counts.matched} of {check.counts.total}
                      </td>
                      <td>{formatInstant(check.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Fidelity check pages"
                offset={offset}
                count={page.items.length}
                total={page.total}
                pageSize={PAGE_SIZE}
                onOffset={(next) => void navigate({ to: '/fidelity', search: { offset: next || undefined } })}
              />
            </>
          )
        }
      </QueryView>
    </section>
  )
}
