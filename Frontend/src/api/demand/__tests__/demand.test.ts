import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { calculateDemand, getDemandAnalysis } from '../demand'

afterEach(() => vi.restoreAllMocks())

const analysis = {
  dropId: 100,
  calculatedAt: '2026-10-22T14:00:00+09:00',
  calculationVersion: 'v1',
  suggestion: { ready: true },
  demand: { activeWishCount: 420, hoursUntilSaleStart: 40, dailyWishes: [] },
  conversion: { selection: 'CATEGORY', own: {}, market: {} },
  assumptions: { safetyStockRate: { value: 0.1, source: 'DEFAULT' } },
  plan: { recommended: 139, options: [] },
  initialRecommendation: { recommended: 44, preparedTotal: 60 },
  dataGaps: [],
  notes: [],
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

test('가정을 바꾼 재계산 요청을 그대로 보내고 응답 형식을 확인한다', async () => {
  const post = vi.spyOn(api, 'post')
  const request = { conversionBasis: 'OWN' as const, safetyStockRate: 0.05 }
  post.mockResolvedValueOnce({ data: { success: true, data: analysis } })
  await expect(calculateDemand(100, request)).resolves.toEqual(analysis)
  expect(post).toHaveBeenLastCalledWith(
    '/seller/drops/100/demand-analysis/calculate',
    request,
  )

  post.mockResolvedValueOnce({ data: { success: false } })
  await expect(calculateDemand(100, request)).rejects.toThrow(
    '수요 분석 재계산 응답 형식이 올바르지 않습니다.',
  )
})
