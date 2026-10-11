import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { experimentsQuery, PAGE_SIZE, useApiClient } from '../api/queries'
import { WRITE_SCOPE } from '../auth/scopes'
import { formatInstant, shortDigest } from '../components/format'
import { Pager } from '../components/Pager'
import { QueryView } from '../components/QueryView'
import { useCan } from '../auth/sessionContext'
import { hasUnsentDraft } from './draft'

export function ExperimentsListPage() {
  const client = useApiClient()
  const { offset = 0 } = useSearch({ from: '/experiments' })
  const navigate = useNavigate()
  const query = useQuery(experimentsQuery(client, offset))
  const canWrite = useCan(WRITE_SCOPE)
  return (
    <>
      <h1>Experiments</h1>
      <p>
        <Link to="/experiments/new">{hasUnsentDraft() ? 'Continue the unsent draft' : 'New experiment'}</Link>
        {!canWrite && <span className="hint"> Previewing and freezing need the {WRITE_SCOPE} scope.</span>}
      </p>
      <QueryView query={query} failure={{ action: 'load the experiments' }}>
        {(page) =>
          page.items.length === 0 ? (
            <p>No experiment has been frozen yet.</p>
          ) : (
            <>
              <table>
                <caption>Frozen experiments, newest first</caption>
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Manifest digest</th>
                    <th scope="col">Frozen</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((experiment) => (
                    <tr key={experiment.experimentId}>
                      <th scope="row">
                        <Link to="/experiments/$experimentId" params={{ experimentId: experiment.experimentId }}>
                          {experiment.name}
                        </Link>
                      </th>
                      <td>
                        <code title={experiment.manifestDigest}>{shortDigest(experiment.manifestDigest)}</code>
                      </td>
                      <td>{formatInstant(experiment.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Pager
                label="Experiment pages"
                offset={offset}
                count={page.items.length}
                total={page.total}
                pageSize={PAGE_SIZE}
                onOffset={(next) => void navigate({ to: '/experiments', search: { offset: next || undefined } })}
              />
            </>
          )
        }
      </QueryView>
    </>
  )
}
