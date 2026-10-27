import { api } from '../client'
import type { components } from '../schema/demand'

type Schemas = components['schemas']

export type SimilarSalesRequest = Schemas['SimilarSalesRequest']
export type SimilarSales = Schemas['SimilarSalesResponse']

// 화면이 지표를 다시 계산하지 않으므로 표시에 필요한 뼈대만 확인한다(DEMAND.md 7.4)
function checked(data: Schemas['ApiResponseSimilarSalesResponse']) {
  const result = data.data
  if (data.success !== true || !result || !result.reason || !result.others)
    throw new Error('비슷한 DROP 실적 응답 형식이 올바르지 않습니다.')
  return result
}

// WISH 단계: 저장된 임베딩으로 고른다
export async function getSimilarSales(dropId: number) {
  const { data } = await api.get<Schemas['ApiResponseSimilarSalesResponse']>(
    `/seller/drops/${dropId}/similar-sales`,
  )
  return checked(data)
}

// 생성 단계: 폼 입력으로 고른다(DEMAND.md 7.5)
export async function getSimilarSalesForForm(
  request: SimilarSalesRequest,
  signal?: AbortSignal,
) {
  const { data } = await api.post<Schemas['ApiResponseSimilarSalesResponse']>(
    '/seller/similar-sales',
    request,
    { signal },
  )
  return checked(data)
}
