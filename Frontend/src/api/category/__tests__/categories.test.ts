import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getCategories } from '../categories'

afterEach(() => vi.restoreAllMocks())

test('카테고리 응답 데이터를 반환하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  get.mockResolvedValueOnce({
    data: {
      success: true,
      data: [{ categoryId: 1, code: 'FASHION', name: '패션' }],
    },
  })
  await expect(getCategories()).resolves.toEqual([
    { categoryId: 1, code: 'FASHION', name: '패션' },
  ])
  expect(get).toHaveBeenCalledWith('/categories')

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getCategories()).rejects.toThrow(
    '카테고리 응답 형식이 올바르지 않습니다.',
  )
})
