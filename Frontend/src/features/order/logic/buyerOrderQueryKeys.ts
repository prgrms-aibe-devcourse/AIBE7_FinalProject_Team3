import type { MyOrdersQuery } from '../../../api/order/orders'

export const buyerOrderKeys = {
  all: ['buyerOrders'] as const,
  list: (query: MyOrdersQuery) =>
    [...buyerOrderKeys.all, 'list', query] as const,
  detail: (orderId: string) =>
    [...buyerOrderKeys.all, 'detail', orderId] as const,
  payments: (orderId: string) =>
    [...buyerOrderKeys.all, 'payments', orderId] as const,
}
