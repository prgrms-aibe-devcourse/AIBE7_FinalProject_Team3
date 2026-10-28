import type {
  StockExplanationRequest,
  StockRecommendation,
} from '../../../api/demand/stockRecommendation'
import { apiErrorCode } from '../../auth/logic/authUser'
import type { DraftGroup } from '../../seller/logic/sellerDropForm'
import { toSimilarSalesRequest } from './similarSales'
import { toRecommendationInput } from './stockRecommendation'

/*
    근거 설명 요청(DEMAND.md 8.1)은 재고 추천과 같은 옵션 입력에 비슷한 DROP 실적의 상품명·설명·옵션을 더한다.
    재고 추천을 요청할 수 없으면(카테고리·가격 없음) null이다.
 */
export function toStockExplanationRequest(
  values: Record<string, string>,
  groups: DraftGroup[],
): StockExplanationRequest | null {
  const input = toRecommendationInput(values)
  const similar = toSimilarSalesRequest(values, groups)
  if (!input || !similar) return null
  return {
    ...input.request,
    name: similar.name,
    description: similar.description,
    optionGroups: similar.optionGroups,
  }
}

// 표본이 부족해 추천하지 못했으면 버튼이 그 이유를 묻는다(DEMAND.md 8.5)
export const explanationButtonLabel = (recommendation: StockRecommendation) =>
  recommendation.dataGaps?.includes('NO_SALES_SAMPLE')
    ? '추천하지 못한 이유'
    : '근거 보기'

// 한도 초과만 따로 알리고 나머지는 다시 시도를 안내한다. 재고 추천 패널은 그대로 쓸 수 있다
export function stockExplanationErrorMessage(error: unknown) {
  return apiErrorCode(error) === 'DEMAND_EXPLANATION_LIMIT_EXCEEDED'
    ? '오늘 요청할 수 있는 AI 설명 횟수를 모두 썼어요. 내일 다시 시도해주세요.'
    : '근거 설명을 불러오지 못했어요. 잠시 후 다시 시도해주세요.'
}
