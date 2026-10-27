import type {
  StockRecommendation,
  StockRecommendationRequest,
} from '../../../api/demand/stockRecommendation'
import type { OptionGroup } from '../../../types/drop'

// DROP 폼의 SKU 입력은 values의 `sku:<선택 JSON>:price|stock|active` 키에 있다(sellerDropForm)
export type RecommendationInput = {
  request: StockRecommendationRequest
  rowKeys: string[]
}

const wholeNumber = (value?: string) => {
  const trimmed = (value ?? '').trim()
  return /^\d{1,12}$/.test(trimmed) ? Number(trimmed) : null
}

// 요청 한도(DEMAND.md 5.5)를 넘는 입력은 잘라 보낸다. 비슷한 상품을 고르는 데만 쓰여 저장 값과는 무관하다
const MAX_NAME = 200
const MAX_DESCRIPTION = 5000
const MAX_GROUPS = 10
const MAX_VALUES = 50
const MAX_LABEL = 100

/*
    입력 중인 상품명·설명·옵션 구성을 보내 비슷한 상품 표본을 고르게 한다(DEMAND.md 5.1, GR-147).
    상품명과 설명이 모두 있어야 서버가 비슷한 상품으로 고르고, 없으면 보내지 않아 카테고리 기준이 된다.
 */
function catalogText(
  values: Record<string, string>,
  groups: OptionGroup[],
): Pick<StockRecommendationRequest, 'name' | 'description' | 'optionGroups'> {
  const name = (values.name ?? '').trim().slice(0, MAX_NAME)
  const description = (values.description ?? '')
    .trim()
    .slice(0, MAX_DESCRIPTION)
  if (!name || !description) return {}
  const optionGroups = groups
    .map((group) => ({
      name: group.name.trim().slice(0, MAX_LABEL),
      values: group.values
        .map((value) => value.label.trim().slice(0, MAX_LABEL))
        .filter(Boolean)
        .slice(0, MAX_VALUES),
    }))
    .filter((group) => group.name && group.values.length)
    .slice(0, MAX_GROUPS)
  return optionGroups.length
    ? { name, description, optionGroups }
    : { name, description }
}

/*
    카테고리와 가격이 정해진 활성 SKU만 추천 요청에 넣는다. 재고는 입력돼 있으면 함께 보내
    배분 비율·대표 가격 가중치로 쓰게 한다(DEMAND.md 5.1·5.2). 요청할 수 없으면 null이다.
 */
export function toRecommendationInput(
  values: Record<string, string>,
  groups: OptionGroup[] = [],
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
  return {
    request: { categoryId, options, ...catalogText(values, groups) },
    rowKeys,
  }
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
