import { api } from '../client'
import type { paths } from '../schema/payment'

type OrderPaymentsResponse =
  paths['/api/v1/orders/{orderId}/payments']['get']['responses'][200]['content']['*/*']
type OrderCancelResponse =
  paths['/api/v1/orders/{orderId}/cancel']['post']['responses'][200]['content']['*/*']
export type OrderCancelRequest = {
  reason: string
}
type PaymentResponse =
  paths['/api/v1/orders/{orderId}/payments']['post']['responses'][200]['content']['*/*']
export type PaymentRequest = {
  paymentKey: string
  amount: number
}

export async function getOrderPayments(orderId: string) {
  const { data } = await api.get<OrderPaymentsResponse>(
    `/orders/${orderId}/payments`,
  )
  if (data.success !== true || !Array.isArray(data.data)) {
    throw new Error('결제 이력 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 결제창 성공 콜백 값으로 승인한다. UNKNOWN이면 호출부가 같은 idempotencyKey와 본문으로 다시 보내 결과를 확인한다.
export async function approvePayment(
  orderId: string,
  idempotencyKey: string,
  body: PaymentRequest,
) {
  const { data } = await api.post<PaymentResponse>(
    `/orders/${orderId}/payments`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  if (data.success !== true || !data.data?.status) {
    throw new Error('결제 승인 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// refundStatus가 UNKNOWN이면 호출부가 같은 idempotencyKey와 본문으로 다시 보내 결과를 확인한다.
export async function cancelOrder(
  orderId: string,
  idempotencyKey: string,
  body: OrderCancelRequest,
) {
  const { data } = await api.post<OrderCancelResponse>(
    `/orders/${orderId}/cancel`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  if (data.success !== true || !data.data) {
    throw new Error('주문 취소 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
