import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getSimilarSales, getSimilarSalesForForm } from '../similarSales'

afterEach(() => vi.restoreAllMocks())

const result = {
  basis: 'OTHERS',
  reason: 'FIRST_SALE',
  own: null,
  others: {
    selection: 'CATEGORY',
    scope: 'ALL_SELLERS',
    dropCountBand: 'NONE',
  },
}

test('WISH 단계는 DROP ID로, 생성 단계는 폼 입력으로 조회한다', async () => {
  vi.spyOn(api, 'get').mockResolvedValueOnce({
    data: { success: true, data: result },
  })
  const post = vi.spyOn(api, 'post')
  post.mockResolvedValueOnce({ data: { success: true, data: result } })
  const request = { categoryId: 8, unitPrices: [12000] }

  await expect(getSimilarSales(42)).resolves.toEqual(result)
  expect(api.get).toHaveBeenCalledWith('/seller/drops/42/similar-sales')
  await expect(getSimilarSalesForForm(request)).resolves.toEqual(result)
  expect(post).toHaveBeenCalledWith('/seller/similar-sales', request, {
    signal: undefined,
  })
})

test('다른 판매자 자료가 빠진 응답은 거부한다', async () => {
  vi.spyOn(api, 'get').mockResolvedValueOnce({
    data: { success: true, data: { ...result, others: undefined } },
  })
  await expect(getSimilarSales(42)).rejects.toThrow(
    '비슷한 DROP 실적 응답 형식이 올바르지 않습니다.',
  )
})
