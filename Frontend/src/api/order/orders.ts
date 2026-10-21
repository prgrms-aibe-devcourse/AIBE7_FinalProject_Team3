import { api } from '../client'
import type { components, paths } from '../schema/order'

export type MyOrdersQuery = NonNullable<
  paths['/api/v1/orders']['get']['parameters']['query']
>
type MyOrdersResponse =
  paths['/api/v1/orders']['get']['responses'][200]['content']['*/*']
type MyOrderDetailResponse =
  paths['/api/v1/orders/{orderId}']['get']['responses'][200]['content']['*/*']
type OrderCreateResponse =
  paths['/api/v1/orders']['post']['responses'][201]['content']['*/*']
export type OrderCreateRequest = components['schemas']['OrderCreateRequest']

// 같은 주문 의도의 재시도는 호출부가 같은 idempotencyKey와 본문을 넘겨야 한다.
export async function createOrder(
  idempotencyKey: string,
  body: OrderCreateRequest,
) {
  const { data } = await api.post<OrderCreateResponse>('/orders', body, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  if (data.success !== true || !data.data?.orderId) {
    throw new Error('주문 생성 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

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
