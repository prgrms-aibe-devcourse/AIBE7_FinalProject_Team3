import { api } from '../client'
import type { components } from '../schema/demand'

type Schemas = components['schemas']

export type StockRecommendationRequest = Schemas['StockRecommendationRequest']
export type StockRecommendation = Schemas['StockRecommendationResponse']
export type OptionAllocationRequest = Schemas['OptionAllocationRequest']
export type OptionAllocation = Schemas['OptionAllocationResponse']

// 화면이 수량을 다시 계산하지 않으므로 표시에 필요한 뼈대와 옵션 수만 확인한다(DEMAND.md 5.5)
export async function getStockRecommendation(
  request: StockRecommendationRequest,
  signal?: AbortSignal,
) {
  const { data } = await api.post<
    Schemas['ApiResponseStockRecommendationResponse']
  >('/seller/stock-recommendations', request, { signal })
  const result = data.data
  if (
    data.success !== true ||
    !result ||
    !result.own ||
    !result.market ||
    !result.recommendation ||
    !Array.isArray(result.recommendation.options) ||
    result.recommendation.options.length !== request.options.length ||
    !result.priceReference ||
    !Array.isArray(result.dataGaps)
  )
    throw new Error('재고 추천 응답 형식이 올바르지 않습니다.')
  return result
}

// 판매자가 "옵션별 추천"을 누를 때만 부른다. 수량은 서버가 계산하므로 옵션 수만 확인한다(DEMAND.md 7.2)
export async function getOptionAllocation(request: OptionAllocationRequest) {
  const { data } = await api.post<
    Schemas['ApiResponseOptionAllocationResponse']
  >('/seller/stock-recommendations/option-allocation', request)
  const result = data.data
  if (
    data.success !== true ||
    !result ||
    !Array.isArray(result.groups) ||
    !Array.isArray(result.options) ||
    result.options.length !== request.options.length
  )
    throw new Error('옵션별 추천 응답 형식이 올바르지 않습니다.')
  return result
}
