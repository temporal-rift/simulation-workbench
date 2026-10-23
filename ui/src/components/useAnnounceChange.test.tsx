import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { AnnouncerContext } from './announceContext'
import { useAnnounceChange } from './useAnnounceChange'

function setup(initial: string | undefined) {
  const announce = vi.fn()
  const wrapper = ({ children }: { children: ReactNode }) => <AnnouncerContext value={announce}>{children}</AnnouncerContext>
  const hook = renderHook(({ value }) => useAnnounceChange(value, (v) => `Now ${v}.`), { wrapper, initialProps: { value: initial } })
  return { announce, rerender: (value: string | undefined) => hook.rerender({ value }) }
}

describe('useAnnounceChange', () => {
  it('announces nothing on the first render', () => {
    expect(setup('a').announce).not.toHaveBeenCalled()
  })

  it('announces once per change and not on a repeated value', () => {
    const { announce, rerender } = setup('a')
    rerender('b')
    rerender('b')
    expect(announce).toHaveBeenCalledTimes(1)
    expect(announce).toHaveBeenCalledWith('Now b.')
  })

  it('announces the first value that arrives after undefined, but not undefined itself', () => {
    const { announce, rerender } = setup(undefined)
    rerender('a')
    rerender(undefined)
    expect(announce).toHaveBeenCalledTimes(1)
  })
})
