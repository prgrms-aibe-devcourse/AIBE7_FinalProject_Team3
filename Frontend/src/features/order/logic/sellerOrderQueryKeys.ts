import type { SellerOrdersQuery } from '../../../api/seller/orders'

export const sellerOrderKeys = {
  all: ['sellerOrders'] as const,
  list: (query: SellerOrdersQuery) =>
    [...sellerOrderKeys.all, 'list', query] as const,
  detail: (orderId: string) =>
    [...sellerOrderKeys.all, 'detail', orderId] as const,
}
