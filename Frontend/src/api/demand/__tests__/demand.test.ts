import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  calculateDemand,
  decideDemandPlan,
  getDemandAnalysis,
  getDemandPlan,
  getDemandPlans,
  saveDemandPlan,
} from '../demand'

afterEach(() => vi.restoreAllMocks())

const PLAN_ID = '4f0c9a52-1d6b-4f4e-9a57-8f6d2c1e0b11'
const analysis = {
  dropId: 100,
  calculatedAt: '2026-10-22T14:00:00+09:00',
  calculationVersion: 'v1',
  suggestion: { ready: true },
  demand: { activeWishCount: 420, hoursUntilSaleStart: 40, dailyWishes: [] },
  conversion: { selection: 'CATEGORY', own: {}, market: {} },
  assumptions: { safetyStockRate: { value: 0.1, source: 'DEFAULT' } },
  plan: { recommended: 139, options: [] },
  dataGaps: [],
  notes: [],
}
const plan = {
  planId: PLAN_ID,
  dropId: 100,
  status: 'DRAFT',
  analysis,
  explanationStatus: 'DISABLED',
}

test('수요 분석을 조회하고 다른 DROP이나 빈 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  get.mockResolvedValueOnce({ data: { success: true, data: analysis } })
  await expect(getDemandAnalysis(100)).resolves.toEqual(analysis)
  expect(get).toHaveBeenCalledWith('/seller/drops/100/demand-analysis', {
    signal: undefined,
  })

  get.mockResolvedValueOnce({ data: { success: true, data: analysis } })
  await expect(getDemandAnalysis(101)).rejects.toThrow(
    '수요 분석 응답 형식이 올바르지 않습니다.',
  )
  get.mockResolvedValueOnce({
    data: { success: true, data: { ...analysis, plan: { options: null } } },
  })
  await expect(getDemandAnalysis(100)).rejects.toThrow()
})

test('가정 변경 재계산과 계획 저장은 같은 요청을 보낸다', async () => {
  const post = vi.spyOn(api, 'post')
  const request = { conversionBasis: 'OWN' as const, safetyStockRate: 0.05 }
  post.mockResolvedValueOnce({ data: { success: true, data: analysis } })
  await expect(calculateDemand(100, request)).resolves.toEqual(analysis)
  expect(post).toHaveBeenLastCalledWith(
    '/seller/drops/100/demand-analysis/calculate',
    request,
  )

  post.mockResolvedValueOnce({ data: { success: true, data: plan } })
  await expect(saveDemandPlan(100, request)).resolves.toEqual(plan)
  expect(post).toHaveBeenLastCalledWith(
    '/seller/drops/100/demand-plans',
    request,
  )

  post.mockResolvedValueOnce({
    data: { success: true, data: { ...plan, status: 'UNKNOWN' } },
  })
  await expect(saveDemandPlan(100, request)).rejects.toThrow(
    '판매 계획 저장 응답 형식이 올바르지 않습니다.',
  )
})

test('계획 목록과 상세를 조회한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [{ planId: PLAN_ID, status: 'DRAFT', recommended: 139 }],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(getDemandPlans(100, 0, 10)).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/seller/drops/100/demand-plans', {
    params: { page: 0, size: 10 },
    signal: undefined,
  })

  get.mockResolvedValueOnce({ data: { success: true, data: plan } })
  await expect(getDemandPlan(PLAN_ID)).resolves.toEqual(plan)
  expect(get).toHaveBeenLastCalledWith(`/seller/demand-plans/${PLAN_ID}`, {
    signal: undefined,
  })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: [{ status: 'DRAFT' }] } },
  })
  await expect(getDemandPlans(100, 0, 10)).rejects.toThrow(
    '판매 계획 목록 응답 형식이 올바르지 않습니다.',
  )
})

test('결정 응답의 상태가 요청과 다르면 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const request = { status: 'REJECTED' as const, reason: '재고 부담' }
  const decided = { ...plan, status: 'REJECTED' }
  post.mockResolvedValueOnce({ data: { success: true, data: decided } })
  await expect(decideDemandPlan(PLAN_ID, request)).resolves.toEqual(decided)
  expect(post).toHaveBeenCalledWith(
    `/seller/demand-plans/${PLAN_ID}/decision`,
    request,
  )

  post.mockResolvedValueOnce({ data: { success: true, data: plan } })
  await expect(decideDemandPlan(PLAN_ID, request)).rejects.toThrow(
    '판매 계획 결정 응답 형식이 올바르지 않습니다.',
  )
})
