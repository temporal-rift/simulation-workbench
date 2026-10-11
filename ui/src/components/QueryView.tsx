import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { describeFailure, type FailureContext } from '../api/problems'
import { ErrorNotice } from './ErrorNotice'

interface Props<T> {
  readonly query: UseQueryResult<T>
  readonly failure: FailureContext
  readonly children: (data: T, state: { readonly stale: boolean }) => ReactNode
}

/**
 * Renders a read: loading, a recoverable failure, or the data. When a refresh fails the last data stays on
 * screen, marked stale with a retry, so a dropped connection never blanks what the designer was reading.
 */
export function QueryView<T>({ query, failure, children }: Props<T>) {
  if (query.data === undefined) {
    if (query.isError) {
      return <ErrorNotice message={describeFailure(query.error, failure)} onRetry={() => void query.refetch()} />
    }
    return (
      <p role="status" aria-busy="true">
        Loading…
      </p>
    )
  }
  const stale = query.isError
  return (
    <>
      {stale && (
        <div role="alert" className="notice notice-warning">
          <p>{describeFailure(query.error, failure)} Showing the last data received, which may be out of date.</p>
          <button type="button" onClick={() => void query.refetch()}>
            Retry
          </button>
        </div>
      )}
      {children(query.data, { stale })}
    </>
  )
}
