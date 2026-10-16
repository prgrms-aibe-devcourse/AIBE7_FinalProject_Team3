import type { Route } from '@playwright/test'
import { FIXED_NOW } from './drop/fixtures'
import { json } from './shared'

const SUMMARY = {
  dropCounts: { DRAFT: 1, WISH: 2, GRAB: 1, ENDED: 3, CANCELED: 0 },
  orderCounts: {
    PAYMENT_PENDING: 0,
    PAID: 3,
    PREPARING: 1,
    SHIPPED: 0,
    DELIVERED: 2,
    EXPIRED: 0,
    CANCELED: 0,
  },
  paymentCounts: {
    PENDING: 0,
    SUCCEEDED: 6,
    FAILED: 0,
    UNKNOWN: 0,
    CANCELED: 0,
  },
  reconciliationRequired: 1,
  stockSummary: { available: 100, reserved: 10, sold: 50 },
}

const UPCOMING = [
  {
    dropId: 101,
    name: '시그니처 캔들',
    status: 'WISH',
    upcomingAt: new Date(FIXED_NOW.getTime() + 30 * 60_000).toISOString(),
  },
]

export async function handleSellerDashboardRoute(
  route: Route,
  url: URL,
): Promise<boolean> {
  if (url.pathname === '/api/v1/seller/dashboard/summary') {
    await json(route, { success: true, data: SUMMARY })
    return true
  }
  if (url.pathname === '/api/v1/seller/dashboard/upcoming-drops') {
    const eventType = url.searchParams.get('eventType')
    await json(route, {
      success: true,
      data: eventType === 'END' ? [] : UPCOMING,
    })
    return true
  }
  return false
}
