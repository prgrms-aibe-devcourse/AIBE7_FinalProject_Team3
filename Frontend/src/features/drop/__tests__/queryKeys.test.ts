import { describe, expect, it } from 'vitest'
import { dropKeys } from '../logic/queryKeys'

describe('DROP 쿼리 키', () => {
  it('값이 같으면 객체가 달라도 같은 목록 키를 만든다', () => {
    expect(
      dropKeys.list({
        status: 'WISH',
        categoryId: 1,
        keyword: '머그',
        sort: 'publishedAt,desc',
        page: 0,
        size: 24,
      }),
    ).toEqual(
      dropKeys.list({
        sort: 'publishedAt,desc',
        size: 24,
        page: 0,
        keyword: '머그',
        categoryId: 1,
        status: 'WISH',
      }),
    )
  })

  it('조건이 다르면 다른 키를 만든다', () => {
    const base = {
      status: 'WISH' as const,
      sort: 'publishedAt,desc',
      page: 0,
      size: 24,
    }
    expect(dropKeys.list({ ...base, categoryId: 1 })).not.toEqual(
      dropKeys.list({ ...base, categoryId: 2 }),
    )
    expect(dropKeys.list({ ...base, page: 0 })).not.toEqual(
      dropKeys.list({ ...base, page: 1 }),
    )
  })

  it('홈 영역과 상세·재고 키를 구분한다', () => {
    expect(dropKeys.home('WISH')).not.toEqual(dropKeys.home('GRAB'))
    expect(dropKeys.detail(100)).not.toEqual(dropKeys.stocks(100))
    expect(dropKeys.detail(100)).toEqual(['drops', 'detail', 100])
  })
})
