import type { ExperimentPreview } from '../api/generated'
import { PAGE_SIZE } from '../api/queries'
import { Pager } from '../components/Pager'

interface Props {
  readonly preview: ExperimentPreview
  /** The draft has changed since this preview was taken. */
  readonly outdated: boolean
  readonly busy: boolean
  readonly onPage: (offset: number) => void
}

/** The exact case matrix the service would freeze: totals, the split per variant and player count, and the coordinates. */
export function PreviewPanel({ preview, outdated, busy, onPage }: Props) {
  const first = preview.cases.length === 0 ? 0 : preview.offset + 1
  const last = preview.offset + preview.cases.length
  return (
    <section aria-labelledby="preview-heading" aria-busy={busy}>
      <h2 id="preview-heading">Matrix preview</h2>
      {outdated && (
        <p role="status" className="notice notice-warning">
          The draft changed after this preview. Preview again before freezing.
        </p>
      )}
      <p>
        <strong>{preview.caseCount}</strong> {preview.caseCount === 1 ? 'case' : 'cases'} in total. Nothing is frozen yet.
      </p>
      <p>
        Manifest digest <code>{preview.manifestDigest}</code>
      </p>
      <table>
        <caption>Cases per variant and player count</caption>
        <thead>
          <tr>
            <th scope="col">Variant</th>
            <th scope="col">Players</th>
            <th scope="col">Cases</th>
          </tr>
        </thead>
        <tbody>
          {preview.breakdown.map((row) => (
            <tr key={`${row.variantLabel}/${row.playerCount}`}>
              <th scope="row">{row.variantLabel}</th>
              <td>{row.playerCount}</td>
              <td>{row.caseCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <table>
        <caption>
          Case coordinates {first}–{last} of {preview.caseCount}
        </caption>
        <thead>
          <tr>
            <th scope="col">Variant</th>
            <th scope="col">Seed</th>
            <th scope="col">Players</th>
            <th scope="col">Seats</th>
          </tr>
        </thead>
        <tbody>
          {preview.cases.map((matrixCase) => (
            <tr key={matrixCase.caseKey}>
              <th scope="row">{matrixCase.variantLabel}</th>
              <td>{matrixCase.seed}</td>
              <td>{matrixCase.playerCount}</td>
              <td>
                {matrixCase.assignments.map((seat) => `${seat.seatIndex}: ${seat.faction} (${seat.policyId}@${seat.policyVersion})`).join('; ')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Pager label="Preview pages" offset={preview.offset} count={preview.cases.length} total={preview.caseCount} pageSize={PAGE_SIZE} busy={busy} onOffset={onPage} />
    </section>
  )
}
