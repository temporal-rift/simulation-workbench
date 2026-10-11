import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router'
import { render } from '@testing-library/react'
import { createQueryClient } from '../api/queryClient'
import { SessionProvider } from '../auth/sessionContext'
import { OBSERVE_SCOPE, READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'
import { AnnouncerProvider } from '../components/Announcer'
import { createAppRouter } from '../routing/router'
import type { FakeService } from './fakeService'

export interface RenderOptions {
  readonly scopes?: readonly string[]
  readonly onSignOut?: () => void
}

/** The whole application at `path`, signed in with `scopes`, talking to the fake service. */
export async function renderApp(path: string, service: FakeService, options: RenderOptions = {}) {
  const queryClient = createQueryClient()
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))
  await router.load()
  const session = {
    fetch: service.fetch,
    scopes: new Set(options.scopes ?? [READ_SCOPE, WRITE_SCOPE, OBSERVE_SCOPE]),
    signOut: options.onSignOut ?? (() => undefined),
  }
  const view = render(
    <QueryClientProvider client={queryClient}>
      <SessionProvider value={session}>
        <AnnouncerProvider>
          <RouterProvider router={router} />
        </AnnouncerProvider>
      </SessionProvider>
    </QueryClientProvider>,
  )
  return { ...view, router, queryClient }
}
