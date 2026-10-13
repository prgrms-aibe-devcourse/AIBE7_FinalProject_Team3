import { endedDrops } from '../../drop/mock/mockEndedDrops'
import {
  orderDetails,
  sellerOrders,
  type SellerOrder,
  type SellerOrderDetail,
} from '../../order/mock/mockSellerOrders'
import type { SellerMockDrop } from './mockSellerDrops'

export type SellerSale = {
  dropId: string
  name: string
  status: SellerMockDrop['status']
  orderCount: number
  soldQuantity: number
  salesAmount: number
}

// Mock 주문에는 paid_at이 없어 SUCCEEDED로 결제 확정을 표현한다.
export function mockSellerSales(
  drops: SellerMockDrop[],
  ownerEmail: string,
  orders: (SellerOrder & { canceledAt?: string | null })[] = sellerOrders,
  details: Record<string, SellerOrderDetail> = orderDetails,
): SellerSale[] {
  return drops
    .filter((drop) => drop.ownerEmail === ownerEmail)
    .map((drop) => {
      const ended = endedDrops.find((item) => String(item.dropId) === drop.id)
      if (ended)
        return {
          dropId: drop.id,
          name: drop.name,
          status: drop.status,
          orderCount: ended.orderCount,
          soldQuantity: ended.soldStock,
          salesAmount: ended.salesAmount,
        }
      const paid = orders.filter(
        (order) =>
          String(order.dropId) === drop.id &&
          order.paymentStatus === 'SUCCEEDED' &&
          !order.canceledAt,
      )
      return {
        dropId: drop.id,
        name: drop.name,
        status: drop.status,
        orderCount: paid.length,
        soldQuantity: paid.reduce(
          (sum, order) =>
            sum +
            (details[order.orderId]?.items.reduce(
              (quantity, item) => quantity + item.quantity,
              0,
            ) ?? 0),
          0,
        ),
        salesAmount: paid.reduce((sum, order) => sum + order.totalAmount, 0),
      }
    })
}
