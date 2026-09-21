import { useState } from 'react'
import type { Order } from '../../types/order'

export default function useOrders() {
  const [orders, setOrders] = useState<Order[]>([])
  return { orders, addOrder: (order: Order) => setOrders((current) => [order, ...current]) }
}
