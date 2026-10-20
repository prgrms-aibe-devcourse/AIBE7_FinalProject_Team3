import { useState } from 'react'
import {
  demoBuyerOrders,
  fromDemoOrder,
  type BuyerOrder,
} from '../mock/mockBuyerOrders'
import type { Order } from '../../../types/order'

// 주문 생성·결제 화면(GR-118 전까지 Mock)만 쓰는 데모 주문 상태다.
export default function useOrders() {
  const [buyerOrders, setBuyerOrders] = useState<BuyerOrder[]>(demoBuyerOrders)
  return {
    buyerOrders,
    addOrder: (order: Order) =>
      setBuyerOrders((current) => [fromDemoOrder(order), ...current]),
    updateBuyerOrder: (id: string, update: (order: BuyerOrder) => BuyerOrder) =>
      setBuyerOrders((current) =>
        current.map((order) => (order.id === id ? update(order) : order)),
      ),
  }
}
