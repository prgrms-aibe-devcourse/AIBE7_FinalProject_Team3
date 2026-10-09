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

// 카테고리는 Mock의 이름을 보존한다. 실제 API 연동 시 categoryId 변환은 별도 작업이다.
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
