export function formatInstant(instant: string | null | undefined): string {
  return instant ? new Date(instant).toLocaleString() : '—'
}

/** The first part of an identifier, enough to tell two apart on screen; the full value stays in the link. */
export function shortId(id: string): string {
  return id.slice(0, 8)
}

export function shortDigest(digest: string): string {
  return `${digest.slice(0, 12)}…`
}
