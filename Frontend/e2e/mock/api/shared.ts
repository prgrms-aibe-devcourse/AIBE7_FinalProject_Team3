import type { Route } from '@playwright/test'
import type { FixtureDrop } from './drop/fixtures'
import type { SellerOrdersFixture } from './sellerOrders'

export type ApiState = {
  drops: FixtureDrop[]
  wished: Set<number>
  sellerOrders: SellerOrdersFixture
}

export const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
