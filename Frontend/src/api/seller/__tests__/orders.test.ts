import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { getSellerOrder, getSellerOrders } from '../orders'

afterEach(() => vi.restoreAllMocks())

test('판매자 주문 목록을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [
      {
        orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
        orderNumber: 'ORD-20260918-000500',
        dropId: 201,
        productName: '뮤직 클럽 티셔츠',
        sellerName: '길동상점',
        orderStatus: 'PAID',
        paymentStatus: 'SUCCEEDED',
        itemsAmount: 258000,
        shippingAmount: 3000,
        totalAmount: 261000,
        orderedAt: '2026-09-18T14:00:00Z',
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    hasNext: false,
  }
  get.mockResolvedValueOnce({ data: { success: true, data: page } })
  await expect(
    getSellerOrders({ orderStatus: 'PAID', dropId: 201 }),
  ).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/seller/orders', {
    params: { orderStatus: 'PAID', dropId: 201 },
  })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getSellerOrders()).rejects.toThrow(
    '판매자 주문 목록 응답 형식이 올바르지 않습니다.',
  )
})

test('판매자 주문 상세를 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const detail = {
    orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
    orderNumber: 'ORD-20260918-000500',
    status: 'PAID',
    items: [
      {
        productName: '뮤직 클럽 티셔츠',
        optionName: '루즈 / 긴소매',
        unitPrice: 129000,
        quantity: 2,
        subtotal: 258000,
      },
    ],
    itemsAmount: 258000,
    shippingAmount: 3000,
    totalAmount: 261000,
    paymentStatus: 'SUCCEEDED',
    shipping: {
      status: null,
      carrier: null,
      trackingNumber: null,
      deliveredAt: null,
    },
    orderedAt: '2026-09-18T14:00:00Z',
  }
  get.mockResolvedValueOnce({ data: { success: true, data: detail } })
  await expect(
    getSellerOrder('b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d'),
  ).resolves.toEqual(detail)
  expect(get).toHaveBeenCalledWith(
    '/seller/orders/b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
  )

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(
    getSellerOrder('b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d'),
  ).rejects.toThrow('판매자 주문 상세 응답 형식이 올바르지 않습니다.')
})
