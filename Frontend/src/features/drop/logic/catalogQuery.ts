import type { CatalogQuery } from './catalog'
import { resolveCategoryId } from './catalogSearchParams'
import type { DropListParams } from './queryKeys'

// 메인 인기 영역은 각각 첫 페이지만 조회한다.
export const HOME_PAGE_SIZE = 8

// URL 조회 조건을 API 쿼리로 옮긴다. 카테고리는 이름을 categoryId로 변환하고,
// 모르는 이름은 전체 목록으로 바꾸지 않고 잘못된 조회 조건으로 거부한다.
export function toDropListParams(
  query: CatalogQuery,
  categories: readonly { categoryId?: number; name?: string }[],
  size: number,
): DropListParams {
  const keyword = (query.keyword ?? '').trim()
  return {
    status: query.status,
    categoryId: resolveCategoryId(query.category, categories),
    keyword: keyword || undefined,
    soldOut: query.soldOut,
    sort: query.sort ?? 'publishedAt,desc',
    page: query.page ?? 0,
    size,
  }
}

// 목록 화면의 조회 조건을 한 번에 판정한다.
// 조건이 틀리거나 카테고리를 찾지 못하면 null(잘못된 조건), 카테고리를 아직 못 받았으면 undefined(대기)다.
export function resolveListParams(
  query: CatalogQuery | null,
  categories: readonly { categoryId?: number; name?: string }[] | undefined,
  size: number,
): DropListParams | null | undefined {
  if (!query) return null
  if (!categories) return undefined
  try {
    return toDropListParams(query, categories, size)
  } catch {
    return null
  }
}
