import { createContext, useContext } from 'react'

export type Announce = (message: string) => void

export const AnnouncerContext = createContext<Announce>(() => undefined)

export function useAnnounce(): Announce {
  return useContext(AnnouncerContext)
}
