import { describe, expect, it } from 'vitest'
import {
  changeSellerOrderParams,
  querySellerOrders,
  readSellerOrderQuery,
} from '../logic/sellerOrderQuery'
import { sellerOrders } from '../mock/mockSellerOrders'

describe('판매자 주문 조회 조건', () => {
  it('상태·결제·DROP을 함께 적용하고 페이지를 자른다', () => {
    const params = new URLSearchParams(
      'orderStatus=PAID&paymentStatus=SUCCEEDED&dropId=201',
    )
    const result = querySellerOrders(sellerOrders, params)
    expect(result.totalElements).toBe(1)
    expect(result.content.map((order) => order.orderNumber)).toEqual([
      'ORD-20260918-000500',
    ])
    expect(
      querySellerOrders(sellerOrders, new URLSearchParams('page=1')).content,
    ).toHaveLength(1)
  })

  it('필터 변경 시 페이지를 초기화하고 다른 조건을 보존한다', () => {
    const next = changeSellerOrderParams(
      new URLSearchParams('dropId=201&page=1'),
      'orderStatus',
      'SHIPPED',
    )
    expect(next.toString()).toBe('dropId=201&orderStatus=SHIPPED')
  })

  it('잘못된 상태·페이지·DROP을 거부한다', () => {
    for (const query of [
      'orderStatus=NOPE',
      'page=-1',
      'page=1.5',
      'dropId=abc',
    ]) {
      expect(() => readSellerOrderQuery(new URLSearchParams(query))).toThrow(
        RangeError,
      )
    }
  })
})
