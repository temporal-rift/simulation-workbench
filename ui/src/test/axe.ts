import axe from 'axe-core'
import { expect } from 'vitest'

/** Fails with the offending markup when the rendered page has an accessibility violation. */
export async function expectNoViolations(container: HTMLElement): Promise<void> {
  // Colour contrast needs real layout and painting, which the DOM implementation here does not have.
  const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
  expect(results.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.html).join(' | ')}`)).toEqual([])
}
