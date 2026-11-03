import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'
import '@testing-library/jest-dom/vitest'

// The first render of a page waits for the whole module graph; under a loaded build machine that exceeds the default
// one second a query waits, well inside the test timeout.
configure({ asyncUtilTimeout: 5_000 })

afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
})

// jsdom does not implement scrolling; the router asks for it on navigation.
window.scrollTo = () => undefined
