import { describe, expect, it } from 'vitest'
import { sellerOrders, orderDetails } from '../../order/mock/mockSellerOrders'
import { readSalesQuery } from '../logic/sales'
import { demoSellerDrops } from '../mock/mockSellerDrops'
import { mockSellerSales } from '../mock/mockSellerSales'

describe('판매 실적 Mock', () => {
  it('결제 확정·미취소 주문만 집계하고 주문 건수와 수량을 구분한다', () => {
    const canceled = {
      ...sellerOrders[0],
      orderId: 'canceled',
      canceledAt: '2026-10-13T00:00:00Z',
    }
    const sales = mockSellerSales(
      demoSellerDrops,
      'grab@example.com',
      [...sellerOrders, canceled],
      orderDetails,
    )
    expect(sales.find((sale) => sale.dropId === '201')).toMatchObject({
      orderCount: 3,
      soldQuantity: 4,
      salesAmount: 525000,
    })
    expect(sales.find((sale) => sale.dropId === '203')).toMatchObject({
      orderCount: 1,
      soldQuantity: 1,
      salesAmount: 91000,
    })
  })

  it('종료 DROP의 누적 실적과 소유권·URL 기본값을 보존한다', () => {
    expect(
      mockSellerSales(demoSellerDrops, 'grab@example.com').find(
        (sale) => sale.dropId === '301',
      ),
    ).toMatchObject({
      status: 'ENDED',
      orderCount: 14,
      soldQuantity: 18,
      salesAmount: 1806000,
    })
    expect(mockSellerSales(demoSellerDrops, 'other@example.com')).toEqual([])
    expect(readSalesQuery(new URLSearchParams('status=BAD&page=-1'))).toEqual({
      status: 'ALL',
      page: 0,
      dropId: null,
    })
  })
})
