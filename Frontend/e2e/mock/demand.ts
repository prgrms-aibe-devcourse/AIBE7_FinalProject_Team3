import type { Page } from '@playwright/test'
import type { components } from '../../src/api/schema/demand'
import { FIXED_NOW } from './api/drop/fixtures'
import { json } from './api/shared'

type Analysis = components['schemas']['DemandAnalysisResponse']
type Plan = components['schemas']['DemandPlanResponse']
type CalculateRequest = components['schemas']['DemandCalculateRequest']

// 판매 시작 전 WISH DROP(101)만 분석할 수 있다. 수치는 DEMAND.md 1.6 식을 그대로 따른다.
const DEMAND_DROP_ID = 101
const ACTIVE_WISHES = 248
const OPTIONS = [
  { optionId: 1011, name: '내추럴', currentQuantity: 30 },
  { optionId: 1012, name: '블랙', currentQuantity: 30 },
]
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
  const first = Math.ceil(recommended / 2)
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
        recommendedQuantity: index === 0 ? first : recommended - first,
      })),
    },
    dataGaps: source === 'OWN' ? ['SMALL_CONVERSION_SAMPLE'] : [],
    notes: ['WISH 없이 구매한 사용자는 전환율에 포함하지 않았습니다.'],
  }
}

const error = (code: string) => ({
  success: false,
  error: { code, fieldErrors: [] },
})

export async function installDemandApi(page: Page) {
  const plans: Plan[] = []
  await page.route('**/api/v1/seller/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const dropMatch = path.match(
      /^\/api\/v1\/seller\/drops\/(\d+)\/(demand-analysis(?:\/calculate)?|demand-plans)$/,
    )
    if (dropMatch) {
      const dropId = Number(dropMatch[1])
      if (dropId === 999) return json(route, error('DROP_ACCESS_DENIED'), 403)
      if (dropMatch[2] === 'demand-plans' && method === 'GET') {
        const content = plans
          .filter((plan) => plan.dropId === dropId)
          .map((plan) => ({
            planId: plan.planId,
            status: plan.status,
            recommended: plan.analysis?.plan?.recommended,
            createdAt: plan.createdAt,
            decidedAt: plan.decision?.decidedAt,
          }))
        return json(route, {
          success: true,
          data: {
            content,
            page: 0,
            size: 10,
            totalElements: content.length,
            totalPages: content.length ? 1 : 0,
            hasNext: false,
          },
        })
      }
      if (dropId !== DEMAND_DROP_ID)
        return json(route, error('DEMAND_ANALYSIS_UNAVAILABLE'), 409)
      const body: CalculateRequest =
        method === 'POST' ? (request.postDataJSON() ?? {}) : {}
      if (
        body.conversionRate != null &&
        (body.conversionRate <= 0 || body.conversionRate > 1)
      )
        return json(route, error('VALIDATION_FAILED'), 400)
      if (dropMatch[2] === 'demand-plans' && method === 'POST') {
        const plan: Plan = {
          planId: `00000000-0000-4000-8000-${String(plans.length + 1).padStart(12, '0')}`,
          dropId,
          status: 'DRAFT',
          createdAt: FIXED_NOW.toISOString(),
          analysis: analyze(body),
          explanationStatus: 'DISABLED',
        }
        plans.unshift(plan)
        return json(route, { success: true, data: plan }, 201)
      }
      return json(route, { success: true, data: analyze(body) })
    }
    const planMatch = path.match(
      /^\/api\/v1\/seller\/demand-plans\/([^/]+)(\/decision)?$/,
    )
    if (!planMatch) return route.fallback()
    const index = plans.findIndex((plan) => plan.planId === planMatch[1])
    if (index < 0) return json(route, error('DEMAND_PLAN_NOT_FOUND'), 404)
    if (!planMatch[2]) return json(route, { success: true, data: plans[index] })
    if (plans[index].status !== 'DRAFT')
      return json(route, error('DEMAND_PLAN_ALREADY_DECIDED'), 409)
    const decision = request.postDataJSON()
    plans[index] = {
      ...plans[index],
      status: decision.status,
      decision: { ...decision, decidedAt: FIXED_NOW.toISOString() },
    }
    return json(route, { success: true, data: plans[index] })
  })
}
