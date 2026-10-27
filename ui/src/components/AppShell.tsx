import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { useSession } from '../auth/sessionContext'
import { OBSERVE_SCOPE, READ_SCOPE, WRITE_SCOPE } from '../auth/scopes'

const SCOPES = [READ_SCOPE, WRITE_SCOPE, OBSERVE_SCOPE]

/** The frame of every page: a skip link, the navigation, what the session may do, and the main landmark. */
export function AppShell({ children }: { readonly children: ReactNode }) {
  const { scopes, signOut } = useSession()
  const granted = SCOPES.filter((scope) => scopes.has(scope))
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="shell-header">
        <p className="brand">Temporal Rift Simulation Workbench</p>
        <nav aria-label="Primary">
          <Link to="/experiments">Experiments</Link>
          <Link to="/runs">Runs</Link>
          <Link to="/comparisons">Comparisons</Link>
          <Link to="/fidelity">Simulator fidelity</Link>
        </nav>
        <p className="session">
          <span>Granted: {granted.length > 0 ? granted.join(', ') : 'no simulation scope'}</span>
          <button type="button" onClick={signOut}>
            Sign out
          </button>
        </p>
      </header>
      <main id="main" tabIndex={-1}>
        {children}
      </main>
    </>
  )
}
