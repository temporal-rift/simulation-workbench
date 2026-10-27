/**
 * The designer application's URL space as type-safe TanStack Router routes. Search params stay plain text
 * (see `search.ts`) and each route's schema decides what it accepts; an unknown path returns to the experiments.
 */
import { Navigate, Outlet, createRootRoute, createRoute, createRouter, redirect, type RouterHistory } from '@tanstack/react-router'
import * as z from 'zod'
import { zFidelityCaseResult, zFidelityVariantLabel, zRunState } from '../api/generated/zod.gen'
import { AppShell } from '../components/AppShell'
import { ExperimentFormPage } from '../experiments/ExperimentFormPage'
import { ExperimentPage } from '../experiments/ExperimentPage'
import { ExperimentsListPage } from '../experiments/ExperimentsListPage'
import { FidelityCheckPage } from '../fidelity/FidelityCheckPage'
import { FidelityPage } from '../fidelity/FidelityPage'
import { ComparisonPage } from '../reports/ComparisonPage'
import { ComparisonsPage } from '../reports/ComparisonsPage'
import { ReportPage } from '../reports/ReportPage'
import { CasePage } from '../runs/CasePage'
import { RunPage } from '../runs/RunPage'
import { RunsListPage } from '../runs/RunsListPage'
import { HOME_PATH, UUID_PATTERN } from './paths'
import { parseSearch, stringifySearch } from './search'

const rootRoute = createRootRoute({
  component: () => (
    <AppShell>
      <Outlet />
    </AppShell>
  ),
  notFoundComponent: () => <Navigate to={HOME_PATH} replace />,
})

const offsetSearch = z.object({ offset: z.coerce.number().int().min(0).optional().catch(undefined) })

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/experiments', replace: true })
  },
})

const experimentsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/experiments',
  validateSearch: offsetSearch,
  component: ExperimentsListPage,
})

const newExperimentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/experiments/new',
  component: ExperimentFormPage,
})

const experimentRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/experiments/$experimentId',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.experimentId)) {
      throw redirect({ to: '/experiments', replace: true })
    }
  },
  component: ExperimentPage,
})

const runsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/runs',
  validateSearch: offsetSearch.extend({
    state: zRunState.optional().catch(undefined),
    experimentId: z.string().regex(UUID_PATTERN).optional().catch(undefined),
  }),
  component: RunsListPage,
})

const runRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/runs/$runId',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.runId)) {
      throw redirect({ to: '/runs', replace: true })
    }
  },
  component: RunPage,
})

const reportRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/runs/$runId/report',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.runId)) {
      throw redirect({ to: '/runs', replace: true })
    }
  },
  component: ReportPage,
})

const comparisonsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/comparisons',
  validateSearch: offsetSearch,
  component: ComparisonsPage,
})

const comparisonRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/comparisons/$comparisonId',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.comparisonId)) {
      throw redirect({ to: '/comparisons', replace: true })
    }
  },
  component: ComparisonPage,
})

const caseRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/runs/$runId/cases/$caseId',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.runId) || !UUID_PATTERN.test(params.caseId)) {
      throw redirect({ to: '/runs', replace: true })
    }
  },
  component: CasePage,
})

const fidelityRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/fidelity',
  validateSearch: offsetSearch,
  component: FidelityPage,
})

const fidelityCheckRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/fidelity/$checkId',
  beforeLoad: ({ params }) => {
    if (!UUID_PATTERN.test(params.checkId)) {
      throw redirect({ to: '/fidelity', replace: true })
    }
  },
  validateSearch: offsetSearch.extend({
    variantLabel: zFidelityVariantLabel.optional().catch(undefined),
    result: zFidelityCaseResult.optional().catch(undefined),
  }),
  component: FidelityCheckPage,
})

/** The fixed OIDC redirect URI. The sign-in gate completes the exchange first, so a visit here has nothing left to do. */
const authCallbackRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/auth/callback',
  beforeLoad: () => {
    throw redirect({ to: '/experiments', replace: true })
  },
})

export const routeTree = rootRoute.addChildren([
  homeRoute,
  experimentsRoute,
  newExperimentRoute,
  experimentRoute,
  runsRoute,
  runRoute,
  reportRoute,
  comparisonsRoute,
  comparisonRoute,
  caseRoute,
  fidelityRoute,
  fidelityCheckRoute,
  authCallbackRoute,
])

export function createAppRouter(history?: RouterHistory) {
  return createRouter({ routeTree, history, parseSearch, stringifySearch })
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>
  }
}
