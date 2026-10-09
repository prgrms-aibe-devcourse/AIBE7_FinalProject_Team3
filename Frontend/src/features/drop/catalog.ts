import type { MockCatalogDrop } from './mockCatalog'

export const CATALOG_PAGE_SIZE = 24

export type CatalogDateSort =
  `${'publishedAt' | 'saleStartsAt' | 'createdAt'},${'asc' | 'desc'}`
export type CatalogQuery = {
  category?: string
  keyword?: string
  soldOut?: boolean
  page?: number
} & (
  | { status: 'WISH'; sort?: CatalogDateSort | 'wishCount,desc' }
  | { status: 'GRAB'; sort?: CatalogDateSort | 'soldQuantity,desc' }
)

const availableStock = (drop: MockCatalogDrop) =>
  drop.skus.reduce(
    (sum, sku) => sum + (sku.active === false ? 0 : sku.stock),
    0,
  )
const time = (at: string) => new Date(at).getTime()

// 전체 Mock에 조건을 적용한 뒤 페이지를 나눈다. 실제 API 응답을 재정렬하는 데 쓰지 않는다.
export function queryCatalog(
  source: readonly MockCatalogDrop[],
  query: CatalogQuery,
  now = Date.now(),
) {
  const keyword = (query.keyword ?? '').trim().toLowerCase()
  const page = query.page ?? 0
  if (keyword.length > 100 || !Number.isSafeInteger(page) || page < 0) {
    throw new RangeError(
      '검색어는 100자 이내, 페이지는 0 이상의 정수로 지정해주세요.',
    )
  }
  const sort = query.sort ?? 'publishedAt,desc'
  const [field, direction] = sort.split(',')
  const items = source.filter((drop) => {
    const stock = availableStock(drop)
    const matchesStatus =
      field === 'wishCount'
        ? drop.status === 'WISH' && now < time(drop.saleStartsAt)
        : field === 'soldQuantity'
          ? (drop.status === 'WISH' || drop.status === 'GRAB') &&
            time(drop.saleStartsAt) <= now &&
            now < time(drop.saleEndsAt) &&
            stock > 0
          : drop.status === query.status
    return (
      matchesStatus &&
      (!query.category || drop.category === query.category) &&
      drop.name.toLowerCase().includes(keyword) &&
      (query.soldOut === undefined || (stock === 0) === query.soldOut)
    )
  })
  items.sort((a, b) => {
    if (field === 'wishCount' || field === 'soldQuantity') {
      return (
        b[field] - a[field] ||
        time(b.publishedAt) - time(a.publishedAt) ||
        b.id - a.id
      )
    }
    const dateField = field as 'publishedAt' | 'saleStartsAt' | 'createdAt'
    const difference = time(a[dateField]) - time(b[dateField])
    return (direction === 'asc' ? difference : -difference) || b.id - a.id
  })
  return {
    content: items.slice(
      page * CATALOG_PAGE_SIZE,
      (page + 1) * CATALOG_PAGE_SIZE,
    ),
    page,
    size: CATALOG_PAGE_SIZE,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / CATALOG_PAGE_SIZE),
    hasNext: (page + 1) * CATALOG_PAGE_SIZE < items.length,
  }
}
