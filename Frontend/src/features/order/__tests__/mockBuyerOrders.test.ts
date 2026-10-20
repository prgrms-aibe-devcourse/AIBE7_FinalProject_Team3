import { describe, expect, it } from 'vitest'
import {
  canPay,
  demoBuyerOrders,
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
})
