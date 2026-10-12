import type { Order } from '../../../types/order'

export type BuyerOrderStatus =
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELED'
  | 'EXPIRED'

export const buyerOrderStatusText: Record<BuyerOrderStatus, string> = {
  PAYMENT_PENDING: '결제 대기',
  PAID: '결제 완료',
  PREPARING: '배송 준비',
  SHIPPED: '배송 중',
  DELIVERED: '배송 완료',
  CANCELED: '취소 완료',
  EXPIRED: '주문 만료',
}

export type BuyerOrder = {
  id: string
  orderNumber: string
  dropId: number
  productName: string
  image: string
  optionLabel: string
  quantity: number
  itemsAmount: number
  shippingAmount: number
  totalAmount: number
  status: BuyerOrderStatus
  paymentStatus: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | null
  reconciliationStatus: 'NONE' | 'REQUIRED'
  paymentExpiresAt: string | null
  orderedAt: string
  shippingAddress: {
    recipient: string
    phone: string
    postalCode: string
    address1: string
    address2: string
  }
  shipping: {
    status: 'SHIPPED' | 'DELIVERED' | null
    carrier: string | null
    trackingNumber: string | null
    deliveredAt: string | null
  }
  refundStatus: 'NONE' | 'SUCCEEDED' | 'UNKNOWN' | null
  cancelReason: string | null
}

const now = Date.now()
const minutes = (value: number) => new Date(now + value * 60_000).toISOString()
const blankShipping: BuyerOrder['shipping'] = {
  status: null,
  carrier: null,
  trackingNumber: null,
  deliveredAt: null,
}

const base: BuyerOrder = {
  id: 'demo-pending',
  orderNumber: 'ORD-DEMO-0001',
  dropId: 201,
  productName: '뮤직 클럽 티셔츠 · 리미티드',
  image:
    'https://images.unsplash.com/photo-1576566588028-4147f3842f27?auto=format&fit=crop&w=1200&q=85',
  optionLabel: '착용감: 릴랙스 · 소매: 롱',
  quantity: 2,
  itemsAmount: 86_000,
  shippingAmount: 3_000,
  totalAmount: 89_000,
  status: 'PAYMENT_PENDING',
  paymentStatus: null,
  reconciliationStatus: 'NONE',
  paymentExpiresAt: minutes(10),
  orderedAt: minutes(-2),
  shippingAddress: {
    recipient: '김그랩',
    phone: '01012345678',
    postalCode: '06236',
    address1: '서울특별시 강남구 테헤란로 1',
    address2: '101호',
  },
  shipping: blankShipping,
  refundStatus: null,
  cancelReason: null,
}

export const demoBuyerOrders: BuyerOrder[] = [
  base,
  {
    ...base,
    id: 'demo-failed',
    orderNumber: 'ORD-DEMO-0002',
    paymentStatus: 'FAILED',
  },
  {
    ...base,
    id: 'demo-processing',
    orderNumber: 'ORD-DEMO-0003',
    paymentStatus: 'PENDING',
  },
  {
    ...base,
    id: 'demo-unknown',
    orderNumber: 'ORD-DEMO-0004',
    paymentStatus: 'UNKNOWN',
    reconciliationStatus: 'REQUIRED',
  },
  {
    ...base,
    id: 'demo-required',
    orderNumber: 'ORD-DEMO-0005',
    paymentStatus: 'SUCCEEDED',
    reconciliationStatus: 'REQUIRED',
  },
  {
    ...base,
    id: 'demo-paid',
    orderNumber: 'ORD-DEMO-0006',
    status: 'PAID',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
  },
  {
    ...base,
    id: 'demo-preparing',
    orderNumber: 'ORD-DEMO-0007',
    status: 'PREPARING',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
  },
  {
    ...base,
    id: 'demo-shipped',
    orderNumber: 'ORD-DEMO-0008',
    status: 'SHIPPED',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
    shipping: {
      status: 'SHIPPED',
      carrier: 'CJ대한통운',
      trackingNumber: '123456789012',
      deliveredAt: null,
    },
  },
  {
    ...base,
    id: 'demo-delivered',
    orderNumber: 'ORD-DEMO-0009',
    status: 'DELIVERED',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
    shipping: {
      status: 'DELIVERED',
      carrier: 'CJ대한통운',
      trackingNumber: '123456789012',
      deliveredAt: minutes(-60),
    },
  },
  {
    ...base,
    id: 'demo-canceled',
    orderNumber: 'ORD-DEMO-0010',
    status: 'CANCELED',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
    refundStatus: 'SUCCEEDED',
    cancelReason: '단순 변심',
  },
  {
    ...base,
    id: 'demo-expired',
    orderNumber: 'ORD-DEMO-0011',
    status: 'EXPIRED',
    paymentExpiresAt: minutes(-1),
  },
]

export function fromDemoOrder(order: Order): BuyerOrder {
  return {
    ...base,
    id: order.id,
    orderNumber: order.id,
    dropId: order.drop.id,
    productName: order.drop.name,
    image: order.drop.image,
    optionLabel: order.optionLabel,
    quantity: order.quantity,
    itemsAmount: order.total - order.drop.shippingFee,
    shippingAmount: order.drop.shippingFee,
    totalAmount: order.total,
    paymentExpiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    orderedAt: new Date().toISOString(),
    shippingAddress: {
      recipient: '',
      phone: '',
      postalCode: '',
      address1: '',
      address2: '',
    },
  }
}

export function canPay(order: BuyerOrder, currentTime = Date.now()) {
  return (
    order.status === 'PAYMENT_PENDING' &&
    order.paymentExpiresAt !== null &&
    new Date(order.paymentExpiresAt).getTime() > currentTime &&
    (order.paymentStatus === null || order.paymentStatus === 'FAILED') &&
    order.reconciliationStatus === 'NONE'
  )
}

export function canCancel(order: BuyerOrder) {
  return (
    (order.status === 'PAYMENT_PENDING' ||
      order.status === 'PAID' ||
      order.status === 'PREPARING') &&
    order.paymentStatus !== 'PENDING' &&
    order.paymentStatus !== 'UNKNOWN' &&
    order.reconciliationStatus === 'NONE' &&
    order.refundStatus !== 'UNKNOWN'
  )
}

export type PaymentDemoResult =
  'SUCCEEDED' | 'FAILED' | 'PENDING' | 'UNKNOWN' | 'REQUIRED'

export function withPaymentExample(
  order: BuyerOrder,
  result: PaymentDemoResult,
  shippingAddress: BuyerOrder['shippingAddress'],
): BuyerOrder {
  if (!canPay(order)) return order
  return {
    ...order,
    shippingAddress,
    status: result === 'SUCCEEDED' ? 'PAID' : 'PAYMENT_PENDING',
    paymentStatus: result === 'REQUIRED' ? 'SUCCEEDED' : result,
    reconciliationStatus:
      result === 'UNKNOWN' || result === 'REQUIRED' ? 'REQUIRED' : 'NONE',
    paymentExpiresAt: result === 'SUCCEEDED' ? null : order.paymentExpiresAt,
  }
}

export type CancelDemoResult = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'CONFLICT'

export function withCancelExample(
  order: BuyerOrder,
  result: CancelDemoResult,
  reason: string,
): BuyerOrder {
  if (!canCancel(order) || !reason.trim() || reason.trim().length > 500)
    return order
  if (result === 'FAILED' || result === 'CONFLICT') return order
  return {
    ...order,
    status: result === 'SUCCEEDED' ? 'CANCELED' : order.status,
    refundStatus:
      result === 'UNKNOWN'
        ? 'UNKNOWN'
        : order.status === 'PAYMENT_PENDING'
          ? 'NONE'
          : 'SUCCEEDED',
    cancelReason: reason.trim(),
    paymentExpiresAt: result === 'SUCCEEDED' ? null : order.paymentExpiresAt,
  }
}
