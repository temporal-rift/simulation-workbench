/**
 * Renders a service-supplied fact tree exactly as received: objects as terms, lists as lists, scalars as text.
 * It derives nothing and fills nothing in, so what a view shows is what its response held.
 */
export function FactList({ value }: { readonly value: unknown }) {
  if (value === null || value === undefined) {
    return <span>none</span>
  }
  if (Array.isArray(value)) {
    return value.length === 0 ? (
      <span>empty</span>
    ) : (
      <ul className="facts">
        {value.map((entry, index) => (
          <li key={index}>
            <FactList value={entry} />
          </li>
        ))}
      </ul>
    )
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    return entries.length === 0 ? (
      <span>empty</span>
    ) : (
      <dl className="facts">
        {entries.map(([key, entry]) => (
          <div key={key}>
            <dt>{key}</dt>
            <dd>
              <FactList value={entry} />
            </dd>
          </div>
        ))}
      </dl>
    )
  }
  return <span>{String(value)}</span>
}
