import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { createOrder, getMyOrder, getMyOrders } from '../orders'

afterEach(() => vi.restoreAllMocks())

test('주문 생성은 멱등 키 헤더와 본문을 보내고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const body = {
    dropId: 100,
    items: [{ optionId: 1001, quantity: 2 }],
    shippingAddress: {
      recipient: '홍길동',
      phone: '01012345678',
      postalCode: '06236',
      address1: '서울특별시 강남구 테헤란로 1',
    },
  }
  const created = {
    orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
    orderNumber: 'ORD-20260918-000500',
    status: 'PAYMENT_PENDING',
    totalAmount: 261000,
    paymentExpiresAt: '2026-09-18T14:10:00+09:00',
  }
  post.mockResolvedValueOnce({ data: { success: true, data: created } })
  await expect(createOrder('key-1', body)).resolves.toEqual(created)
  expect(post).toHaveBeenCalledWith('/orders', body, {
    headers: { 'Idempotency-Key': 'key-1' },
  })

  post.mockResolvedValueOnce({ data: { success: true, data: {} } })
  await expect(createOrder('key-1', body)).rejects.toThrow(
    '주문 생성 응답 형식이 올바르지 않습니다.',
  )
})

test('내 주문 목록을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [
      {
        orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
        orderNumber: 'ORD-20260918-000500',
        productName: '한정판 스니커즈',
        status: 'PAID',
        totalAmount: 261000,
        orderedAt: '2026-09-18T14:00:00+09:00',
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(getMyOrders({ status: 'PAID' })).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/orders', { params: { status: 'PAID' } })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getMyOrders()).rejects.toThrow(
    '내 주문 목록 응답 형식이 올바르지 않습니다.',
  )
})

test('내 주문 상세를 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const detail = {
    orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
    orderNumber: 'ORD-20260918-000500',
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
    itemsAmount: 258000,
    shippingAmount: 3000,
    totalAmount: 261000,
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
    shippingAddress: {
      recipient: '홍길동',
      phone: '01012345678',
      postalCode: '06236',
      address1: '서울특별시 강남구 테헤란로 1',
      address2: null,
      deliveryMemo: null,
    },
    shipping: {
      status: null,
      carrier: null,
      trackingNumber: null,
      deliveredAt: null,
      deliveryStage: null,
    },
    orderedAt: '2026-09-18T14:00:00+09:00',
  }
  get.mockResolvedValueOnce({ data: { success: true, data: detail } })
  await expect(getMyOrder(detail.orderId)).resolves.toEqual(detail)
  expect(get).toHaveBeenCalledWith(`/orders/${detail.orderId}`)

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getMyOrder(detail.orderId)).rejects.toThrow(
    '내 주문 상세 응답 형식이 올바르지 않습니다.',
  )
})
