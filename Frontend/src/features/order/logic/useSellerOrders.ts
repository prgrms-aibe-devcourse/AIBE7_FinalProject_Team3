import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getSellerOrder,
  getSellerOrders,
  prepareShipment,
  registerShipment,
  updateShipment,
  type ShipmentRequest,
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

// 행을 펼칠 때만 조회한다(열린 주문이 없으면 orderId가 null). 배송 단계가 바뀐 걸 보려면
// 새로고침하거나 패널을 다시 연다(다른 화면과 동일하게 폴링하지 않는다).
export function useSellerOrder(orderId: string | null) {
  return useQuery({
    queryKey: orderId ? sellerOrderKeys.detail(orderId) : sellerOrderKeys.all,
    queryFn: () => getSellerOrder(orderId as string),
    enabled: orderId !== null,
  })
}

// 배송 액션 성공 후에는 목록·상세를 다시 불러와 바뀐 상태를 반영한다(GR-86 자동 진행 포함).
function useInvalidateOrders() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: sellerOrderKeys.all })
}

export function usePrepareShipment() {
  const invalidate = useInvalidateOrders()
  return useMutation({
    mutationFn: (orderId: string) => prepareShipment(orderId),
    onSuccess: () => invalidate(),
    onError: () => invalidate(),
  })
}

export function useRegisterShipment() {
  const invalidate = useInvalidateOrders()
  return useMutation({
    mutationFn: ({
      orderId,
      idempotencyKey,
      body,
    }: {
      orderId: string
      idempotencyKey: string
      body: ShipmentRequest
    }) => registerShipment(orderId, idempotencyKey, body),
    onSuccess: () => invalidate(),
    onError: () => invalidate(),
  })
}

export function useUpdateShipment() {
  const invalidate = useInvalidateOrders()
  return useMutation({
    mutationFn: ({
      orderId,
      body,
    }: {
      orderId: string
      body: ShipmentRequest
    }) => updateShipment(orderId, body),
    onSuccess: () => invalidate(),
    onError: () => invalidate(),
  })
}
