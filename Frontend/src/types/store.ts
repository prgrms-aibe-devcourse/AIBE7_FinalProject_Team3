import type { AuthStatus } from '../features/auth/logic/authUser'
import type { Order } from './order'

export type SharedProps = {
  authenticated: boolean
  authStatus: AuthStatus
  wishes: ReadonlySet<number>
  wishesReady: boolean
  toggleWish: (id: number) => Promise<boolean>
  addOrder: (order: Order) => void
  notify: (message: string) => void
}
