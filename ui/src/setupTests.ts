import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import '@testing-library/jest-dom/vitest'

afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
})

// jsdom does not implement scrolling; the router asks for it on navigation.
window.scrollTo = () => undefined
