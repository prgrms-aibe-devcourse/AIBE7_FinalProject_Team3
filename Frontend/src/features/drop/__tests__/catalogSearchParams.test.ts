import { describe, expect, it } from 'vitest'
import {
  changeCatalogParams,
  readCatalogQuery,
  resolveCategoryId,
} from '../logic/catalogSearchParams'

describe('상품 목록 URL 조건', () => {
  it('조건이 없으면 최신 공개순 첫 페이지로 조회한다', () => {
    expect(readCatalogQuery(new URLSearchParams(), 'WISH')).toEqual({
      status: 'WISH',
      sort: 'publishedAt,desc',
      page: 0,
      category: undefined,
      keyword: '',
      soldOut: undefined,
    })
  })

  it('검색·카테고리·정렬·품절·페이지를 직접 진입 URL에서 읽는다', () => {
    const params = new URLSearchParams({
      category: '리빙',
      keyword: '머그',
      sort: 'soldQuantity,desc',
      soldOut: 'false',
      page: '2',
    })
    expect(readCatalogQuery(params, 'GRAB')).toMatchObject({
      category: '리빙',
      keyword: '머그',
      sort: 'soldQuantity,desc',
      soldOut: false,
      page: 2,
    })
  })

  it.each([
    'page=-1',
    'page=1.5',
    'page=',
    'page=9007199254740992',
    'sort=wishCount,asc',
    'sort=soldQuantity,desc',
    'soldOut=yes',
  ])('잘못된 WISH 조건을 거부한다: %s', (query) => {
    expect(() => readCatalogQuery(new URLSearchParams(query), 'WISH')).toThrow(
      RangeError,
    )
  })

  it('상태에 맞지 않는 인기 정렬과 긴 검색어를 거부한다', () => {
    expect(() =>
      readCatalogQuery(new URLSearchParams('sort=wishCount,desc'), 'GRAB'),
    ).toThrow(RangeError)
    expect(() =>
      readCatalogQuery(
        new URLSearchParams({ keyword: '가'.repeat(101) }),
        'GRAB',
      ),
    ).toThrow(RangeError)
  })

  it.each(['category', 'keyword', 'sort', 'soldOut'] as const)(
    '%s 변경 시 다른 조건을 보존하고 첫 페이지로 이동한다',
    (key) => {
      const original = new URLSearchParams(
        'page=2&keyword=머그&category=리빙&sort=publishedAt,asc&soldOut=false',
      )
      const changed = changeCatalogParams(original, key, '')
      expect(changed.has('page')).toBe(false)
      expect(changed.has(key)).toBe(false)
      for (const [name, value] of original) {
        if (name !== key && name !== 'page')
          expect(changed.get(name)).toBe(value)
      }
      expect(original.get('page')).toBe('2')
    },
  )

  it('페이지 변경 시 조회 조건을 보존한다', () => {
    const changed = changeCatalogParams(
      new URLSearchParams('keyword=머그&category=리빙'),
      'page',
      '1',
    )
    expect(readCatalogQuery(changed, 'GRAB')).toMatchObject({
      page: 1,
      keyword: '머그',
      category: '리빙',
    })
  })
})

describe('카테고리 이름 → categoryId 매핑', () => {
  const categories = [
    { categoryId: 1, code: 'FASHION', name: '패션' },
    { categoryId: 2, code: 'LIVING', name: '리빙' },
  ]

  it('이름이 없으면 조건 없이 전체를 조회한다', () => {
    expect(resolveCategoryId(undefined, categories)).toBeUndefined()
    expect(resolveCategoryId('', categories)).toBeUndefined()
  })

  it('이름과 일치하는 categoryId를 반환한다', () => {
    expect(resolveCategoryId('리빙', categories)).toBe(2)
  })

  it('모르는 이름은 전체로 바꾸지 않고 잘못된 조건으로 거부한다', () => {
    expect(() => resolveCategoryId('없는카테고리', categories)).toThrow(
      RangeError,
    )
  })

  it('이름은 찾았지만 categoryId가 없으면 거부한다', () => {
    expect(() => resolveCategoryId('패션', [{ name: '패션' }])).toThrow(
      RangeError,
    )
  })
})
