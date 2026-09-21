import type { Order } from './order'

export type SharedProps = {
  authenticated: boolean
  wishes: Set<number>
  toggleWish: (id: number) => boolean
  orders: Order[]
  addOrder: (order: Order) => void
  notify: (message: string) => void
}
