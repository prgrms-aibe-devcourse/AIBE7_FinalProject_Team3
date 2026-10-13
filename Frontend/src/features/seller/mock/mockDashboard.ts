import { sellerOrders } from '../../order/mock/mockSellerOrders'
import type { SellerMockDrop } from './mockSellerDrops'

export const dashboardDropStatuses = [
  'DRAFT',
  'WISH',
  'GRAB',
  'ENDED',
  'CANCELED',
] as const

export const dashboardOrderStatuses = [
  'PAYMENT_PENDING',
  'PAID',
  'PREPARING',
  'SHIPPED',
  'DELIVERED',
  'EXPIRED',
  'CANCELED',
] as const

export const dashboardPaymentStatuses = [
  'PENDING',
  'SUCCEEDED',
  'FAILED',
  'UNKNOWN',
  'CANCELED',
] as const

// GET /api/v1/seller/dashboard/summary (GR-41). 재고·보정은 별도 Mock 예시다.
// 주문 목록 Mock에는 결제 시도 이력이 없어 결제 건수는 주문과 1:1로 표시된다.
export function mockDashboardSummary(
  drops: SellerMockDrop[],
  ownerEmail: string,
  from = '',
  to = '',
) {
  const owned = drops.filter((drop) => drop.ownerEmail === ownerEmail)
  const periodOrders = sellerOrders.filter((order) => {
    const at = new Date(order.orderedAt).getTime()
    return (
      (!from || at >= new Date(`${from}T00:00:00`).getTime()) &&
      (!to || at <= new Date(`${to}T23:59:59.999`).getTime())
    )
  })
  return {
    dropCounts: Object.fromEntries(
      dashboardDropStatuses.map((status) => [
        status,
        owned.filter((drop) => drop.status === status).length,
      ]),
    ) as Record<(typeof dashboardDropStatuses)[number], number>,
    stockSummary: { available: 100, reserved: 10, sold: 50 },
    reconciliationRequired: 1,
    orderCounts: Object.fromEntries(
      dashboardOrderStatuses.map((status) => [
        status,
        periodOrders.filter((order) => order.orderStatus === status).length,
      ]),
    ) as Record<(typeof dashboardOrderStatuses)[number], number>,
    paymentCounts: Object.fromEntries(
      dashboardPaymentStatuses.map((status) => [
        status,
        periodOrders.filter((order) => order.paymentStatus === status).length,
      ]),
    ) as Record<(typeof dashboardPaymentStatuses)[number], number>,
  }
}

// GET /api/v1/seller/dashboard/upcoming-drops?eventType=START|END&withinMinutes=1440 (GR-38)
export function mockUpcomingDrops(
  drops: SellerMockDrop[],
  ownerEmail: string,
  eventType: 'START' | 'END',
  now = Date.now(),
) {
  return drops
    .filter((drop) => drop.ownerEmail === ownerEmail)
    .flatMap((drop) => {
      const at = eventType === 'START' ? drop.saleStartsAt : drop.saleEndsAt
      if (drop.status !== (eventType === 'START' ? 'WISH' : 'GRAB') || !at)
        return []
      const remaining = new Date(at).getTime() - now
      if (remaining <= 0 || remaining > 86_400_000) return []
      return [
        {
          dropId: drop.id,
          name: drop.name,
          status: drop.status,
          upcomingAt: at,
        },
      ]
    })
    .sort(
      (left, right) =>
        new Date(left.upcomingAt).getTime() -
        new Date(right.upcomingAt).getTime(),
    )
    .slice(0, 3)
}
