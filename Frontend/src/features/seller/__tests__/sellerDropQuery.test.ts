import { expect, test } from 'vitest'
import { readSellerDropQuery } from '../logic/sellerDropQuery'
import { readInventoryQuery } from '../logic/inventory'

test('상태와 페이지를 URL에서 읽고 잘못된 조건은 기본값으로 돌린다', () => {
  expect(
    readSellerDropQuery(new URLSearchParams('status=DRAFT&page=2')),
  ).toEqual({ status: 'DRAFT', page: 2 })
  expect(
    readSellerDropQuery(new URLSearchParams('status=BAD&page=-1')),
  ).toEqual({ status: 'ALL', page: 0 })
  expect(readSellerDropQuery(new URLSearchParams('page=1.5'))).toEqual({
    status: 'ALL',
    page: 0,
  })
})

test('재고 화면의 DROP 필터는 공통 상태·페이지와 함께 유지한다', () => {
  expect(
    readInventoryQuery(new URLSearchParams('status=GRAB&page=2&dropId=201')),
  ).toEqual({ status: 'GRAB', page: 2, dropId: '201' })
})
