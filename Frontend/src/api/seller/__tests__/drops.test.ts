import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  createSellerDrop,
  getSellerDropDetail,
  getSellerDropStocks,
  publishSellerDrop,
  updateSellerDrop,
  getSellerDrops,
  cancelSellerDrop,
  getSellerDropWishCount,
} from '../drops'

afterEach(() => vi.restoreAllMocks())

test('목록에 서버 상태·페이지·크기를 전달하고 페이지 응답을 확인한다', async () => {
  const page = {
    content: [{ dropId: 100, status: 'DRAFT' }],
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  const get = vi
    .spyOn(api, 'get')
    .mockResolvedValue({ data: { success: true, data: page } })
  const params = { status: 'DRAFT' as const, page: 0, size: 20 }
  await expect(getSellerDrops(params)).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/seller/drops', {
    params,
    signal: undefined,
  })
  get.mockResolvedValue({
    data: { success: true, data: { ...page, content: [{ dropId: -1 }] } },
  })
  await expect(getSellerDrops(params)).rejects.toThrow('목록 응답 형식')
})

test('취소 사유를 보내고 CANCELED 상태 응답을 확인한다', async () => {
  const post = vi.spyOn(api, 'post').mockResolvedValue({
    data: { success: true, data: { dropId: 100, status: 'CANCELED' } },
  })
  await expect(cancelSellerDrop(100, '일정 변경')).resolves.toMatchObject({
    status: 'CANCELED',
  })
  expect(post).toHaveBeenCalledWith('/seller/drops/100/cancel', {
    reason: '일정 변경',
  })
  post.mockResolvedValue({
    data: { success: true, data: { dropId: 101, status: 'CANCELED' } },
  })
  await expect(cancelSellerDrop(100, '일정 변경')).rejects.toThrow(
    '취소 응답 형식',
  )
})

test('판매자 WISH 수는 해당 DROP의 음수가 아닌 정수만 반환한다', async () => {
  const get = vi.spyOn(api, 'get').mockResolvedValue({
    data: { success: true, data: { dropId: 100, activeWishCount: 0 } },
  })
  await expect(getSellerDropWishCount(100)).resolves.toBe(0)
  expect(get).toHaveBeenCalledWith('/seller/drops/100/wish-count')
  get.mockResolvedValue({
    data: { success: true, data: { dropId: 100, activeWishCount: -1 } },
  })
  await expect(getSellerDropWishCount(100)).rejects.toThrow('WISH 수 응답 형식')
})

test('판매자 상세 요청에 취소 신호를 전달한다', async () => {
  const detail = {
    dropId: 100,
    status: 'DRAFT',
    images: [],
    detailImages: [],
    optionGroups: [],
    options: [],
  }
  const get = vi
    .spyOn(api, 'get')
    .mockResolvedValue({ data: { success: true, data: detail } })
  const signal = new AbortController().signal
  await expect(getSellerDropDetail(100, signal)).resolves.toEqual(detail)
  expect(get).toHaveBeenCalledWith('/seller/drops/100', { signal })
})

test.each([
  {
    dropId: 101,
    status: 'DRAFT',
    images: [],
    detailImages: [],
    optionGroups: [],
    options: [],
  },
  {
    dropId: 100,
    status: 'UNKNOWN',
    images: [],
    detailImages: [],
    optionGroups: [],
    options: [],
  },
  { dropId: 100, status: 'DRAFT', images: [], optionGroups: [], options: [] },
])('다른 상품 또는 불완전한 상세 응답을 거부한다: %j', async (detail) => {
  vi.spyOn(api, 'get').mockResolvedValue({
    data: { success: true, data: detail },
  })
  await expect(getSellerDropDetail(100)).rejects.toThrow('상세 응답 형식')
})

test('부분 DRAFT를 생성하고 서버 ID를 반환한다', async () => {
  const post = vi.spyOn(api, 'post').mockResolvedValue({
    data: { success: true, data: { dropId: 100, status: 'DRAFT' } },
  })
  await expect(createSellerDrop({ name: '초안' })).resolves.toMatchObject({
    dropId: 100,
  })
  expect(post).toHaveBeenCalledWith('/seller/drops', { name: '초안' })
})

test('PATCH는 한 용도 삭제와 다른 용도 누락을 그대로 보낸다', async () => {
  const patch = vi.spyOn(api, 'patch').mockResolvedValue({
    data: { success: true, data: { dropId: 100, status: 'DRAFT' } },
  })
  await expect(updateSellerDrop(100, { images: [] })).resolves.toMatchObject({
    dropId: 100,
  })
  expect(patch).toHaveBeenCalledWith('/seller/drops/100', { images: [] })
})

test('공개 요청은 저장된 ID를 사용하고 WISH 응답을 반환한다', async () => {
  const post = vi.spyOn(api, 'post').mockResolvedValue({
    data: { success: true, data: { dropId: 100, status: 'WISH' } },
  })
  await expect(publishSellerDrop(100)).resolves.toMatchObject({
    status: 'WISH',
  })
  expect(post).toHaveBeenCalledWith('/seller/drops/100/publish')
})

test.each([
  { success: false, data: { dropId: 100, status: 'DRAFT' } },
  { success: true, data: { status: 'DRAFT' } },
  { success: true, data: { dropId: -1, status: 'DRAFT' } },
  { success: true, data: { dropId: 1.5, status: 'DRAFT' } },
  { success: true, data: { dropId: 100, status: 'WISH' } },
])('잘못된 생성 응답은 성공으로 처리하지 않는다: %j', async (data) => {
  vi.spyOn(api, 'post').mockResolvedValue({ data })
  await expect(createSellerDrop({})).rejects.toThrow('임시 저장 응답 형식')
})

test('수정·공개 응답의 ID와 상태를 확인한다', async () => {
  vi.spyOn(api, 'patch').mockResolvedValue({
    data: { success: true, data: { dropId: 101, status: 'DRAFT' } },
  })
  await expect(updateSellerDrop(100, {})).rejects.toThrow('수정 응답 형식')
  vi.spyOn(api, 'post').mockResolvedValue({
    data: { success: true, data: { dropId: 100, status: 'DRAFT' } },
  })
  await expect(publishSellerDrop(100)).rejects.toThrow('공개 응답 형식')
})

test('서버 오류를 변형하지 않고 호출자에게 전달한다', async () => {
  const error = new Error('VALIDATION_FAILED')
  vi.spyOn(api, 'patch').mockRejectedValue(error)
  await expect(updateSellerDrop(100, {})).rejects.toBe(error)
})

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
