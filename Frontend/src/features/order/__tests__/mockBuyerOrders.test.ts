import { describe, expect, it } from 'vitest'
import {
  canCancel,
  canPay,
  demoBuyerOrders,
  withCancelExample,
  withPaymentExample,
} from '../mock/mockBuyerOrders'

const scenario = (id: string) =>
  demoBuyerOrders.find((order) => order.id === id)!

describe('구매자 주문 Mock 상태', () => {
  it('결제 대기 또는 명확한 실패이며 마감 전일 때만 재결제를 허용한다', () => {
    expect(canPay(scenario('demo-pending'))).toBe(true)
    expect(canPay(scenario('demo-failed'))).toBe(true)
    expect(canPay(scenario('demo-processing'))).toBe(false)
    expect(canPay(scenario('demo-unknown'))).toBe(false)
    expect(canPay(scenario('demo-required'))).toBe(false)
    expect(canPay(scenario('demo-expired'))).toBe(false)
    expect(canPay(scenario('demo-pending'), Date.now() + 11 * 60_000)).toBe(
      false,
    )
  })

  it('결제 승인 대기나 배송 시작 후에는 취소를 막는다', () => {
    expect(canCancel(scenario('demo-pending'))).toBe(true)
    expect(canCancel(scenario('demo-paid'))).toBe(true)
    expect(canCancel(scenario('demo-preparing'))).toBe(true)
    expect(canCancel(scenario('demo-processing'))).toBe(false)
    expect(canCancel(scenario('demo-unknown'))).toBe(false)
    expect(canCancel(scenario('demo-required'))).toBe(false)
    expect(canCancel(scenario('demo-shipped'))).toBe(false)
    expect(canCancel(scenario('demo-delivered'))).toBe(false)
    expect(canCancel(scenario('demo-canceled'))).toBe(false)
    expect(canCancel(scenario('demo-expired'))).toBe(false)
  })

  it('데모 결제 성공만 주문을 PAID로 표시하고 결과 불명은 재결제를 막는다', () => {
    const pending = scenario('demo-pending')
    const paid = withPaymentExample(
      pending,
      'SUCCEEDED',
      pending.shippingAddress,
    )
    const unknown = withPaymentExample(
      pending,
      'UNKNOWN',
      pending.shippingAddress,
    )
    expect(paid.status).toBe('PAID')
    expect(paid.paymentExpiresAt).toBeNull()
    expect(unknown.status).toBe('PAYMENT_PENDING')
    expect(unknown.reconciliationStatus).toBe('REQUIRED')
    expect(canPay(unknown)).toBe(false)
  })

  it('취소 실패·결과 불명은 주문을 취소 완료로 바꾸지 않는다', () => {
    const paid = scenario('demo-paid')
    expect(withCancelExample(paid, 'FAILED', '단순 변심').status).toBe('PAID')
    const unknown = withCancelExample(paid, 'UNKNOWN', '단순 변심')
    expect(unknown.status).toBe('PAID')
    expect(unknown.refundStatus).toBe('UNKNOWN')
    expect(canCancel(unknown)).toBe(false)
    const canceled = withCancelExample(paid, 'SUCCEEDED', '단순 변심')
    expect(canceled.status).toBe('CANCELED')
    expect(canceled.refundStatus).toBe('SUCCEEDED')
    expect(
      withCancelExample(scenario('demo-shipped'), 'SUCCEEDED', '단순 변심')
        .status,
    ).toBe('SHIPPED')
  })
})
