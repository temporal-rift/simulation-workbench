import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { useState } from 'react'
import { createQueryClient } from './api/queryClient'
import { AuthGate } from './auth/session'
import { AnnouncerProvider } from './components/Announcer'
import { resolveAppConfig } from './config/appConfig'
import { createAppRouter } from './routing/router'

export default function App() {
  const [queryClient] = useState(createQueryClient)
  const [router] = useState(() => createAppRouter())
  const [resolved] = useState(() => resolveAppConfig(window.__APP_CONFIG__))
  if (!resolved.ok) {
    return (
      <main className="centered">
        <h1>The workbench is not configured</h1>
        <ul role="alert">
          {resolved.errors.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      </main>
    )
  }
  return (
    <QueryClientProvider client={queryClient}>
      <AuthGate config={resolved.config}>
        <AnnouncerProvider>
          <RouterProvider router={router} />
        </AnnouncerProvider>
      </AuthGate>
    </QueryClientProvider>
  )
}
