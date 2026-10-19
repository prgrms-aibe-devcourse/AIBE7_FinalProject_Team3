import type { SellerOrderStatus } from '../mock/mockSellerOrders'

export const sellerOrderStatuses: SellerOrderStatus[] = [
  'PAID',
  'PREPARING',
  'SHIPPED',
  'DELIVERED',
]

export function readSellerOrderQuery(params: URLSearchParams) {
  const orderStatus = params.get('orderStatus') ?? 'PAID'
  const paymentStatus = params.get('paymentStatus') ?? ''
  const dropId = params.get('dropId') ?? ''
  const pageText = params.get('page') ?? '0'
  const page = Number(pageText)
  if (
    !sellerOrderStatuses.includes(orderStatus as SellerOrderStatus) ||
    (paymentStatus !== '' &&
      !['SUCCEEDED', 'UNKNOWN'].includes(paymentStatus)) ||
    (dropId !== '' && !/^[1-9]\d*$/.test(dropId)) ||
    !/^\d+$/.test(pageText) ||
    !Number.isSafeInteger(page)
  ) {
    throw new RangeError('주문 조회 조건이 올바르지 않습니다.')
  }
  return {
    orderStatus: orderStatus as SellerOrderStatus,
    paymentStatus: paymentStatus as '' | 'SUCCEEDED' | 'UNKNOWN',
    dropId,
    page,
  }
}

export function changeSellerOrderParams(
  current: URLSearchParams,
  key: 'orderStatus' | 'paymentStatus' | 'dropId' | 'page',
  value: string,
) {
  const next = new URLSearchParams(current)
  if (value) next.set(key, value)
  else next.delete(key)
  if (key !== 'page') next.delete('page')
  return next
}
