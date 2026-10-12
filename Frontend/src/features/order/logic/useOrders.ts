import { useState } from 'react'
import {
  demoBuyerOrders,
  fromDemoOrder,
  type BuyerOrder,
} from '../mock/mockBuyerOrders'
import type { Order } from '../../../types/order'

export default function useOrders() {
  const [orders, setOrders] = useState<Order[]>([])
  const [buyerOrders, setBuyerOrders] = useState<BuyerOrder[]>(demoBuyerOrders)
  return {
    orders,
    buyerOrders,
    addOrder: (order: Order) => {
      setOrders((current) => [order, ...current])
      setBuyerOrders((current) => [fromDemoOrder(order), ...current])
    },
    updateBuyerOrder: (id: string, update: (order: BuyerOrder) => BuyerOrder) =>
      setBuyerOrders((current) =>
        current.map((order) => (order.id === id ? update(order) : order)),
      ),
  }
}
