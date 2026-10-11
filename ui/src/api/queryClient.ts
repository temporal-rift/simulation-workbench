import { QueryClient } from '@tanstack/react-query'

/**
 * Creates the cache for server state. Polling is the retry: a run that is open is read again on its own
 * interval, and a command never retries blindly, so neither queries nor mutations retry automatically.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: true, refetchOnReconnect: true },
      mutations: { retry: 0 },
    },
  })
}
