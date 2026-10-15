import type { Order } from './order'

export type SharedProps = {
  authenticated: boolean
  wishes: ReadonlySet<number>
  wishesReady: boolean
  toggleWish: (id: number) => Promise<boolean>
  orders: Order[]
  addOrder: (order: Order) => void
  notify: (message: string) => void
}
