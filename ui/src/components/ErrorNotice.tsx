export function ErrorNotice({ message, onRetry }: { readonly message: string; readonly onRetry?: () => void }) {
  return (
    <div role="alert" className="notice notice-error">
      <p>{message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  )
}
