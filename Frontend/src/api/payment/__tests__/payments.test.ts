import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { approvePayment, cancelOrder, getOrderPayments } from '../payments'

afterEach(() => vi.restoreAllMocks())

const orderId = 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d'

test('결제 승인은 멱등 키 헤더와 콜백 값을 보내고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const result = {
    paymentId: 'e7d6c5b4-a392-4817-b6a5-4d3c2b1a0f9e',
    orderId,
    orderNumber: 'ORD-20260918-000500',
    amount: 261000,
    status: 'FAILED',
    reconciliationStatus: 'NONE',
    paidAt: null,
    failure: { code: 'REJECT_CARD_PAYMENT', message: '한도초과' },
  }
  const body = { paymentKey: 'tgen_test', amount: 261000 }
  post.mockResolvedValueOnce({ data: { success: true, data: result } })
  await expect(approvePayment(orderId, 'key-1', body)).resolves.toEqual(result)
  expect(post).toHaveBeenCalledWith(`/orders/${orderId}/payments`, body, {
    headers: { 'Idempotency-Key': 'key-1' },
  })

  post.mockResolvedValueOnce({ data: { success: true, data: {} } })
  await expect(approvePayment(orderId, 'key-1', body)).rejects.toThrow(
    '결제 승인 응답 형식이 올바르지 않습니다.',
  )
})

test('결제 이력을 시도 순서대로 반환하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const payments = [
    {
      paymentId: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
      orderId,
      provider: 'TOSS',
      amount: 261000,
      status: 'FAILED',
      reconciliationStatus: 'NONE',
      requestedAt: '2026-09-18T14:01:00+09:00',
      paidAt: null,
      canceledAt: null,
      failure: {
        code: 'REJECT_CARD_PAYMENT',
        message: '한도초과 혹은 잔액부족으로 결제에 실패했습니다.',
      },
    },
  ]
  get.mockResolvedValueOnce({ data: { success: true, data: payments } })
  await expect(getOrderPayments(orderId)).resolves.toEqual(payments)
  expect(get).toHaveBeenCalledWith(`/orders/${orderId}/payments`)

  get.mockResolvedValueOnce({ data: { success: true, data: [] } })
  await expect(getOrderPayments(orderId)).resolves.toEqual([])

  get.mockResolvedValueOnce({ data: { success: true, data: null } })
  await expect(getOrderPayments(orderId)).rejects.toThrow(
    '결제 이력 응답 형식이 올바르지 않습니다.',
  )
})

test('주문 취소는 멱등 키 헤더와 사유를 보내고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const result = {
    orderId,
    status: 'PAID',
    refundStatus: 'UNKNOWN',
    canceledAt: null,
  }
  post.mockResolvedValueOnce({ data: { success: true, data: result } })
  await expect(
    cancelOrder(orderId, 'key-1', { reason: '단순 변심' }),
  ).resolves.toEqual(result)
  expect(post).toHaveBeenCalledWith(
    `/orders/${orderId}/cancel`,
    { reason: '단순 변심' },
    { headers: { 'Idempotency-Key': 'key-1' } },
  )

  post.mockResolvedValueOnce({ data: { success: true, data: null } })
  await expect(
    cancelOrder(orderId, 'key-1', { reason: '단순 변심' }),
  ).rejects.toThrow('주문 취소 응답 형식이 올바르지 않습니다.')
})
