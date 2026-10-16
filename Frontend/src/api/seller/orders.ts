import { api } from '../client'
import type { paths } from '../schema/order'

export type SellerOrdersQuery = NonNullable<
  paths['/api/v1/seller/orders']['get']['parameters']['query']
>
type SellerOrdersResponse =
  paths['/api/v1/seller/orders']['get']['responses'][200]['content']['*/*']
type SellerOrderDetailResponse =
  paths['/api/v1/seller/orders/{orderId}']['get']['responses'][200]['content']['*/*']
type OrderStatusResponse =
  paths['/api/v1/seller/orders/{orderId}/prepare-shipment']['post']['responses'][200]['content']['*/*']
type ShipmentUpdateResponse =
  paths['/api/v1/seller/orders/{orderId}/shipment']['patch']['responses'][200]['content']['*/*']
export type ShipmentRequest = {
  carrier: string
  trackingNumber: string
}

export async function getSellerOrders(query: SellerOrdersQuery = {}) {
  const { data } = await api.get<SellerOrdersResponse>('/seller/orders', {
    params: query,
  })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('판매자 주문 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getSellerOrder(orderId: string) {
  const { data } = await api.get<SellerOrderDetailResponse>(
    `/seller/orders/${orderId}`,
  )
  if (data.success !== true || !data.data) {
    throw new Error('판매자 주문 상세 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function prepareShipment(orderId: string) {
  const { data } = await api.post<OrderStatusResponse>(
    `/seller/orders/${orderId}/prepare-shipment`,
  )
  if (data.success !== true || !data.data) {
    throw new Error('배송 준비 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 같은 주문에 대한 재시도는 호출부가 같은 idempotencyKey를 넘겨야 한다.
export async function registerShipment(
  orderId: string,
  idempotencyKey: string,
  body: ShipmentRequest,
) {
  const { data } = await api.post<OrderStatusResponse>(
    `/seller/orders/${orderId}/shipment`,
    body,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  if (data.success !== true || !data.data) {
    throw new Error('발송 처리 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function updateShipment(orderId: string, body: ShipmentRequest) {
  const { data } = await api.patch<ShipmentUpdateResponse>(
    `/seller/orders/${orderId}/shipment`,
    body,
  )
  if (data.success !== true || !data.data) {
    throw new Error('송장 수정 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
