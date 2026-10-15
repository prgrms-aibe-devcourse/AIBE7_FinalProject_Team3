import type { Route } from '@playwright/test'
import {
  FIXED_NOW,
  INITIAL_WISHES,
  WISH_NOTICE,
  type FixtureDrop,
  type Scenario,
} from './fixtures'
import { json, type ApiState } from '../shared'

const activeOptions = (drop: FixtureDrop) =>
  drop.options.filter((option) => option.active !== false)

const totalStock = (drop: FixtureDrop) =>
  activeOptions(drop).reduce((sum, option) => sum + option.availableStock, 0)

const isSoldOut = (drop: FixtureDrop) =>
  activeOptions(drop).length === 0 || totalStock(drop) === 0

export const minPrice = (drop: FixtureDrop): number | null => {
  const prices = activeOptions(drop).map((option) => option.unitPrice)
  return prices.length ? Math.min(...prices) : null
}

const wishCountOf = (drop: FixtureDrop, wished: ReadonlySet<number>) =>
  drop.wishCount +
  (wished.has(drop.dropId) ? 1 : 0) -
  (INITIAL_WISHES.has(drop.dropId) ? 1 : 0)

const listItem = (drop: FixtureDrop, wished: ReadonlySet<number>) => ({
  dropId: drop.dropId,
  name: drop.name,
  brandName: drop.brand,
  thumbnailUrl: drop.thumbnailUrl,
  minPrice: minPrice(drop),
  category: { categoryId: drop.categoryId, name: drop.categoryName },
  status: drop.status,
  soldOut: isSoldOut(drop),
  availableStock: totalStock(drop),
  wishCount: wishCountOf(drop, wished),
  saleStartsAt: drop.saleStartsAt,
  saleEndsAt: drop.saleEndsAt,
})

const actions = (drop: FixtureDrop) => {
  const now = FIXED_NOW.getTime()
  const started = Date.parse(drop.saleStartsAt) <= now
  const running = Date.parse(drop.saleEndsAt) > now
  const soldOut = isSoldOut(drop)
  if (drop.status === 'ENDED' || drop.status === 'CANCELED')
    return { wishable: false, wishCancelable: false, orderable: false }
  if (drop.status === 'WISH')
    return started
      ? {
          wishable: false,
          wishCancelable: false,
          orderable: running && !soldOut,
        }
      : { wishable: true, wishCancelable: true, orderable: false }
  return {
    wishable: false,
    wishCancelable: false,
    orderable: running && !soldOut,
  }
}

const detailBody = (drop: FixtureDrop, wished: ReadonlySet<number>) => ({
  dropId: drop.dropId,
  name: drop.name,
  brandName: drop.brand,
  description: drop.description,
  imageUrls: drop.gallery.map((item) => item.imageUrl),
  galleryImages: drop.gallery,
  detailImages: drop.detailImages,
  minPrice: minPrice(drop),
  category: { categoryId: drop.categoryId, name: drop.categoryName },
  status: drop.status,
  soldOut: isSoldOut(drop),
  wishCount: wishCountOf(drop, wished),
  wishNotice: WISH_NOTICE,
  saleStartsAt: drop.saleStartsAt,
  saleEndsAt: drop.saleEndsAt,
  shipping: {
    shippingFee: drop.shippingFee,
    shippingNotice: drop.shippingNotice,
  },
  optionGroups: drop.groups,
  options: activeOptions(drop).map((option) => ({
    optionId: option.optionId,
    selections: option.selections,
    unitPrice: option.unitPrice,
    availableStock: option.availableStock,
    soldOut: option.availableStock === 0,
  })),
  actions: actions(drop),
  serverTime: FIXED_NOW.toISOString(),
})

const stockBody = (drop: FixtureDrop) => ({
  dropId: drop.dropId,
  options: activeOptions(drop).map((option) => ({
    optionId: option.optionId,
    availableStock: option.availableStock,
    soldOut: option.availableStock === 0,
  })),
  serverTime: FIXED_NOW.toISOString(),
})

type SortField =
  'publishedAt' | 'createdAt' | 'saleStartsAt' | 'wishCount' | 'soldQuantity'

const sortValue = (
  drop: FixtureDrop,
  field: SortField,
  wished: ReadonlySet<number>,
) =>
  field === 'wishCount'
    ? wishCountOf(drop, wished)
    : field === 'soldQuantity'
      ? drop.soldQuantity
      : Date.parse(drop[field])

function listResponse(
  dataset: FixtureDrop[],
  params: URLSearchParams,
  wished: ReadonlySet<number>,
) {
  const status = params.get('status')
  const categoryId = params.get('categoryId')
  const keyword = (params.get('keyword') ?? '').trim().toLowerCase()
  const soldOutParam = params.get('soldOut')
  const sort = params.get('sort') ?? 'publishedAt,desc'
  const page = Number(params.get('page') ?? '0')
  const size = Number(params.get('size') ?? '24')
  const [field, direction] = sort.split(',') as [SortField, 'asc' | 'desc']

  const popular = field === 'wishCount' || field === 'soldQuantity'
  let items = dataset.filter(
    (drop) =>
      drop.listed && drop.status !== 'DRAFT' && drop.status !== 'CANCELED',
  )
  // 인기 정렬은 저장 상태가 아니라 서버 시각의 탐색 단계로 후보를 정하므로
  // status 파라미터로 WISH를 먼저 걸러내지 않는다(soldQuantity는 WISH·GRAB 모두 후보).
  if (!popular && status) items = items.filter((drop) => drop.status === status)
  if (categoryId)
    items = items.filter((drop) => String(drop.categoryId) === categoryId)
  if (keyword)
    items = items.filter((drop) => drop.name.toLowerCase().includes(keyword))
  if (soldOutParam)
    items = items.filter(
      (drop) => isSoldOut(drop) === (soldOutParam === 'true'),
    )

  if (popular) {
    const now = FIXED_NOW.getTime()
    items = items.filter((drop) => {
      if (field === 'wishCount')
        return drop.status === 'WISH' && Date.parse(drop.saleStartsAt) > now
      return (
        (drop.status === 'WISH' || drop.status === 'GRAB') &&
        Date.parse(drop.saleStartsAt) <= now &&
        now < Date.parse(drop.saleEndsAt) &&
        totalStock(drop) > 0
      )
    })
  }

  items = [...items].sort((a, b) => {
    const diff = sortValue(a, field, wished) - sortValue(b, field, wished)
    const directed =
      field === 'wishCount' || field === 'soldQuantity'
        ? -diff
        : direction === 'asc'
          ? diff
          : -diff
    return (
      directed ||
      Date.parse(b.publishedAt) - Date.parse(a.publishedAt) ||
      b.dropId - a.dropId
    )
  })

  const totalElements = items.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  return {
    content: items
      .slice(page * size, page * size + size)
      .map((drop) => listItem(drop, wished)),
    page,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : totalPages,
    hasNext: (page + 1) * size < totalElements,
  }
}

export async function handleDropRoute(
  route: Route,
  state: ApiState,
  scenario: Scenario,
  failureStatus: 'all' | 'GRAB' | null,
  url: URL,
): Promise<boolean> {
  const path = url.pathname
  const { drops, wished } = state
  if (path === '/api/v1/drops') {
    const status = url.searchParams.get('status')
    const fails =
      failureStatus === 'all' || (failureStatus === 'GRAB' && status === 'GRAB')
    if (fails)
      await json(route, { success: false, message: 'INVALID_REQUEST' }, 500)
    else if (scenario === 'empty' && status === 'GRAB')
      await json(route, {
        success: true,
        data: {
          content: [],
          page: 0,
          size: 24,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        },
      })
    else
      await json(route, {
        success: true,
        data: listResponse(drops, url.searchParams, wished),
      })
    return true
  }
  const detail = path.match(/^\/api\/v1\/drops\/(\d+)$/)
  if (detail) {
    const drop = drops.find((item) => item.dropId === Number(detail[1]))
    if (!drop || drop.status === 'DRAFT')
      await json(route, { success: false, message: 'DROP_NOT_FOUND' }, 404)
    else await json(route, { success: true, data: detailBody(drop, wished) })
    return true
  }
  const stocks = path.match(/^\/api\/v1\/drops\/(\d+)\/stocks$/)
  if (stocks) {
    const drop = drops.find((item) => item.dropId === Number(stocks[1]))
    if (!drop || drop.status === 'DRAFT')
      await json(route, { success: false, message: 'DROP_NOT_FOUND' }, 404)
    else await json(route, { success: true, data: stockBody(drop) })
    return true
  }
  return false
}
