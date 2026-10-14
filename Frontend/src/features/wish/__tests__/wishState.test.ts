import { describe, expect, it, vi } from 'vitest'
import {
  WISH_PAGE_SIZE,
  loadMyWishes,
  wishErrorMessage,
} from '../logic/wishState'

describe('내 WISH 전체 순회', () => {
  it('hasNext가 끝날 때까지 size=100으로 조회해 Set을 만든다', async () => {
    const pages = [
      { content: [{ dropId: 1 }, { dropId: 2 }], hasNext: true },
      { content: [{ dropId: 3 }, {}], hasNext: false },
    ]
    const fetchPage = vi.fn(async (page: number) => pages[page])
    await expect(loadMyWishes(fetchPage)).resolves.toEqual(new Set([1, 2, 3]))
    expect(fetchPage).toHaveBeenNthCalledWith(1, 0, WISH_PAGE_SIZE)
    expect(fetchPage).toHaveBeenNthCalledWith(2, 1, WISH_PAGE_SIZE)
  })

  it('한 페이지뿐이면 한 번만 조회한다', async () => {
    const fetchPage = vi.fn(async () => ({
      content: [{ dropId: 10 }],
      hasNext: false,
    }))
    await expect(loadMyWishes(fetchPage)).resolves.toEqual(new Set([10]))
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })
})

describe('WISH 오류 안내', () => {
  const axiosError = (status?: number, message?: string) => ({
    isAxiosError: true,
    response: status ? { status, data: { message } } : undefined,
  })

  const cases: [number, string | undefined, string][] = [
    [409, 'GRAB_ALREADY_STARTED', '이미 판매가 시작되어 WISH할 수 없어요.'],
    [409, 'DROP_NOT_WISHABLE', '지금은 WISH할 수 없는 상품이에요.'],
    [404, 'DROP_NOT_FOUND', '상품을 찾을 수 없어요.'],
    [401, undefined, '로그인 후 WISH를 이용할 수 있어요.'],
    [403, undefined, '로그인 후 WISH를 이용할 수 있어요.'],
  ]

  it.each(cases)(
    '상태 %s·%s를 안내 문구로 바꾼다',
    (status, code, expected) => {
      expect(wishErrorMessage(axiosError(status, code))).toBe(expected)
    },
  )

  it('알 수 없는 오류는 일반 안내로 처리한다', () => {
    expect(wishErrorMessage(new Error('boom'))).toBe(
      'WISH 처리에 실패했어요. 잠시 후 다시 시도해주세요.',
    )
  })
})
