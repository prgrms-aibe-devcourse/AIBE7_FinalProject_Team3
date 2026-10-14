import type { CatalogDateSort, CatalogQuery } from './catalog'

const dateSorts: CatalogDateSort[] = [
  'publishedAt,desc',
  'publishedAt,asc',
  'saleStartsAt,desc',
  'saleStartsAt,asc',
  'createdAt,desc',
  'createdAt,asc',
]

export function readCatalogQuery(
  params: URLSearchParams,
  status: 'WISH' | 'GRAB',
): CatalogQuery {
  const sort = params.get('sort') ?? 'publishedAt,desc'
  const pageText = params.get('page') ?? '0'
  const page = Number(pageText)
  const keyword = params.get('keyword') ?? ''
  const soldOut = params.get('soldOut')
  const popularSort = status === 'WISH' ? 'wishCount,desc' : 'soldQuantity,desc'
  if (
    (!dateSorts.includes(sort as CatalogDateSort) && sort !== popularSort) ||
    !/^\d+$/.test(pageText) ||
    !Number.isSafeInteger(page) ||
    keyword.trim().length > 100 ||
    (soldOut !== null && soldOut !== 'true' && soldOut !== 'false')
  ) {
    throw new RangeError('상품 목록의 조회 조건이 올바르지 않습니다.')
  }
  const filters = {
    category: params.get('category') || undefined,
    keyword,
    page,
    soldOut: soldOut === null ? undefined : soldOut === 'true',
  }
  return status === 'WISH'
    ? { ...filters, status, sort: sort as CatalogDateSort | 'wishCount,desc' }
    : {
        ...filters,
        status,
        sort: sort as CatalogDateSort | 'soldQuantity,desc',
      }
}

// URL의 category는 카테고리 이름을 보존한다. API 호출 시 이 이름을 categoryId로 변환한다.
// 모르는 이름은 전체 목록으로 바꾸지 않고 잘못된 조회 조건으로 알린다.
export function resolveCategoryId(
  categoryName: string | undefined,
  categories: readonly { categoryId?: number; name?: string }[],
): number | undefined {
  if (!categoryName) return undefined
  const match = categories.find((category) => category.name === categoryName)
  if (!match || match.categoryId === undefined) {
    throw new RangeError('상품 목록의 조회 조건이 올바르지 않습니다.')
  }
  return match.categoryId
}

export function changeCatalogParams(
  current: URLSearchParams,
  key: 'category' | 'keyword' | 'sort' | 'soldOut' | 'page',
  value: string,
) {
  const next = new URLSearchParams(current)
  if (value) next.set(key, value)
  else next.delete(key)
  if (key !== 'page') next.delete('page')
  return next
}
