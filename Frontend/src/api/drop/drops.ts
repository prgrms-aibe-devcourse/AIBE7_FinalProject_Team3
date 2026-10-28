import { api } from '../client'
import type { paths } from '../schema/drop'

type DropListQuery = NonNullable<
  paths['/api/v1/drops']['get']['parameters']['query']
>
type DropListResponse =
  paths['/api/v1/drops']['get']['responses'][200]['content']['*/*']
type DropDetailResponse =
  paths['/api/v1/drops/{dropId}']['get']['responses'][200]['content']['*/*']
type DropStocksResponse =
  paths['/api/v1/drops/{dropId}/stocks']['get']['responses'][200]['content']['*/*']
type RelatedDropsResponse =
  paths['/api/v1/drops/{dropId}/related']['get']['responses'][200]['content']['*/*']

export async function getDrops(
  query: DropListQuery = {},
  signal?: AbortSignal,
) {
  const { data } = await api.get<DropListResponse>(
    '/drops',
    signal ? { params: query, signal } : { params: query },
  )
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('DROP 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getDropDetail(dropId: number, signal?: AbortSignal) {
  const { data } = await api.get<DropDetailResponse>(`/drops/${dropId}`, {
    signal,
  })
  if (data.success !== true || !data.data) {
    throw new Error('DROP 상세 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getDropStocks(dropId: number, signal?: AbortSignal) {
  const { data } = await api.get<DropStocksResponse>(
    `/drops/${dropId}/stocks`,
    {
      signal,
    },
  )
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.options)
  ) {
    throw new Error('DROP 재고 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 관련 DROP 추천(DROP.md 1.4). 추천이 없으면 basis가 없고 drops는 빈 배열이다
export async function getRelatedDrops(dropId: number, signal?: AbortSignal) {
  const { data } = await api.get<RelatedDropsResponse>(
    `/drops/${dropId}/related`,
    { signal },
  )
  if (data.success !== true || !data.data || !Array.isArray(data.data.drops)) {
    throw new Error('관련 DROP 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
