import type { Page } from '@playwright/test'
import type { components } from '../../src/api/schema/demand'
import { baseDrops, FIXED_NOW } from './api/drop/fixtures'
import { json } from './api/shared'

type StockRequest = components['schemas']['StockRecommendationRequest']
type Analysis = components['schemas']['DemandAnalysisResponse']
type CalculateRequest = components['schemas']['DemandCalculateRequest']

// 판매 시작 전 WISH DROP(101)만 분석할 수 있다. 수치는 DEMAND.md 1.6 식을 그대로 따른다.
const DEMAND_DROP_ID = 101
const ACTIVE_WISHES = 248
// DROP 101 상세 fixture의 옵션을 그대로 쓴다. 판매 전이라 가용 재고가 현재 준비 수량이고,
// 표시명은 서버처럼 옵션 그룹 순서대로 값을 " / "로 잇는다(DEMAND.md 2.1)
const OPTIONS = (() => {
  const drop = baseDrops().find((item) => item.dropId === DEMAND_DROP_ID)!
  return drop.options.map((option) => ({
    optionId: option.optionId,
    name: drop.groups
      .map(
        (group) =>
          group.values.find(
            (value) =>
              value.valueId ===
              option.selections.find(
                (selection) => selection.groupId === group.groupId,
              )?.valueId,
          )?.value,
      )
      .join(' / '),
    currentQuantity: option.availableStock,
  }))
})()

// 준비 권장 수량을 현재 수량 비율로 나누는 최대 나머지 배분(DEMAND.md 1.6)
function allocate(total: number) {
  const current = OPTIONS.reduce(
    (sum, option) => sum + option.currentQuantity,
    0,
  )
  const shares = OPTIONS.map(
    (option) => (total * option.currentQuantity) / current,
  )
  const allocated = shares.map(Math.floor)
  let left = total - allocated.reduce((sum, value) => sum + value, 0)
  shares
    .map((share, index) => ({ index, remainder: share - Math.floor(share) }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index)
    .forEach(({ index }) => {
      if (left-- > 0) allocated[index] += 1
    })
  return allocated
}
const OWN = {
  rate: 0.4,
  rateLow: 0.3,
  rateHigh: 0.51,
  avgQuantityPerBuyer: 1.0,
  confidence: 'LOW' as const,
  dropCount: 3,
  wishCount: 85,
}
const MARKET = {
  rate: 0.3,
  rateLow: 0.27,
  rateHigh: 0.33,
  avgQuantityPerBuyer: 1.1,
  confidence: 'HIGH' as const,
  dropCountBand: '10_TO_19',
  wishCount: 1740,
  asOf: '2026-10-01T00:00:00+09:00',
}
// 공개 시점 재고 추천 기록(DEMAND.md 5.6) 요약
const INITIAL_RECOMMENDATION = {
  recordedAt: '2026-09-28T10:00:00+09:00',
  basis: 'MARKET' as const,
  low: 25,
  expected: 40,
  high: 60,
  recommended: 44,
  preparedTotal: 60,
}

function analyze(request: CalculateRequest = {}): Analysis {
  const safety = request.safetyStockRate ?? 0.1
  const basis =
    request.conversionRate != null
      ? null
      : request.conversionBasis === 'OWN'
        ? OWN
        : MARKET
  const rate = request.conversionRate ?? basis!.rate
  const avg = basis?.avgQuantityPerBuyer ?? MARKET.avgQuantityPerBuyer
  const low = basis?.rateLow ?? rate * 0.7
  const high = basis?.rateHigh ?? Math.min(rate * 1.3, 1)
  const source =
    request.conversionRate != null
      ? 'SELLER_INPUT'
      : basis === OWN
        ? 'OWN'
        : 'MARKET'
  const base = ACTIVE_WISHES * rate * avg
  const recommended = Math.ceil(base * (1 + safety))
  const current = OPTIONS.reduce(
    (sum, option) => sum + option.currentQuantity,
    0,
  )
  const allocated = allocate(recommended)
  return {
    dropId: DEMAND_DROP_ID,
    calculatedAt: FIXED_NOW.toISOString(),
    calculationVersion: 'v1',
    suggestion: { ready: true },
    demand: {
      activeWishCount: ACTIVE_WISHES,
      hoursUntilSaleStart: 72,
      dailyWishes: [
        { date: '2026-09-30', count: 30 },
        { date: '2026-10-01', count: 12 },
      ],
      trendApproximate: true,
    },
    conversion: { selection: 'CATEGORY', own: OWN, market: MARKET },
    assumptions: {
      conversionRate: { value: rate, source },
      avgQuantityPerBuyer: {
        value: avg,
        source: source === 'OWN' ? 'OWN' : 'MARKET',
      },
      safetyStockRate: {
        value: safety,
        source: request.safetyStockRate == null ? 'DEFAULT' : 'SELLER_INPUT',
      },
    },
    plan: {
      low: Math.floor(ACTIVE_WISHES * low * avg),
      expected: Math.round(base),
      high: Math.ceil(ACTIVE_WISHES * high * avg),
      recommended,
      currentTotalQuantity: current,
      differenceFromCurrent: recommended - current,
      options: OPTIONS.map((option, index) => ({
        ...option,
        recommendedQuantity: allocated[index],
      })),
    },
    initialRecommendation: INITIAL_RECOMMENDATION,
    dataGaps: source === 'OWN' ? ['SMALL_CONVERSION_SAMPLE'] : [],
    notes: ['WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다.'],
  }
}

const error = (code: string) => ({
  success: false,
  error: { code, fieldErrors: [] },
})

// 생성 단계 재고 추천(DEMAND.md 5.5). 카테고리 1(패션)만 시장 표본이 있고 나머지는 표본 부족이다
function recommendStock(request: StockRequest) {
  const recommended = 44
  const quantities = request.options.map((option) => option.quantity ?? 0)
  const proportional = quantities.every((quantity) => quantity >= 1)
  const weights = proportional ? quantities : quantities.map(() => 1)
  const total = weights.reduce((sum, weight) => sum + weight, 0)
  const shares = weights.map((weight) => (recommended * weight) / total)
  const allocated = shares.map(Math.floor)
  let left = recommended - allocated.reduce((sum, value) => sum + value, 0)
  shares
    .map((share, index) => ({ index, remainder: share - Math.floor(share) }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index)
    .forEach(({ index }) => {
      if (left-- > 0) allocated[index] += 1
    })
  const available = request.categoryId === 1
  const own = {
    confidence: 'NONE',
    dropCount: 1,
    soldLow: null,
    soldMedian: null,
    soldHigh: null,
    sellThroughRate: null,
    soldOutRate: null,
  }
  return {
    calculatedAt: FIXED_NOW.toISOString(),
    calculationVersion: 's1',
    referencePrice: request.options[0].unitPrice,
    own,
    market: available
      ? {
          soldLow: 25,
          soldMedian: 40,
          soldHigh: 60,
          sellThroughRate: 0.71,
          soldOutRate: 0.3,
          confidence: 'HIGH',
          dropCountBand: '10_TO_19',
          asOf: '2026-10-01T00:00:00+09:00',
        }
      : {
          confidence: 'NONE',
          dropCountBand: 'NONE',
          asOf: '2026-10-01T00:00:00+09:00',
        },
    basis: available ? 'MARKET' : null,
    recommendation: {
      low: available ? 25 : null,
      expected: available ? 40 : null,
      high: available ? 60 : null,
      recommended: available ? recommended : null,
      safetyStockRate: 0.1,
      options: allocated.map((quantity, index) => ({
        index,
        recommendedQuantity: available ? quantity : null,
      })),
    },
    priceReference: {
      position: available ? 'MID' : 'UNKNOWN',
      typicalPriceLow: available ? 45000 : null,
      typicalPriceHigh: available ? 72000 : null,
      bands: available
        ? [
            { band: 'LOWER', sellThroughRate: 0.78, dropCountBand: '10_TO_19' },
            {
              band: 'SIMILAR',
              sellThroughRate: 0.71,
              dropCountBand: '20_PLUS',
            },
            { band: 'HIGHER', sellThroughRate: null, dropCountBand: 'NONE' },
          ]
        : [],
    },
    dataGaps: available ? [] : ['NO_SALES_SAMPLE'],
  }
}

// 수요 분석 경로만 처리한다. 다른 판매자 경로는 기존 핸들러가 맡는다
export async function installDemandApi(page: Page) {
  await page.route('**/api/v1/seller/stock-recommendations', async (route) => {
    const request = route.request()
    if (request.method() !== 'POST') return route.fallback()
    return json(route, {
      success: true,
      data: recommendStock(request.postDataJSON()),
    })
  })
  // WISH 단계 수요 분석(DEMAND.md 2.1·2.2). DROP 101 외에는 판매 시작 후로 보고 409를 준다
  await page.route(
    '**/api/v1/seller/drops/*/demand-analysis**',
    async (route) => {
      const request = route.request()
      const match = new URL(request.url()).pathname.match(
        /^\/api\/v1\/seller\/drops\/(\d+)\/demand-analysis(\/calculate)?$/,
      )
      if (!match) return route.fallback()
      const dropId = Number(match[1])
      if (dropId === 999) return json(route, error('DROP_ACCESS_DENIED'), 403)
      if (dropId !== DEMAND_DROP_ID)
        return json(route, error('DEMAND_ANALYSIS_UNAVAILABLE'), 409)
      const body: CalculateRequest =
        request.method() === 'POST' ? (request.postDataJSON() ?? {}) : {}
      if (
        body.conversionRate != null &&
        (body.conversionRate <= 0 || body.conversionRate > 1)
      )
        return json(route, error('VALIDATION_FAILED'), 400)
      return json(route, { success: true, data: analyze(body) })
    },
  )
}
