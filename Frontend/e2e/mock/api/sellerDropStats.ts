import type { Route } from '@playwright/test'
import { json } from './shared'

export type SellerDropStat = {
  dropId: number
  name: string
  status: 'DRAFT' | 'WISH' | 'GRAB' | 'ENDED' | 'CANCELED'
  saleEndsAt: string | null
  activeWishCount: number
  availableStock: number
  reservedStock: number
  soldStock: number
  orderCount: number
  soldQuantity: number
  salesAmount: number
}

// id 내림차순(실제 API와 동일)
export const SELLER_DROP_STATS: SellerDropStat[] = [
  {
    dropId: 403,
    name: '리미티드 키링 세트',
    status: 'CANCELED',
    saleEndsAt: null,
    activeWishCount: 0,
    availableStock: 30,
    reservedStock: 0,
    soldStock: 0,
    orderCount: 0,
    soldQuantity: 0,
    salesAmount: 0,
  },
  {
    dropId: 301,
    name: '가을 코듀로이 셔츠',
    status: 'ENDED',
    saleEndsAt: '2026-10-13T00:00:00Z',
    activeWishCount: 0,
    availableStock: 0,
    reservedStock: 0,
    soldStock: 18,
    orderCount: 14,
    soldQuantity: 18,
    salesAmount: 1806000,
  },
  {
    dropId: 202,
    name: '슬로우 모닝 머그',
    status: 'GRAB',
    saleEndsAt: '2026-10-20T00:00:00Z',
    activeWishCount: 142,
    availableStock: 32,
    reservedStock: 0,
    soldStock: 0,
    orderCount: 0,
    soldQuantity: 0,
    salesAmount: 0,
  },
  {
    dropId: 201,
    name: '뮤직 클럽 티셔츠 · 리미티드',
    status: 'GRAB',
    saleEndsAt: '2026-10-22T00:00:00Z',
    activeWishCount: 210,
    availableStock: 19,
    reservedStock: 3,
    soldStock: 39,
    orderCount: 23,
    soldQuantity: 39,
    salesAmount: 983000,
  },
  {
    dropId: 101,
    name: '시그니처 캔들',
    status: 'DRAFT',
    saleEndsAt: null,
    activeWishCount: 0,
    availableStock: 0,
    reservedStock: 0,
    soldStock: 0,
    orderCount: 0,
    soldQuantity: 0,
    salesAmount: 0,
  },
]

export const SELLER_DROP_STOCKS: Record<
  number,
  {
    optionId: number
    optionName: string
    totalStock: number
    availableStock: number
    reservedStock: number
    soldStock: number
    active: boolean
  }[]
> = {
  201: [
    {
      optionId: 2011,
      optionName: '릴랙스 / 하프',
      totalStock: 24,
      availableStock: 8,
      reservedStock: 2,
      soldStock: 14,
      active: true,
    },
    {
      optionId: 2012,
      optionName: '릴랙스 / 롱',
      totalStock: 15,
      availableStock: 5,
      reservedStock: 1,
      soldStock: 9,
      active: true,
    },
    {
      optionId: 2013,
      optionName: '와이드 / 하프',
      totalStock: 10,
      availableStock: 6,
      reservedStock: 0,
      soldStock: 4,
      active: true,
    },
    {
      optionId: 2014,
      optionName: '와이드 / 롱',
      totalStock: 12,
      availableStock: 0,
      reservedStock: 0,
      soldStock: 12,
      active: false,
    },
  ],
  301: [],
}

const pageSize = 4

export async function handleSellerDropStatsRoute(
  route: Route,
  url: URL,
): Promise<boolean> {
  if (url.pathname === '/api/v1/seller/dashboard/drops') {
    const status = url.searchParams.get('status')
    const dropId = url.searchParams.get('dropId')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? String(pageSize))
    const filtered = SELLER_DROP_STATS.filter(
      (drop) =>
        (!status || drop.status === status) &&
        (!dropId || drop.dropId === Number(dropId)),
    )
    const content = filtered.slice(page * size, page * size + size)
    await json(route, {
      success: true,
      data: {
        content,
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
        hasNext: (page + 1) * size < filtered.length,
      },
    })
    return true
  }
  const stocksMatch = url.pathname.match(
    /^\/api\/v1\/seller\/drops\/(\d+)\/stocks$/,
  )
  if (stocksMatch) {
    const dropId = Number(stocksMatch[1])
    const exists = SELLER_DROP_STATS.some((drop) => drop.dropId === dropId)
    if (!exists) {
      await json(route, { success: false, message: 'DROP_NOT_FOUND' }, 404)
      return true
    }
    await json(route, {
      success: true,
      data: { dropId, options: SELLER_DROP_STOCKS[dropId] ?? [] },
    })
    return true
  }
  return false
}
