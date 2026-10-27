import type { Page } from '@playwright/test'
import type { components } from '../../src/api/schema/demand'
import { FIXED_NOW } from './api/drop/fixtures'
import { json } from './api/shared'

type StockRequest = components['schemas']['StockRecommendationRequest']

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
}
