import axios from 'axios'
import type { OrderCreateRequest } from '../../../api/order/orders'
import type { approvePayment } from '../../../api/payment/payments'
import type { DropDetail } from '../../../types/drop'
import { apiErrorCode } from '../../auth/logic/authUser'
import { selectionLabel } from '../../drop/logic/option'
import type { BuyerOrderDetail } from './buyerOrderView'

// 상품 상세의 수량 선택 상한과 같다(DropDetailPage).
export const MAX_ORDER_QUANTITY = 5

export type CheckoutDraft = {
  dropId: number
  optionId: number
  quantity: number
}

export type ShippingAddressInput = {
  recipient: string
  phone: string
  postalCode: string
  address1: string
  address2: string
  deliveryMemo: string
}

type PaymentResult = Awaited<ReturnType<typeof approvePayment>>

export type PaymentOutcome =
  | { kind: 'SUCCEEDED' }
  | { kind: 'CHECKING' }
  | { kind: 'FAILED'; message: string }

const positiveInteger = (value: string | null) =>
  value !== null && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(+value)
    ? Number(value)
    : null

export function readCheckoutDraft(
  params: URLSearchParams,
): CheckoutDraft | null {
  const dropId = positiveInteger(params.get('dropId'))
  const optionId = positiveInteger(params.get('optionId'))
  const quantity = positiveInteger(params.get('quantity'))
  if (!dropId || !optionId || !quantity || quantity > MAX_ORDER_QUANTITY)
    return null
  return { dropId, optionId, quantity }
}

export function checkoutDraftPath(draft: CheckoutDraft) {
  const params = new URLSearchParams({
    dropId: String(draft.dropId),
    optionId: String(draft.optionId),
    quantity: String(draft.quantity),
  })
  return `/checkout?${params}`
}

// 주문 생성 전 예상 금액. 최종 금액은 주문 생성 응답(서버 계산)을 따른다.
export function toDraftSummary(detail: DropDetail, draft: CheckoutDraft) {
  const sku = detail.skus.find((item) => item.id === draft.optionId)
  if (!sku) return null
  const itemsAmount = sku.price * draft.quantity
  return {
    productName: detail.name,
    image: detail.image,
    optionLabel:
      selectionLabel(detail.optionGroups, sku.selections) || '기본 옵션',
    quantity: draft.quantity,
    itemsAmount,
    shippingAmount: detail.shippingFee,
    totalAmount: itemsAmount + detail.shippingFee,
    orderable: detail.actions.orderable,
  }
}

export function toOrderCreateRequest(
  draft: CheckoutDraft,
  address: ShippingAddressInput,
): OrderCreateRequest {
  const optional = (value: string) => value.trim() || undefined
  return {
    dropId: draft.dropId,
    items: [{ optionId: draft.optionId, quantity: draft.quantity }],
    shippingAddress: {
      recipient: address.recipient.trim(),
      phone: address.phone.trim(),
      postalCode: address.postalCode.trim(),
      address1: address.address1.trim(),
      address2: optional(address.address2),
      deliveryMemo: optional(address.deliveryMemo),
    },
  }
}

export function toPaymentOutcome(result: PaymentResult): PaymentOutcome {
  if (result.status === 'SUCCEEDED' && result.reconciliationStatus === 'NONE')
    return { kind: 'SUCCEEDED' }
  if (result.status === 'FAILED')
    return {
      kind: 'FAILED',
      message: result.failure?.message || '결제가 승인되지 않았어요.',
    }
  // PENDING·UNKNOWN과 승인됐지만 보정 대상(REQUIRED)인 결제는 결제창을 다시 열지 않는다.
  return { kind: 'CHECKING' }
}

// PG의 orderId는 서버 주문 UUID가 아니라 orderNumber다(PAYMENT.md 1.0).
export function isCallbackMismatch(
  callback: { orderId: string; amount: number },
  order: Pick<BuyerOrderDetail, 'orderNumber' | 'totalAmount'>,
) {
  return (
    callback.orderId !== order.orderNumber ||
    callback.amount !== order.totalAmount
  )
}

const isNetworkError = (error: unknown) =>
  axios.isAxiosError(error) && error.response === undefined

export function createOrderErrorMessage(error: unknown): string {
  const messages: Record<string, string> = {
    INSUFFICIENT_STOCK: '재고가 부족해요. 수량이나 옵션을 다시 골라주세요.',
    SALE_NOT_STARTED: '아직 판매가 시작되지 않았어요.',
    SALE_ENDED: '판매가 종료됐어요.',
    DROP_NOT_ON_SALE: '지금은 주문할 수 없는 상품이에요.',
    OPTION_NOT_FOUND:
      '선택한 옵션을 찾을 수 없어요. 상품 화면에서 다시 골라주세요.',
    VALIDATION_FAILED: '배송지 입력 내용을 확인해주세요.',
    DUPLICATE_IDEMPOTENCY_KEY: '이전 요청과 내용이 달라요. 다시 시도해주세요.',
  }
  const code = apiErrorCode(error)
  if (code && messages[code]) return messages[code]
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  if (status === 401 || status === 403) return '로그인 후 주문할 수 있어요.'
  if (isNetworkError(error))
    return '연결에 실패했어요. 같은 내용으로 다시 시도해주세요.'
  return '주문을 만들지 못했어요. 잠시 후 다시 시도해주세요.'
}

export function paymentErrorMessage(error: unknown): string {
  const messages: Record<string, string> = {
    PAYMENT_EXPIRED: '결제 가능 시간이 지났어요. 주문을 다시 진행해주세요.',
    PAYMENT_AMOUNT_MISMATCH: '결제 금액이 주문 금액과 달라 승인하지 않았어요.',
    INVALID_STATE_TRANSITION: '결제할 수 없는 주문 상태예요.',
    PAYMENT_ALREADY_PROCESSED:
      '이미 처리 중이거나 처리된 결제가 있어요. 주문 상태를 확인해주세요.',
    ORDER_NOT_FOUND: '주문을 찾을 수 없어요.',
    DUPLICATE_IDEMPOTENCY_KEY:
      '이전 요청과 내용이 달라요. 주문 상태를 확인해주세요.',
  }
  const code = apiErrorCode(error)
  if (code && messages[code]) return messages[code]
  if (isNetworkError(error))
    return '연결에 실패했어요. 결과 확인을 다시 시도해주세요.'
  return '결제 결과를 처리하지 못했어요. 주문 상태를 확인해주세요.'
}
