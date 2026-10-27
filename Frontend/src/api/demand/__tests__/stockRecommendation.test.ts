import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getStockRecommendation } from '../stockRecommendation'

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
