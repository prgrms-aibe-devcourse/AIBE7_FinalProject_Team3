import type { Route } from '@playwright/test'
import { minPrice } from '../drop'
import {
  daysAgo,
  FIXED_NOW,
  INITIAL_WISHES,
  WISH_NOTICE,
  type FixtureDrop,
  type Scenario,
} from '../drop/fixtures'
import { json, type ApiState } from '../shared'

const wishListItem = (drop: FixtureDrop) => ({
  dropId: drop.dropId,
  name: drop.name,
  thumbnailUrl: drop.thumbnailUrl,
  minPrice: minPrice(drop),
  status: drop.status,
  saleStartsAt: drop.saleStartsAt,
  wishedAt: daysAgo(1),
})

function wishListResponse(state: ApiState, params: URLSearchParams) {
  const page = Number(params.get('page') ?? '0')
  const size = Number(params.get('size') ?? '20')
  const items = state.drops.filter((drop) => state.wished.has(drop.dropId))
  const totalElements = items.length
  return {
    content: items.slice(page * size, page * size + size).map(wishListItem),
    page,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / size),
    hasNext: (page + 1) * size < totalElements,
  }
}

// 등록·취소 판정은 명세(WISH.md 1.1·1.2)와 서버 시각을 따른다.
function wishError(
  drop: FixtureDrop | undefined,
): { status: number; code: string } | null {
  if (!drop || drop.status === 'DRAFT')
    return { status: 404, code: 'DROP_NOT_FOUND' }
  if (drop.status === 'CANCELED')
    return { status: 409, code: 'DROP_NOT_WISHABLE' }
  if (drop.status === 'GRAB' || drop.status === 'ENDED' || drop.rejectWish)
    return { status: 409, code: 'GRAB_ALREADY_STARTED' }
  if (Date.parse(drop.saleStartsAt) <= FIXED_NOW.getTime())
    return { status: 409, code: 'GRAB_ALREADY_STARTED' }
  return null
}

export async function handleWishRoute(
  route: Route,
  state: ApiState,
  url: URL,
): Promise<boolean> {
  const path = url.pathname
  const { drops, wished } = state
  if (path === '/api/v1/users/me/wishes') {
    await json(route, {
      success: true,
      data: wishListResponse(state, url.searchParams),
    })
    return true
  }
  const wish = path.match(/^\/api\/v1\/drops\/(\d+)\/wish$/)
  if (wish) {
    const drop = drops.find((item) => item.dropId === Number(wish[1]))
    const error = wishError(drop)
    if (route.request().method() === 'PUT') {
      if (error) {
        await json(route, { success: false, message: error.code }, error.status)
        return true
      }
      wished.add(Number(wish[1]))
      await json(route, {
        success: true,
        data: {
          dropId: Number(wish[1]),
          wished: true,
          wishedAt: FIXED_NOW.toISOString(),
          notice: WISH_NOTICE,
        },
      })
      return true
    }
    if (route.request().method() === 'DELETE') {
      if (error) {
        await json(route, { success: false, message: error.code }, error.status)
        return true
      }
      wished.delete(Number(wish[1]))
      await route.fulfill({ status: 204, body: '' })
      return true
    }
  }
  return false
}

export function initialWishesFor(scenario: Scenario): Set<number> {
  if (scenario === 'no-wishes') return new Set()
  if (scenario === 'wishes')
    return new Set([
      ...INITIAL_WISHES,
      ...Array.from({ length: 23 }, (_, index) => 3000 + index),
    ])
  return new Set(INITIAL_WISHES)
}
