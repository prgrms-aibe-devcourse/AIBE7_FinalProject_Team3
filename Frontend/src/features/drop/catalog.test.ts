import { describe, expect, it } from 'vitest'
import { queryCatalog } from './catalog'
import { catalogDrops, type MockCatalogDrop } from './mock/mockCatalog'

const now = Date.UTC(2026, 9, 9)
const at = (hours: number) => new Date(now + hours * 3_600_000).toISOString()
const drop = (
  id: number,
  overrides: Partial<MockCatalogDrop> = {},
): MockCatalogDrop => ({
  ...catalogDrops[0],
  id,
  name: `상품 ${id}`,
  status: 'GRAB',
  category: '패션',
  saleStartsAt: at(-1),
  saleEndsAt: at(1),
  createdAt: at(-4),
  publishedAt: at(-2),
  wishCount: 0,
  soldQuantity: 0,
  skus: [{ id: id * 10, selections: {}, price: 1000, stock: 1 }],
  ...overrides,
})
const ids = (items: MockCatalogDrop[]) => items.map((item) => item.id)

describe('Mock 상품 목록 조회', () => {
  it('상품명만 공백 제거·대소문자 무시·리터럴 부분 일치로 검색한다', () => {
    const source = [
      drop(1, { name: 'Daily 100%_\\', category: '리빙' }),
      drop(2, { name: 'Daily 머그', category: '리빙' }),
      drop(3, { brand: 'Daily 100%_\\', category: '리빙' }),
      drop(4, { name: 'Daily 100%_\\', category: '패션' }),
    ]
    expect(
      ids(
        queryCatalog(
          source,
          {
            status: 'GRAB',
            category: '리빙',
            keyword: ' DAILY 100%_\\ ',
          },
          now,
        ).content,
      ),
    ).toEqual([1])
  })

  it('일반 정렬은 저장 상태를 사용하고 비공개·종료 상품을 제외한다', () => {
    const source = ['WISH', 'GRAB', 'DRAFT', 'CANCELED', 'ENDED'].map(
      (status, i) =>
        drop(i + 1, {
          status: status as MockCatalogDrop['status'],
          saleEndsAt: at(-1),
        }),
    )
    expect(ids(queryCatalog(source, { status: 'GRAB' }, now).content)).toEqual([
      2,
    ])
    expect(ids(queryCatalog(source, { status: 'WISH' }, now).content)).toEqual([
      1,
    ])
  })

  it('인기 WISH는 시작 전 상품만 포함하고 동률은 공개 시각·ID 내림차순이다', () => {
    const source = [
      drop(1, { status: 'WISH', saleStartsAt: at(1), wishCount: 10 }),
      drop(2, { status: 'WISH', saleStartsAt: at(1), wishCount: 10 }),
      drop(3, {
        status: 'WISH',
        saleStartsAt: at(1),
        wishCount: 10,
        publishedAt: at(-1),
      }),
      drop(4, { status: 'WISH', saleStartsAt: at(0), wishCount: 100 }),
      drop(5, { status: 'WISH', saleStartsAt: at(1), wishCount: 0 }),
    ]
    expect(
      ids(
        queryCatalog(source, { status: 'WISH', sort: 'wishCount,desc' }, now)
          .content,
      ),
    ).toEqual([3, 2, 1, 5])
  })

  it('인기 GRAB은 전환 지연 WISH를 포함하고 종료·품절·비활성 재고는 제외한다', () => {
    const source = [
      drop(1, { soldQuantity: 2 }),
      drop(2, { status: 'WISH', saleStartsAt: at(0), soldQuantity: 5 }),
      drop(3, { saleStartsAt: at(1), soldQuantity: 100 }),
      drop(4, { saleEndsAt: at(0), soldQuantity: 100 }),
      drop(5, {
        skus: [{ id: 50, selections: {}, price: 1, stock: 10, active: false }],
      }),
      drop(6, { status: 'ENDED', soldQuantity: 100 }),
      drop(7, { status: 'CANCELED', soldQuantity: 100 }),
      drop(8, { status: 'DRAFT', soldQuantity: 100 }),
      drop(9),
    ]
    expect(
      ids(
        queryCatalog(source, { status: 'GRAB', sort: 'soldQuantity,desc' }, now)
          .content,
      ),
    ).toEqual([2, 1, 9])
  })

  it('품절 여부는 활성 옵션의 가용 재고만 사용한다', () => {
    const source = [
      drop(1),
      drop(2, { skus: [] }),
      drop(3, { skus: [{ id: 30, selections: {}, price: 1, stock: 0 }] }),
      drop(4, {
        skus: [{ id: 40, selections: {}, price: 1, stock: 10, active: false }],
      }),
    ]
    expect(
      ids(queryCatalog(source, { status: 'GRAB', soldOut: true }, now).content),
    ).toEqual([4, 3, 2])
    expect(
      ids(
        queryCatalog(source, { status: 'GRAB', soldOut: false }, now).content,
      ),
    ).toEqual([1])
  })

  it.each(['publishedAt', 'createdAt', 'saleStartsAt'] as const)(
    '%s 날짜 정렬은 동률 ID를 내림차순으로 유지한다',
    (field) => {
      const source = [drop(1), drop(2), drop(3, { [field]: at(-5) })]
      expect(
        ids(
          queryCatalog(source, { status: 'GRAB', sort: `${field},asc` }, now)
            .content,
        ),
      ).toEqual([3, 2, 1])
      expect(
        ids(
          queryCatalog(source, { status: 'GRAB', sort: `${field},desc` }, now)
            .content,
        ),
      ).toEqual([2, 1, 3])
    },
  )

  it('전체 인기순 정렬 후 24개씩 나누고 입력 배열을 변경하지 않는다', () => {
    const source = Array.from({ length: 49 }, (_, i) =>
      drop(i + 1, { soldQuantity: i }),
    )
    const first = queryCatalog(
      source,
      { status: 'GRAB', sort: 'soldQuantity,desc' },
      now,
    )
    const second = queryCatalog(
      source,
      { status: 'GRAB', sort: 'soldQuantity,desc', page: 1 },
      now,
    )
    const last = queryCatalog(
      source,
      { status: 'GRAB', sort: 'soldQuantity,desc', page: 2 },
      now,
    )
    expect(first).toMatchObject({
      page: 0,
      size: 24,
      totalElements: 49,
      totalPages: 3,
      hasNext: true,
    })
    expect(ids(first.content)).toEqual(
      Array.from({ length: 24 }, (_, i) => 49 - i),
    )
    expect(ids(second.content)).toEqual(
      Array.from({ length: 24 }, (_, i) => 25 - i),
    )
    expect(ids(last.content)).toEqual([1])
    expect(last.hasNext).toBe(false)
    expect(source[0].id).toBe(1)
  })

  it('빈 결과와 범위 밖 페이지는 빈 목록으로 반환한다', () => {
    expect(queryCatalog([], { status: 'GRAB' }, now)).toMatchObject({
      content: [],
      totalPages: 0,
      hasNext: false,
    })
    expect(
      queryCatalog([drop(1)], { status: 'GRAB', page: 5 }, now),
    ).toMatchObject({ content: [], page: 5, totalElements: 1, hasNext: false })
  })

  it('잘못된 페이지와 100자를 넘는 검색어는 거부한다', () => {
    for (const page of [-1, 1.5, NaN, Infinity]) {
      expect(() => queryCatalog([], { status: 'GRAB', page }, now)).toThrow(
        RangeError,
      )
    }
    expect(() =>
      queryCatalog([], { status: 'GRAB', keyword: '가'.repeat(101) }, now),
    ).toThrow(RangeError)
  })
})
