import { expect, test } from 'vitest'
import { sellerOrderErrorMessage } from '../logic/sellerOrderState'

function axiosError(status: number) {
  return { isAxiosError: true, response: { status } }
}

test('401·403은 권한 안내, 404는 찾을 수 없음 안내를 반환한다', () => {
  expect(sellerOrderErrorMessage(axiosError(401))).toBe(
    '판매자 주문을 볼 권한이 없어요.',
  )
  expect(sellerOrderErrorMessage(axiosError(403))).toBe(
    '판매자 주문을 볼 권한이 없어요.',
  )
  expect(sellerOrderErrorMessage(axiosError(404))).toBe(
    '요청한 주문 또는 DROP을 찾을 수 없어요.',
  )
  expect(sellerOrderErrorMessage(axiosError(500))).toBe(
    '처리하지 못했어요. 잠시 후 다시 시도해주세요.',
  )
  expect(sellerOrderErrorMessage(new Error('network'))).toBe(
    '처리하지 못했어요. 잠시 후 다시 시도해주세요.',
  )
})
