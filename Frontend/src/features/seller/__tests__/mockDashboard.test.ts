import { describe, expect, it } from 'vitest'
import { demoSellerDrops } from '../mock/mockSellerDrops'
import { mockDashboardSummary, mockUpcomingDrops } from '../mock/mockDashboard'

describe('판매자 종합 Mock', () => {
  it('기간 변경은 주문·결제 건수에만 적용한다', () => {
    const all = mockDashboardSummary(demoSellerDrops, 'grab@example.com')
    const past = mockDashboardSummary(
      demoSellerDrops,
      'grab@example.com',
      '2020-01-01',
      '2020-01-02',
    )
    expect(past.dropCounts).toEqual(all.dropCounts)
    expect(past.stockSummary).toEqual(all.stockSummary)
    expect(past.reconciliationRequired).toBe(all.reconciliationRequired)
    expect(past.orderCounts.PAID).toBe(0)
    expect(past.paymentCounts.SUCCEEDED).toBe(0)
    expect(all.orderCounts.PAID).toBeGreaterThan(0)
  })

  it('임박 일정은 본인 DROP만 시각순으로 표시한다', () => {
    const now = Date.now()
    const drops = [
      {
        ...demoSellerDrops[0],
        id: 'a',
        status: 'WISH' as const,
        saleStartsAt: new Date(now + 60_000).toISOString(),
      },
      {
        ...demoSellerDrops[0],
        id: 'b',
        status: 'WISH' as const,
        saleStartsAt: new Date(now + 30_000).toISOString(),
      },
      {
        ...demoSellerDrops[0],
        id: 'c',
        ownerEmail: 'other@example.com',
        status: 'WISH' as const,
        saleStartsAt: new Date(now + 10_000).toISOString(),
      },
    ]
    expect(
      mockUpcomingDrops(drops, 'grab@example.com', 'START', now).map(
        (drop) => drop.dropId,
      ),
    ).toEqual(['b', 'a'])
    expect(mockUpcomingDrops(drops, 'grab@example.com', 'END', now)).toEqual([])
  })
})
