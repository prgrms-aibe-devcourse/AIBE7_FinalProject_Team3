import type { AuthStatus } from '../features/auth/logic/authUser'

export type SharedProps = {
  authenticated: boolean
  authStatus: AuthStatus
  wishes: ReadonlySet<number>
  wishesReady: boolean
  toggleWish: (id: number) => Promise<boolean>
  notify: (message: string) => void
}
