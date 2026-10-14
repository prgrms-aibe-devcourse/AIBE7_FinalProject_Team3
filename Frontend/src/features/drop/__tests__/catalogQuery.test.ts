import { describe, expect, it } from 'vitest'
import { HOME_PAGE_SIZE, toDropListParams } from '../logic/catalogQuery'

const categories = [
  { categoryId: 1, name: '패션' },
  { categoryId: 2, name: '리빙' },
]

describe('목록 조회 조건을 API 쿼리로 변환', () => {
  it('카테고리 이름을 categoryId로 변환하고 나머지 조건을 보존한다', () => {
    expect(
      toDropListParams(
        {
          status: 'GRAB',
          category: '리빙',
          keyword: '머그',
          soldOut: false,
          sort: 'soldQuantity,desc',
          page: 2,
        },
        categories,
        24,
      ),
    ).toEqual({
      status: 'GRAB',
      categoryId: 2,
      keyword: '머그',
      soldOut: false,
      sort: 'soldQuantity,desc',
      page: 2,
      size: 24,
    })
  })

  it('카테고리·검색어가 없으면 조건을 보내지 않는다', () => {
    expect(
      toDropListParams({ status: 'WISH', page: 0 }, categories, HOME_PAGE_SIZE),
    ).toEqual({
      status: 'WISH',
      categoryId: undefined,
      keyword: undefined,
      soldOut: undefined,
      sort: 'publishedAt,desc',
      page: 0,
      size: HOME_PAGE_SIZE,
    })
  })

  it('공백뿐인 검색어는 조건에서 뺀다', () => {
    const params = toDropListParams(
      { status: 'WISH', keyword: '   ', page: 0 },
      categories,
      24,
    )
    expect(params.keyword).toBeUndefined()
  })

  it('모르는 카테고리 이름은 전체로 바꾸지 않고 거부한다', () => {
    expect(() =>
      toDropListParams(
        { status: 'WISH', category: '없는카테고리', page: 0 },
        categories,
        24,
      ),
    ).toThrow(RangeError)
  })
})
