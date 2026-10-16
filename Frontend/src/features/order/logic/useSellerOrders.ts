import { useQuery } from '@tanstack/react-query'
import {
  getSellerOrder,
  getSellerOrders,
  type SellerOrdersQuery,
} from '../../../api/seller/orders'
import { sellerOrderKeys } from './sellerOrderQueryKeys'

export function useSellerOrders(query: SellerOrdersQuery, enabled = true) {
  return useQuery({
    queryKey: sellerOrderKeys.list(query),
    queryFn: () => getSellerOrders(query),
    enabled,
  })
}

// 행을 펼칠 때만 조회한다(열린 주문이 없으면 orderId가 null).
export function useSellerOrder(orderId: string | null) {
  return useQuery({
    queryKey: orderId ? sellerOrderKeys.detail(orderId) : sellerOrderKeys.all,
    queryFn: () => getSellerOrder(orderId as string),
    enabled: orderId !== null,
  })
}
