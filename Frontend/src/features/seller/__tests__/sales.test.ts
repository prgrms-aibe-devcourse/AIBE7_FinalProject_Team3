import { expect, test } from 'vitest'
import { readSalesQuery } from '../logic/sales'

test('dropId가 있으면 page를 0으로 정규화한다', () => {
  const params = new URLSearchParams('status=ENDED&dropId=301&page=3')
  expect(readSalesQuery(params)).toEqual({
    status: 'ENDED',
    page: 0,
    dropId: '301',
  })
})

test('dropId가 없으면 기존 page·status 유효성 검사를 유지한다', () => {
  const params = new URLSearchParams('status=GRAB&page=2')
  expect(readSalesQuery(params)).toEqual({
    status: 'GRAB',
    page: 2,
    dropId: null,
  })

  const invalidPage = new URLSearchParams('page=-1')
  expect(readSalesQuery(invalidPage)).toEqual({
    status: 'ALL',
    page: 0,
    dropId: null,
  })
})
