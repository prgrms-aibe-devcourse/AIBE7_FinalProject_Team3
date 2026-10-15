import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { cancelWish, getMyWishes, registerWish } from '../wishes'

afterEach(() => vi.restoreAllMocks())

test('WISH 등록은 PUT 결과를 반환하고 잘못된 응답은 거부한다', async () => {
  const put = vi.spyOn(api, 'put')
  const wish = {
    dropId: 100,
    wished: true,
    wishedAt: '2026-09-18T14:00:00+09:00',
    notice: 'WISH는 구매를 보장하지 않습니다.',
  }
  put.mockResolvedValueOnce({ data: { success: true, data: wish } })
  await expect(registerWish(100)).resolves.toEqual(wish)
  expect(put).toHaveBeenCalledWith('/drops/100/wish')

  put.mockResolvedValueOnce({ data: { success: true, data: { dropId: 100 } } })
  await expect(registerWish(100)).rejects.toThrow(
    'WISH 등록 응답 형식이 올바르지 않습니다.',
  )

  put.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(registerWish(100)).rejects.toThrow(
    'WISH 등록 응답 형식이 올바르지 않습니다.',
  )
})

test('WISH 취소는 DELETE 204를 정상 처리한다', async () => {
  const remove = vi.spyOn(api, 'delete')
  remove.mockResolvedValueOnce({ status: 204, data: '' })
  await expect(cancelWish(100)).resolves.toBeUndefined()
  expect(remove).toHaveBeenCalledWith('/drops/100/wish')
})

test('내 WISH 목록은 페이지 데이터를 반환하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [{ dropId: 100, name: '한정판 스니커즈', status: 'WISH' }],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(getMyWishes({ page: 0, size: 20 })).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/users/me/wishes', {
    params: { page: 0, size: 20 },
  })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getMyWishes()).rejects.toThrow(
    'WISH 목록 응답 형식이 올바르지 않습니다.',
  )
})
