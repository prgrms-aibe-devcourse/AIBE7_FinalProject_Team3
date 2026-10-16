import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getSellerDropStocks } from '../drops'

afterEach(() => vi.restoreAllMocks())

test('판매자 재고를 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const stocks = {
    dropId: 100,
    options: [
      {
        optionId: 1001,
        optionName: '코튼 / 롱',
        totalStock: 10,
        availableStock: 4,
        reservedStock: 2,
        soldStock: 4,
        active: true,
      },
    ],
  }
  get.mockResolvedValueOnce({ data: { success: true, data: stocks } })
  await expect(getSellerDropStocks(100)).resolves.toEqual(stocks)
  expect(get).toHaveBeenCalledWith('/seller/drops/100/stocks')

  get.mockResolvedValueOnce({
    data: { success: true, data: { options: null } },
  })
  await expect(getSellerDropStocks(100)).rejects.toThrow(
    '판매자 재고 응답 형식이 올바르지 않습니다.',
  )
})
