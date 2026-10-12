import { useMutation } from '@tanstack/react-query'
import { describeFailure } from '../api/problems'
import { fetchExport } from '../api/queries'
import { useSession } from '../auth/sessionContext'
import { saveBlob } from './download'
import { ErrorNotice } from './ErrorNotice'

type Representation = 'json' | 'csv'

const ACCEPT: Readonly<Record<Representation, string>> = { json: 'application/json', csv: 'text/csv' }

interface Props {
  /** The resource path whose JSON and CSV representations are exported. */
  readonly path: string
  /** The download's name without extension. */
  readonly filename: string
}

/** Downloads the service's JSON or CSV exactly as returned: the bytes are saved, never parsed or re-serialised. */
export function ExportButtons({ path, filename }: Props) {
  const { fetch } = useSession()
  const download = useMutation({
    mutationFn: async (representation: Representation) => {
      saveBlob(await fetchExport(fetch, path, ACCEPT[representation]), `${filename}.${representation}`)
    },
  })
  return (
    <div className="actions">
      <button type="button" aria-busy={download.isPending} onClick={() => download.mutate('json')}>
        Export JSON
      </button>
      <button type="button" aria-busy={download.isPending} onClick={() => download.mutate('csv')}>
        Export CSV
      </button>
      {download.isError && <ErrorNotice message={describeFailure(download.error, { action: 'export' })} />}
    </div>
  )
}
