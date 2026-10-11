/**
 * Search params stay plain strings, as the URLs this client shares and
 * receives use them (`?game=`, the OAuth callback fields): no JSON or
 * number coercion, and the first of a repeated parameter wins. Each route's
 * Zod search schema then decides what it accepts.
 */

export function parseSearch(search: string): Record<string, string> {
  const values: Record<string, string> = {}
  for (const [key, value] of new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)) {
    values[key] ??= value
  }
  return values
}

export function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(search)) {
    // Every route's search is flat text; a value of any other shape has no place in the URL.
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      params.set(key, String(value))
    }
  }
  const query = params.toString()
  return query ? `?${query}` : ''
}
