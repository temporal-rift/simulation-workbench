export const AUTH_CALLBACK_PATH = '/auth/callback'
export const HOME_PATH = '/experiments'

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Only in-application paths are accepted as a sign-in return target, never another origin. */
export function safeReturnPath(candidate: unknown): string {
  return typeof candidate === 'string' && candidate.startsWith('/') && !candidate.startsWith('//') && candidate !== AUTH_CALLBACK_PATH
    ? candidate
    : HOME_PATH
}
