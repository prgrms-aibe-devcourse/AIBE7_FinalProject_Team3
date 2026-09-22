import type { Order } from './order'

export type SharedProps = {
  authenticated: boolean
  wishes: Set<number>
  toggleWish: (id: number) => boolean
  orders: Order[]
  addOrder: (order: Order) => void
  updateOrder: (id: string, changes: Partial<Order>) => void
  notify: (message: string) => void
}
