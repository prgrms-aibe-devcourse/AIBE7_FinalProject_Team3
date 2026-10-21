import { expect, test } from 'vitest'
import {
  canCancel,
  canPay,
  cancelErrorMessage,
  isPaymentChecking,
  isRefunded,
  toBuyerOrderDetail,
  toBuyerOrderPayments,
  toBuyerOrderSummary,
  type BuyerOrderDetail,
  type BuyerOrderPayment,
} from '../logic/buyerOrderView'

const NOW = new Date('2026-10-01T10:00:00+09:00').getTime()

function axiosError(status: number, code?: string) {
  return {
    isAxiosError: true,
    response: { status, data: code ? { error: { code } } : undefined },
  }
}

function detail(overrides: Partial<BuyerOrderDetail> = {}): BuyerOrderDetail {
  return {
    ...toBuyerOrderDetail({
      orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
      orderNumber: 'ORD-20261001-000001',
      status: 'PAYMENT_PENDING',
      paymentExpiresAt: '2026-10-01T10:10:00+09:00',
    }),
    ...overrides,
  }
}

function payment(
  overrides: Partial<BuyerOrderPayment> = {},
): BuyerOrderPayment {
  return {
    id: 'p1',
    amount: 89000,
    status: 'FAILED',
    reconciliationRequired: false,
    requestedAt: '2026-10-01T09:58:00+09:00',
    paidAt: null,
    canceledAt: null,
    failureMessage: null,
    ...overrides,
  }
}

test('목록 응답을 화면용 요약으로 바꾼다', () => {
  expect(
    toBuyerOrderSummary({
      orderId: 'id-1',
      orderNumber: 'ORD-1',
      productName: '한정판 스니커즈',
      status: 'PAID',
      totalAmount: 261000,
      orderedAt: '2026-09-18T14:00:00+09:00',
    }),
  ).toEqual({
    id: 'id-1',
    orderNumber: 'ORD-1',
    productName: '한정판 스니커즈',
    status: 'PAID',
    totalAmount: 261000,
    orderedAt: '2026-09-18T14:00:00+09:00',
  })
})

test('상세 응답의 배송지·배송 정보 null을 그대로 옮긴다', () => {
  const order = toBuyerOrderDetail({
    orderId: 'id-1',
    status: 'PAID',
    items: [
      {
        productName: '한정판 스니커즈',
        optionName: '코튼 / 롱',
        unitPrice: 129000,
        quantity: 2,
        subtotal: 258000,
      },
    ],
    shippingAddress: {
      recipient: '홍길동',
      phone: '01012345678',
      postalCode: '06236',
      address1: '서울특별시 강남구 테헤란로 1',
    },
    shipping: {},
  })
  expect(order.items).toEqual([
    {
      productName: '한정판 스니커즈',
      optionName: '코튼 / 롱',
      unitPrice: 129000,
      quantity: 2,
      subtotal: 258000,
    },
  ])
  expect(order.shippingAddress).toEqual({
    recipient: '홍길동',
    phone: '01012345678',
    postalCode: '06236',
    address1: '서울특별시 강남구 테헤란로 1',
    address2: null,
    deliveryMemo: null,
  })
  expect(order.shipping).toEqual({
    carrier: null,
    trackingNumber: null,
    deliveredAt: null,
  })
  expect(order.paymentStatus).toBeNull()
  expect(toBuyerOrderDetail({ orderId: 'id-2' }).shippingAddress).toBeNull()
})

test('결제 이력의 보정 필요·실패 사유를 옮긴다', () => {
  expect(
    toBuyerOrderPayments([
      {
        paymentId: 'p1',
        amount: 89000,
        status: 'FAILED',
        reconciliationStatus: 'NONE',
        requestedAt: '2026-10-01T09:58:00+09:00',
        failure: { code: 'REJECT_CARD_PAYMENT', message: '한도초과' },
      },
      {
        paymentId: 'p2',
        amount: 89000,
        status: 'SUCCEEDED',
        reconciliationStatus: 'REQUIRED',
        requestedAt: '2026-10-01T09:59:00+09:00',
        paidAt: '2026-10-01T09:59:10+09:00',
      },
    ]),
  ).toEqual([
    payment({ failureMessage: '한도초과' }),
    payment({
      id: 'p2',
      status: 'SUCCEEDED',
      reconciliationRequired: true,
      requestedAt: '2026-10-01T09:59:00+09:00',
      paidAt: '2026-10-01T09:59:10+09:00',
    }),
  ])
})

test('결제 마감 전 시도 전·실패 주문만 결제할 수 있다', () => {
  expect(canPay(detail(), [], NOW)).toBe(true)
  expect(canPay(detail({ paymentStatus: 'FAILED' }), [payment()], NOW)).toBe(
    true,
  )
  expect(canPay(detail({ paymentStatus: 'UNKNOWN' }), [], NOW)).toBe(false)
  expect(canPay(detail(), [], NOW + 11 * 60_000)).toBe(false)
  expect(
    canPay(detail({ status: 'PAID', paymentExpiresAt: null }), [], NOW),
  ).toBe(false)
  // 가장 최근 시도가 보정 대상이면 결제를 막는다.
  expect(
    canPay(
      detail({ paymentStatus: 'FAILED' }),
      [payment(), payment({ status: 'FAILED', reconciliationRequired: true })],
      NOW,
    ),
  ).toBe(false)
})

test('결과 확인 중 표시는 최근 결제 상태와 보정 여부로 판단한다', () => {
  expect(isPaymentChecking(detail({ paymentStatus: 'PENDING' }), [])).toBe(true)
  expect(isPaymentChecking(detail({ paymentStatus: 'UNKNOWN' }), [])).toBe(true)
  expect(
    isPaymentChecking(detail({ paymentStatus: 'SUCCEEDED' }), [
      payment({ status: 'SUCCEEDED', reconciliationRequired: true }),
    ]),
  ).toBe(true)
  expect(isPaymentChecking(detail({ paymentStatus: 'FAILED' }), [])).toBe(false)
})

test('결제 대기·완료·배송 준비만 취소할 수 있고 승인 대기는 막는다', () => {
  expect(canCancel(detail())).toBe(true)
  expect(canCancel(detail({ paymentStatus: 'UNKNOWN' }))).toBe(true)
  expect(canCancel(detail({ paymentStatus: 'PENDING' }))).toBe(false)
  expect(canCancel(detail({ status: 'PAID' }))).toBe(true)
  expect(canCancel(detail({ status: 'PREPARING' }))).toBe(true)
  for (const status of ['SHIPPED', 'DELIVERED', 'CANCELED', 'EXPIRED'] as const)
    expect(canCancel(detail({ status }))).toBe(false)
})

test('결제 이력에 취소된 시도가 있으면 환불 완료로 본다', () => {
  expect(isRefunded([payment({ status: 'CANCELED' })])).toBe(true)
  expect(isRefunded([payment()])).toBe(false)
})

test('취소 오류는 결제 취소 실패·상태 충돌·불가를 구분한다', () => {
  expect(cancelErrorMessage(axiosError(502, 'PAYMENT_CANCEL_FAILED'))).toBe(
    '결제 취소가 거절됐어요. 주문 상태는 그대로예요. 잠시 후 다시 시도해주세요.',
  )
  expect(cancelErrorMessage(axiosError(409, 'ORDER_STATUS_CONFLICT'))).toBe(
    '결제 승인 또는 취소 결과를 확인하는 중이에요. 잠시 후 주문 상태를 다시 확인해주세요.',
  )
  expect(cancelErrorMessage(axiosError(409, 'ORDER_NOT_CANCELABLE'))).toBe(
    '주문 상태가 바뀌어 취소할 수 없어요.',
  )
  expect(cancelErrorMessage(axiosError(404, 'ORDER_NOT_FOUND'))).toBe(
    '주문을 찾을 수 없어요.',
  )
  expect(cancelErrorMessage(axiosError(401))).toBe('로그인 후 이용해주세요.')
  expect(cancelErrorMessage(new Error('network'))).toBe(
    '주문을 취소하지 못했어요. 잠시 후 다시 시도해주세요.',
  )
})
