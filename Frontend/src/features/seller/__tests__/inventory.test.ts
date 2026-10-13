import { describe, expect, it } from 'vitest'
import {
  inventoryTotals,
  optionLabel,
  readInventoryQuery,
  stockBreakdown,
} from '../logic/inventory'
import { demoSellerDrops } from '../mock/mockSellerDrops'

describe('판매자 재고 Mock', () => {
  it('비활성 옵션과 0재고를 포함해 상품 합계를 계산한다', () => {
    const drop = demoSellerDrops.find((item) => item.id === '201')!
    expect(inventoryTotals(drop)).toEqual({
      available: 19,
      reserved: 3,
      sold: 39,
    })
    expect(stockBreakdown(drop.skus[3])).toEqual({
      available: 0,
      reserved: 0,
      sold: 12,
      total: 12,
    })
    expect(optionLabel(drop, drop.skus[3])).toBe('와이드 / 롱')
  })

  it('종료 상품의 판매량과 잘못된 URL 조건을 구분한다', () => {
    const ended = demoSellerDrops.find((item) => item.id === '301')!
    expect(inventoryTotals(ended)).toEqual({
      available: 0,
      reserved: 0,
      sold: 18,
    })
    expect(ended.skus).toHaveLength(0)
    expect(
      readInventoryQuery(new URLSearchParams('status=BAD&page=-1&dropId=201')),
    ).toEqual({
      status: 'ALL',
      page: 0,
      dropId: '201',
    })
  })
})
