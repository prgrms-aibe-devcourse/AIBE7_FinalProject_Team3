import { describe, expect, it } from 'vitest'
import {
  changeSellerOrderParams,
  readSellerOrderQuery,
} from '../logic/sellerOrderQuery'

describe('판매자 주문 조회 조건', () => {
  it('상태·결제·DROP·페이지를 읽고 기본값(orderStatus=PAID)을 적용한다', () => {
    expect(
      readSellerOrderQuery(
        new URLSearchParams(
          'orderStatus=SHIPPED&paymentStatus=SUCCEEDED&dropId=201&page=1',
        ),
      ),
    ).toEqual({
      orderStatus: 'SHIPPED',
      paymentStatus: 'SUCCEEDED',
      dropId: '201',
      page: 1,
    })
    expect(readSellerOrderQuery(new URLSearchParams('')).orderStatus).toBe(
      'PAID',
    )
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
