import { api } from '../client'
import type { paths } from '../schema/wish'

type WishListQuery = NonNullable<
  paths['/api/v1/users/me/wishes']['get']['parameters']['query']
>
type WishResponse =
  paths['/api/v1/drops/{dropId}/wish']['put']['responses'][200]['content']['*/*']
type WishListResponse =
  paths['/api/v1/users/me/wishes']['get']['responses'][200]['content']['*/*']

export async function registerWish(dropId: number) {
  const { data } = await api.put<WishResponse>(`/drops/${dropId}/wish`)
  if (data.success !== true || data.data?.wished !== true) {
    throw new Error('WISH 등록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 취소는 204 빈 본문이라 성공 여부만 확인한다(활성 WISH가 없어도 204).
export async function cancelWish(dropId: number) {
  await api.delete(`/drops/${dropId}/wish`)
}

export async function getMyWishes(query: WishListQuery = {}) {
  const { data } = await api.get<WishListResponse>('/users/me/wishes', {
    params: query,
  })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('WISH 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
