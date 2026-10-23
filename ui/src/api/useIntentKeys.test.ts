import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useIntentKeys } from './useIntentKeys'

describe('useIntentKeys', () => {
  it('repeats the key of a scope until it is cleared', () => {
    const { result } = renderHook(() => useIntentKeys())
    const first = result.current.keyFor('launch')
    expect(result.current.keyFor('launch')).toBe(first)
    result.current.clear('launch')
    expect(result.current.keyFor('launch')).not.toBe(first)
  })

  it('keeps a separate key per scope', () => {
    const { result } = renderHook(() => useIntentKeys())
    expect(result.current.keyFor('cancel')).not.toBe(result.current.keyFor('resume'))
  })
})
