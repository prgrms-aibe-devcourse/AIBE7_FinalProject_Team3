import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getMyOrder,
  getMyOrders,
  type MyOrdersQuery,
} from '../../../api/order/orders'
import {
  cancelOrder,
  getOrderPayments,
  type OrderCancelRequest,
} from '../../../api/payment/payments'
import { buyerOrderKeys } from './buyerOrderQueryKeys'
import {
  toBuyerOrderDetail,
  toBuyerOrderPayments,
  toBuyerOrderSummary,
} from './buyerOrderView'

export function useMyOrders(query: MyOrdersQuery, enabled = true) {
  return useQuery({
    queryKey: buyerOrderKeys.list(query),
    queryFn: () => getMyOrders(query),
    select: (page) => ({
      ...page,
      content: (page.content ?? []).map(toBuyerOrderSummary),
    }),
    enabled,
  })
}

export function useMyOrder(orderId: string, enabled = true) {
  return useQuery({
    queryKey: buyerOrderKeys.detail(orderId),
    queryFn: () => getMyOrder(orderId),
    select: toBuyerOrderDetail,
    enabled,
  })
}

export function useOrderPayments(orderId: string, enabled = true) {
  return useQuery({
    queryKey: buyerOrderKeys.payments(orderId),
    queryFn: () => getOrderPayments(orderId),
    select: toBuyerOrderPayments,
    enabled,
  })
}

// 취소 결과(성공·실패·결과 불명)와 관계없이 목록·상세·결제 이력을 다시 불러와 서버 상태를 반영한다.
export function useCancelOrder() {
  const queryClient = useQueryClient()
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: buyerOrderKeys.all })
  return useMutation({
    mutationFn: ({
      orderId,
      idempotencyKey,
      body,
    }: {
      orderId: string
      idempotencyKey: string
      body: OrderCancelRequest
    }) => cancelOrder(orderId, idempotencyKey, body),
    onSuccess: () => invalidate(),
    onError: () => invalidate(),
  })
}
