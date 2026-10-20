import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getDropDetail, getDrops, getDropStocks } from '../drops'

afterEach(() => vi.restoreAllMocks())

test('목록 쿼리와 signal을 전달하고 페이지 데이터를 반환하며 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const signal = new AbortController().signal
  const page = {
    content: [
      { dropId: 100, name: '한정판 스니커즈', minPrice: null, status: 'WISH' },
    ],
    page: 0,
    size: 24,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(
    getDrops({ status: 'WISH', categoryId: 1, page: 0, size: 24 }, signal),
  ).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/drops', {
    params: { status: 'WISH', categoryId: 1, page: 0, size: 24 },
    signal,
  })

  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(getDrops()).resolves.toEqual(page)
  expect(get).toHaveBeenLastCalledWith('/drops', { params: {} })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getDrops()).rejects.toThrow(
    'DROP 목록 응답 형식이 올바르지 않습니다.',
  )

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getDrops()).rejects.toThrow(
    'DROP 목록 응답 형식이 올바르지 않습니다.',
  )
})

test('목록 요청 signal이 중단되면 요청이 취소된다', async () => {
  const get = vi.spyOn(api, 'get')
  const controller = new AbortController()
  get.mockImplementationOnce(
    (_url, config) =>
      new Promise<never>((_resolve, reject) => {
        config?.signal?.addEventListener?.('abort', () =>
          reject(new Error('aborted')),
        )
      }),
  )
  const request = getDrops(
    { status: 'WISH', sort: 'publishedAt,desc', page: 0, size: 24 },
    controller.signal,
  )
  controller.abort()
  await expect(request).rejects.toThrow('aborted')
})

test('상세 응답을 반환하고 필수 데이터가 없으면 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const signal = new AbortController().signal
  const detail = {
    dropId: 100,
    name: '한정판 스니커즈',
    status: 'GRAB',
    actions: { wishable: false, wishCancelable: false, orderable: true },
  }
  get.mockResolvedValueOnce({ data: { success: true, data: detail } })
  await expect(getDropDetail(100, signal)).resolves.toEqual(detail)
  expect(get).toHaveBeenCalledWith('/drops/100', { signal })

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getDropDetail(100)).rejects.toThrow(
    'DROP 상세 응답 형식이 올바르지 않습니다.',
  )
})

test('옵션별 재고를 반환하고 options가 배열이 아니면 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const signal = new AbortController().signal
  const stocks = {
    dropId: 100,
    options: [{ optionId: 1001 }],
    serverTime: '2026-09-20T10:01:00+09:00',
  }
  get.mockResolvedValueOnce({ data: { success: true, data: stocks } })
  await expect(getDropStocks(100, signal)).resolves.toEqual(stocks)
  expect(get).toHaveBeenCalledWith('/drops/100/stocks', { signal })

  get.mockResolvedValueOnce({
    data: { success: true, data: { dropId: 100, options: null } },
  })
  await expect(getDropStocks(100)).rejects.toThrow(
    'DROP 재고 응답 형식이 올바르지 않습니다.',
  )
})
