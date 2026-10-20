import { api } from '../client'
import type { paths } from '../schema/order'

export type MyOrdersQuery = NonNullable<
  paths['/api/v1/orders']['get']['parameters']['query']
>
type MyOrdersResponse =
  paths['/api/v1/orders']['get']['responses'][200]['content']['*/*']
type MyOrderDetailResponse =
  paths['/api/v1/orders/{orderId}']['get']['responses'][200]['content']['*/*']

export async function getMyOrders(query: MyOrdersQuery = {}) {
  const { data } = await api.get<MyOrdersResponse>('/orders', {
    params: query,
  })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('내 주문 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getMyOrder(orderId: string) {
  const { data } = await api.get<MyOrderDetailResponse>(`/orders/${orderId}`)
  if (data.success !== true || !data.data) {
    throw new Error('내 주문 상세 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
