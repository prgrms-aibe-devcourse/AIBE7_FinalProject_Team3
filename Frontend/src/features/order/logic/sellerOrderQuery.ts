import type { SellerOrder, SellerOrderStatus } from '../mock/mockSellerOrders'

export const SELLER_ORDER_PAGE_SIZE = 2

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
    paymentStatus,
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

export function querySellerOrders(
  orders: SellerOrder[],
  params: URLSearchParams,
) {
  const { orderStatus, paymentStatus, dropId, page } =
    readSellerOrderQuery(params)
  const filtered = orders.filter(
    (order) =>
      order.orderStatus === orderStatus &&
      (!paymentStatus || order.paymentStatus === paymentStatus) &&
      (!dropId || String(order.dropId) === dropId),
  )
  return {
    content: filtered.slice(
      page * SELLER_ORDER_PAGE_SIZE,
      (page + 1) * SELLER_ORDER_PAGE_SIZE,
    ),
    totalPages: Math.max(
      1,
      Math.ceil(filtered.length / SELLER_ORDER_PAGE_SIZE),
    ),
    totalElements: filtered.length,
    page,
    orderStatus,
  }
}
