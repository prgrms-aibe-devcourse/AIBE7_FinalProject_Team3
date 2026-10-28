import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  getStockRecommendation,
  requestStockExplanation,
} from '../stockRecommendation'

afterEach(() => vi.restoreAllMocks())

const request = {
  categoryId: 3,
  options: [{ unitPrice: 59000, quantity: 40 }, { unitPrice: 59000 }],
}
const result = {
  calculationVersion: 's1',
  referencePrice: 59000,
  own: { confidence: 'NONE', dropCount: 1 },
  market: { confidence: 'HIGH', dropCountBand: '10_TO_19' },
  basis: 'MARKET',
  recommendation: {
    recommended: 44,
    options: [
      { index: 0, recommendedQuantity: 30 },
      { index: 1, recommendedQuantity: 14 },
    ],
  },
  priceReference: { position: 'MID', bands: [] },
  dataGaps: [],
}

test('폼 입력값으로 재고 추천을 요청하고 옵션 수가 다른 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  post.mockResolvedValueOnce({ data: { success: true, data: result } })
  await expect(getStockRecommendation(request)).resolves.toEqual(result)
  expect(post).toHaveBeenCalledWith('/seller/stock-recommendations', request, {
    signal: undefined,
  })

  post.mockResolvedValueOnce({
    data: {
      success: true,
      data: {
        ...result,
        recommendation: { ...result.recommendation, options: [] },
      },
    },
  })
  await expect(getStockRecommendation(request)).rejects.toThrow(
    '재고 추천 응답 형식이 올바르지 않습니다.',
  )
})

test('근거 설명은 폼 입력으로 요청하고, 요약 없는 GENERATED 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const explanationRequest = { ...request, name: '키링' }
  const generated = {
    status: 'GENERATED',
    explanation: { summary: '준비 권장 수량은 44개입니다.' },
    generatedAt: '2026-10-28T10:00:00Z',
  }
  post.mockResolvedValueOnce({ data: { success: true, data: generated } })
  await expect(requestStockExplanation(explanationRequest)).resolves.toEqual(
    generated,
  )
  expect(post).toHaveBeenCalledWith(
    '/seller/stock-recommendations/explanation',
    explanationRequest,
  )

  post.mockResolvedValueOnce({
    data: { success: true, data: { status: 'GENERATED', explanation: {} } },
  })
  await expect(requestStockExplanation(explanationRequest)).rejects.toThrow(
    '재고 추천 근거 설명 응답 형식이 올바르지 않습니다.',
  )
})
