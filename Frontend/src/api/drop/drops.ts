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

export async function getDrops(query: DropListQuery = {}) {
  const { data } = await api.get<DropListResponse>('/drops', { params: query })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('DROP 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getDropDetail(dropId: number) {
  const { data } = await api.get<DropDetailResponse>(`/drops/${dropId}`)
  if (data.success !== true || !data.data) {
    throw new Error('DROP 상세 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getDropStocks(dropId: number) {
  const { data } = await api.get<DropStocksResponse>(`/drops/${dropId}/stocks`)
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.options)
  ) {
    throw new Error('DROP 재고 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
