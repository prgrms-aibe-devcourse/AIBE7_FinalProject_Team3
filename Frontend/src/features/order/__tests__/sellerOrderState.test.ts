import { expect, test } from 'vitest'
import {
  sellerOrderErrorMessage,
  shipmentErrorMessage,
} from '../logic/sellerOrderState'

function axiosError(status: number, code?: string) {
  return {
    isAxiosError: true,
    response: { status, data: code ? { error: { code } } : undefined },
  }
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

test('배송 액션 오류는 코드별로 다른 안내를 반환한다', () => {
  expect(shipmentErrorMessage(axiosError(403))).toBe(
    '판매자 권한으로 로그인한 뒤 이용할 수 있어요.',
  )
  expect(shipmentErrorMessage(axiosError(404))).toBe(
    '주문을 찾을 수 없어요. 목록을 다시 확인해주세요.',
  )
  expect(
    shipmentErrorMessage(axiosError(409, 'PAYMENT_CANCELLATION_UNKNOWN')),
  ).toBe('결제 취소를 확인하는 중이라 지금은 처리할 수 없어요.')
  expect(shipmentErrorMessage(axiosError(409, 'ORDER_STATUS_CONFLICT'))).toBe(
    '주문 상태가 바뀌었어요. 목록을 다시 확인해주세요.',
  )
  expect(
    shipmentErrorMessage(axiosError(409, 'INVALID_STATE_TRANSITION')),
  ).toBe('주문 상태가 바뀌었어요. 목록을 다시 확인해주세요.')
  expect(
    shipmentErrorMessage(axiosError(409, 'DUPLICATE_IDEMPOTENCY_KEY')),
  ).toBe('이전 요청과 내용이 달라요. 다시 시도해주세요.')
  expect(shipmentErrorMessage(axiosError(500))).toBe(
    '배송 작업을 처리하지 못했어요. 다시 시도해주세요.',
  )
  expect(shipmentErrorMessage(new Error('network'))).toBe(
    '처리하지 못했어요. 잠시 후 다시 시도해주세요.',
  )
})
