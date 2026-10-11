import * as z from 'zod'

declare global {
  interface Window {
    __APP_CONFIG__?: unknown
  }
}

export interface AppConfig {
  readonly oidcIssuerUrl: string
  readonly oidcClientId: string
  readonly oidcAudience: string | undefined
}

function isAbsoluteHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

// A missing value and a malformed one are different fixes for whoever deploys the service, so each reports itself.
function configuredText(name: string) {
  return z
    .string({ error: `${name} is not configured.` })
    .trim()
    .min(1, { error: `${name} is not configured.`, abort: true })
}

/**
 * Shape of `window.__APP_CONFIG__`, which the service generates from its own configuration at `/config.js` and
 * the page loads before the application bundle.
 */
export const runtimeConfigSchema = z.preprocess(
  (value) => (typeof value === 'object' && value !== null ? value : {}),
  z.object({
    oidcIssuerUrl: configuredText('oidcIssuerUrl').refine(isAbsoluteHttpUrl, {
      error: 'oidcIssuerUrl must be an absolute http(s) URL.',
    }),
    oidcClientId: configuredText('oidcClientId'),
    oidcAudience: z
      .string()
      .trim()
      .optional()
      .transform((value) => (value ? value : undefined)),
  }),
)

export type AppConfigResult =
  | { readonly ok: true; readonly config: AppConfig }
  | { readonly ok: false; readonly errors: readonly string[] }

/** Validates the service-supplied configuration before anything renders, so a bad deployment is explained. */
export function resolveAppConfig(config: unknown): AppConfigResult {
  const result = runtimeConfigSchema.safeParse(config)
  if (!result.success) {
    return { ok: false, errors: result.error.issues.map((issue) => issue.message) }
  }
  return { ok: true, config: result.data }
}
