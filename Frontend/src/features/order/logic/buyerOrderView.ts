import axios from 'axios'
import type { getMyOrder, getMyOrders } from '../../../api/order/orders'
import type { getOrderPayments } from '../../../api/payment/payments'
import { apiErrorCode } from '../../auth/logic/authUser'

type MyOrderDetail = Awaited<ReturnType<typeof getMyOrder>>
type MyOrderSummary = NonNullable<
  Awaited<ReturnType<typeof getMyOrders>>['content']
>[number]
type OrderPayment = Awaited<ReturnType<typeof getOrderPayments>>[number]

export type BuyerOrderStatus = NonNullable<MyOrderDetail['status']>
export type BuyerPaymentStatus = NonNullable<OrderPayment['status']>

export const buyerOrderStatusText: Record<BuyerOrderStatus, string> = {
  PAYMENT_PENDING: '결제 대기',
  PAID: '결제 완료',
  PREPARING: '배송 준비',
  SHIPPED: '배송 중',
  DELIVERED: '배송 완료',
  CANCELED: '취소 완료',
  EXPIRED: '주문 만료',
}

export const paymentStatusText: Record<BuyerPaymentStatus, string> = {
  PENDING: '결과 확인 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
  UNKNOWN: '결과 확인 중',
  CANCELED: '결제 취소',
}

export type BuyerOrderSummary = {
  id: string
  orderNumber: string
  productName: string
  status: BuyerOrderStatus
  totalAmount: number
  orderedAt: string
}

export type BuyerOrderPayment = {
  id: string
  amount: number
  status: BuyerPaymentStatus
  reconciliationRequired: boolean
  requestedAt: string
  paidAt: string | null
  canceledAt: string | null
  failureMessage: string | null
}

export type BuyerOrderDetail = {
  id: string
  orderNumber: string
  status: BuyerOrderStatus
  items: {
    productName: string
    optionName: string
    unitPrice: number
    quantity: number
    subtotal: number
  }[]
  itemsAmount: number
  shippingAmount: number
  totalAmount: number
  paymentStatus: BuyerPaymentStatus | null
  paymentExpiresAt: string | null
  orderedAt: string
  shippingAddress: {
    recipient: string
    phone: string
    postalCode: string
    address1: string
    address2: string | null
    deliveryMemo: string | null
  } | null
  shipping: {
    carrier: string | null
    trackingNumber: string | null
    deliveredAt: string | null
  }
}

export function toBuyerOrderSummary(order: MyOrderSummary): BuyerOrderSummary {
  return {
    id: order.orderId ?? '',
    orderNumber: order.orderNumber ?? '',
    productName: order.productName ?? '',
    status: order.status ?? 'PAYMENT_PENDING',
    totalAmount: order.totalAmount ?? 0,
    orderedAt: order.orderedAt ?? '',
  }
}

export function toBuyerOrderDetail(order: MyOrderDetail): BuyerOrderDetail {
  const address = order.shippingAddress
  return {
    id: order.orderId ?? '',
    orderNumber: order.orderNumber ?? '',
    status: order.status ?? 'PAYMENT_PENDING',
    items: (order.items ?? []).map((item) => ({
      productName: item.productName ?? '',
      optionName: item.optionName ?? '',
      unitPrice: item.unitPrice ?? 0,
      quantity: item.quantity ?? 0,
      subtotal: item.subtotal ?? 0,
    })),
    itemsAmount: order.itemsAmount ?? 0,
    shippingAmount: order.shippingAmount ?? 0,
    totalAmount: order.totalAmount ?? 0,
    paymentStatus: order.paymentStatus ?? null,
    paymentExpiresAt: order.paymentExpiresAt ?? null,
    orderedAt: order.orderedAt ?? '',
    shippingAddress: address
      ? {
          recipient: address.recipient ?? '',
          phone: address.phone ?? '',
          postalCode: address.postalCode ?? '',
          address1: address.address1 ?? '',
          address2: address.address2 ?? null,
          deliveryMemo: address.deliveryMemo ?? null,
        }
      : null,
    shipping: {
      carrier: order.shipping?.carrier ?? null,
      trackingNumber: order.shipping?.trackingNumber ?? null,
      deliveredAt: order.shipping?.deliveredAt ?? null,
    },
  }
}

export function toBuyerOrderPayments(
  payments: OrderPayment[],
): BuyerOrderPayment[] {
  return payments.map((payment) => ({
    id: payment.paymentId ?? '',
    amount: payment.amount ?? 0,
    status: payment.status ?? 'PENDING',
    reconciliationRequired: payment.reconciliationStatus === 'REQUIRED',
    requestedAt: payment.requestedAt ?? '',
    paidAt: payment.paidAt ?? null,
    canceledAt: payment.canceledAt ?? null,
    failureMessage: payment.failure?.message ?? null,
  }))
}

// 상세 응답에는 보정 여부가 없어 결제 이력의 가장 최근 시도로 판단한다(OrderDetailPage.md).
export function needsReconciliation(payments: BuyerOrderPayment[]) {
  return payments.at(-1)?.reconciliationRequired ?? false
}

export function isPaymentChecking(
  order: BuyerOrderDetail,
  payments: BuyerOrderPayment[],
) {
  return (
    order.paymentStatus === 'PENDING' ||
    order.paymentStatus === 'UNKNOWN' ||
    needsReconciliation(payments)
  )
}

export function canPay(
  order: BuyerOrderDetail,
  payments: BuyerOrderPayment[],
  currentTime = Date.now(),
) {
  return (
    order.status === 'PAYMENT_PENDING' &&
    order.paymentExpiresAt !== null &&
    new Date(order.paymentExpiresAt).getTime() > currentTime &&
    (order.paymentStatus === null || order.paymentStatus === 'FAILED') &&
    !needsReconciliation(payments)
  )
}

// 서버는 결과 불명(UNKNOWN) 결제가 있어도 취소를 받고, 승인 대기(PENDING)만 거부한다(ORDER.md 1.4).
export function canCancel(order: BuyerOrderDetail) {
  return (
    (order.status === 'PAYMENT_PENDING' ||
      order.status === 'PAID' ||
      order.status === 'PREPARING') &&
    order.paymentStatus !== 'PENDING'
  )
}

// 취소 응답이 없을 때(다시 진입)는 결제 이력의 CANCELED 시도로 환불 완료를 판단한다.
export function isRefunded(payments: BuyerOrderPayment[]) {
  return payments.some((payment) => payment.status === 'CANCELED')
}

export function isNotFoundError(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export function isAuthError(error: unknown) {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  return status === 401 || status === 403
}

export function cancelErrorMessage(error: unknown): string {
  const code = apiErrorCode(error)
  if (code === 'PAYMENT_CANCEL_FAILED') {
    return '결제 취소가 거절됐어요. 주문 상태는 그대로예요. 잠시 후 다시 시도해주세요.'
  }
  if (code === 'ORDER_STATUS_CONFLICT') {
    return '결제 승인 또는 취소 결과를 확인하는 중이에요. 잠시 후 주문 상태를 다시 확인해주세요.'
  }
  if (code === 'ORDER_NOT_CANCELABLE') {
    return '주문 상태가 바뀌어 취소할 수 없어요.'
  }
  if (code === 'VALIDATION_FAILED') {
    return '취소 사유를 500자 이내로 입력해주세요.'
  }
  if (code === 'DUPLICATE_IDEMPOTENCY_KEY') {
    return '이전 요청과 내용이 달라요. 다시 시도해주세요.'
  }
  if (isNotFoundError(error)) return '주문을 찾을 수 없어요.'
  if (isAuthError(error)) return '로그인 후 이용해주세요.'
  return '주문을 취소하지 못했어요. 잠시 후 다시 시도해주세요.'
}
