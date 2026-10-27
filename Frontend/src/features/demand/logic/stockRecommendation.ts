import type {
  StockRecommendation,
  StockRecommendationRequest,
} from '../../../api/demand/stockRecommendation'

// DROP 폼의 SKU 입력은 values의 `sku:<선택 JSON>:price|stock|active` 키에 있다(sellerDropForm)
export type RecommendationInput = {
  request: StockRecommendationRequest
  rowKeys: string[]
}

const wholeNumber = (value?: string) => {
  const trimmed = (value ?? '').trim()
  return /^\d{1,12}$/.test(trimmed) ? Number(trimmed) : null
}

/*
    카테고리와 가격이 정해진 활성 SKU만 추천 요청에 넣는다. 재고는 입력돼 있으면 함께 보내
    배분 비율·대표 가격 가중치로 쓰게 한다(DEMAND.md 5.1·5.2). 요청할 수 없으면 null이다.
 */
export function toRecommendationInput(
  values: Record<string, string>,
): RecommendationInput | null {
  const categoryId = wholeNumber(values.categoryId)
  if (!categoryId) return null
  const rowKeys: string[] = []
  const options: StockRecommendationRequest['options'] = []
  for (const key of Object.keys(values)) {
    if (!key.startsWith('sku:') || !key.endsWith(':price')) continue
    const rowKey = key.slice(0, -':price'.length)
    if (values[`${rowKey}:active`] === 'false') continue
    const unitPrice = wholeNumber(values[key])
    if (unitPrice === null) continue
    const quantity = wholeNumber(values[`${rowKey}:stock`])
    rowKeys.push(rowKey)
    options.push(
      quantity === null ? { unitPrice } : { unitPrice, quantity: quantity },
    )
  }
  if (!options.length || options.length > 100) return null
  return { request: { categoryId, options }, rowKeys }
}

// 추천 배분을 각 SKU의 재고 칸에 채운다. 추천이 없으면 그대로 둔다
export function applyRecommendedStock(
  values: Record<string, string>,
  rowKeys: string[],
  recommendation: StockRecommendation,
): Record<string, string> {
  const next = { ...values }
  for (const option of recommendation.recommendation?.options ?? []) {
    const rowKey = rowKeys[option.index ?? -1]
    if (rowKey && option.recommendedQuantity != null)
      next[`${rowKey}:stock`] = String(option.recommendedQuantity)
  }
  return next
}

export const PRICE_POSITION_LABEL: Record<string, string | null> = {
  LOW: '같은 카테고리에서 낮은 편',
  MID: '같은 카테고리에서 중간',
  HIGH: '같은 카테고리에서 높은 편',
  UNKNOWN: null,
}

export const PRICE_BAND_LABEL: Record<string, string> = {
  LOWER: '더 낮은 가격대',
  SIMILAR: '비슷한 가격대',
  HIGHER: '더 높은 가격대',
}

export const STOCK_GAP_TEXT: Record<string, string> = {
  NO_SALES_SAMPLE:
    '비교할 종료 DROP이 부족해 추천하지 않았어요. 재고를 직접 정해주세요.',
  SMALL_SALES_SAMPLE: '비교한 종료 DROP이 적어 참고용으로만 봐주세요.',
  FREQUENT_SOLD_OUT:
    '비슷한 DROP이 자주 품절돼 실제 수요가 더 클 수 있어 범위 상단을 기준으로 했어요.',
}
