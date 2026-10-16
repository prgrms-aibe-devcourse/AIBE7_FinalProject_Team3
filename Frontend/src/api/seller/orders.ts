import { api } from '../client'
import type { paths } from '../schema/order'

export type SellerOrdersQuery = NonNullable<
  paths['/api/v1/seller/orders']['get']['parameters']['query']
>
type SellerOrdersResponse =
  paths['/api/v1/seller/orders']['get']['responses'][200]['content']['*/*']
type SellerOrderDetailResponse =
  paths['/api/v1/seller/orders/{orderId}']['get']['responses'][200]['content']['*/*']

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
