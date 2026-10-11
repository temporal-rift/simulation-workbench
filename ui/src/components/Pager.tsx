interface Props {
  readonly label: string
  readonly offset: number
  readonly count: number
  readonly total: number
  readonly pageSize: number
  readonly onOffset: (offset: number) => void
}

export function Pager({ label, offset, count, total, pageSize, onOffset }: Props) {
  const first = count === 0 ? 0 : offset + 1
  const last = offset + count
  return (
    <nav aria-label={label} className="pager">
      <span>
        {first}–{last} of {total}
      </span>
      <button type="button" disabled={offset === 0} onClick={() => onOffset(Math.max(0, offset - pageSize))}>
        Previous
      </button>
      <button type="button" disabled={last >= total} onClick={() => onOffset(offset + pageSize)}>
        Next
      </button>
    </nav>
  )
}
