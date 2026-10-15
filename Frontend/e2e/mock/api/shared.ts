import type { Route } from '@playwright/test'
import type { FixtureDrop } from './drop/fixtures'

export type ApiState = {
  drops: FixtureDrop[]
  wished: Set<number>
}

export const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
