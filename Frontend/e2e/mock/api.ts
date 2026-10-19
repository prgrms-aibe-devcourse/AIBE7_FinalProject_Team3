import type { Page, Route } from '@playwright/test'
import { handleDropRoute } from './api/drop'
import { CATEGORIES, datasetFor, type Scenario } from './api/drop/fixtures'
import { handleSellerDashboardRoute } from './api/seller'
import { handleSellerDropStatsRoute } from './api/sellerDropStats'
import {
  createSellerOrdersFixture,
  handleSellerOrdersRoute,
} from './api/sellerOrders'
import { json, type ApiState } from './api/shared'
import { handleWishRoute, initialWishesFor } from './api/wish'

export { FIXED_NOW, LONG_PRODUCT_NAME } from './api/drop/fixtures'
export type { FixtureDrop } from './api/drop/fixtures'

async function handleRoute(
  route: Route,
  state: ApiState,
  scenario: Scenario,
  failureStatus: 'all' | 'GRAB' | null,
) {
  const url = new URL(route.request().url())
  if (url.pathname === '/api/v1/categories') {
    await json(route, { success: true, data: CATEGORIES })
    return
  }
  if (await handleWishRoute(route, state, url)) return
  if (await handleDropRoute(route, state, scenario, failureStatus, url)) return
  if (await handleSellerDashboardRoute(route, url)) return
  if (await handleSellerDropStatsRoute(route, url)) return
  if (await handleSellerOrdersRoute(route, url, state.sellerOrders)) return
  await route.fallback()
}

// 모든 소비자 화면이 공개 API만 호출하도록 기본 응답을 고정한다.
// WISH 상태는 페이지 세션 동안 유지되어 등록·취소가 다음 조회에 반영된다.
export async function installPublicApi(page: Page) {
  const state: ApiState = {
    drops: datasetFor('base'),
    wished: initialWishesFor('base'),
    sellerOrders: createSellerOrdersFixture(),
  }
  await page.route('**/api/v1/**', (route) =>
    handleRoute(route, state, 'base', null),
  )
}

export async function installHomeScenario(
  page: Page,
  scenario: 'empty' | 'failure' | 'many',
) {
  const state: ApiState = {
    drops: datasetFor(scenario),
    wished: initialWishesFor(scenario),
    sellerOrders: createSellerOrdersFixture(),
  }
  await page.route('**/api/v1/**', (route) =>
    handleRoute(route, state, scenario, scenario === 'failure' ? 'GRAB' : null),
  )
}

export async function installCatalogScenario(page: Page, scenario: Scenario) {
  const state: ApiState = {
    drops: datasetFor(scenario),
    wished: initialWishesFor(scenario),
    sellerOrders: createSellerOrdersFixture(),
  }
  await page.route('**/api/v1/**', (route) =>
    handleRoute(route, state, scenario, scenario === 'failure' ? 'all' : null),
  )
}
