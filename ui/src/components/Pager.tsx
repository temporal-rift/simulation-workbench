interface Props {
  readonly label: string
  readonly offset: number
  readonly count: number
  readonly total: number
  readonly pageSize: number
  /** A page is being read: both buttons wait for it. */
  readonly busy?: boolean
  /** The range is already shown beside the pager. */
  readonly showRange?: boolean
  readonly previousLabel?: string
  readonly nextLabel?: string
  readonly onOffset: (offset: number) => void
}

export function Pager({ label, offset, count, total, pageSize, busy = false, showRange = true, previousLabel = 'Previous', nextLabel = 'Next', onOffset }: Props) {
  const first = count === 0 ? 0 : offset + 1
  const last = offset + count
  return (
    <nav aria-label={label} className="pager">
      {showRange && (
        <span>
          {first}–{last} of {total}
        </span>
      )}
      <button type="button" disabled={busy || offset === 0} onClick={() => onOffset(Math.max(0, offset - pageSize))}>
        {previousLabel}
      </button>
      <button type="button" disabled={busy || last >= total} onClick={() => onOffset(offset + pageSize)}>
        {nextLabel}
      </button>
    </nav>
  )
}
