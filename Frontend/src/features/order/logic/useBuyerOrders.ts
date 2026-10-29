import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createOrder,
  getMyOrder,
  getMyOrders,
  type MyOrdersQuery,
  type OrderCreateRequest,
} from '../../../api/order/orders'
import {
  approvePayment,
  cancelOrder,
  getOrderPayments,
  type OrderCancelRequest,
  type PaymentRequest,
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

// livePolling은 주문 상세 화면에서만 켠다. 배송 중(SHIPPED)에는 배송 단계가 서버에서 자동으로
// 진행되므로 10초 간격으로 다시 불러오고, 배송 완료 등 그 밖의 상태에서는 멈춘다.
export function useMyOrder(
  orderId: string,
  enabled = true,
  livePolling = false,
) {
  return useQuery({
    queryKey: buyerOrderKeys.detail(orderId),
    queryFn: () => getMyOrder(orderId),
    select: toBuyerOrderDetail,
    enabled,
    refetchInterval: livePolling
      ? (query) => (query.state.data?.status === 'SHIPPED' ? 10_000 : false)
      : false,
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

// 새 주문이 생기면 내 주문 목록을 다시 불러온다. 실패는 서버 상태를 바꾸지 않으므로 그대로 둔다.
export function useCreateOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      idempotencyKey,
      body,
    }: {
      idempotencyKey: string
      body: OrderCreateRequest
    }) => createOrder(idempotencyKey, body),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: buyerOrderKeys.all }),
  })
}

// 승인 결과(성공·거절·결과 불명·409)와 관계없이 주문·결제 이력을 다시 불러와 서버 상태를 반영한다.
export function useApprovePayment() {
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
      body: PaymentRequest
    }) => approvePayment(orderId, idempotencyKey, body),
    onSuccess: () => invalidate(),
    onError: () => invalidate(),
  })
}
