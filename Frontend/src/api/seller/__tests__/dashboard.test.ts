import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  getDashboardSummary,
  getDropStats,
  getUpcomingDrops,
} from '../dashboard'

afterEach(() => vi.restoreAllMocks())

test('대시보드 요약을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const summary = {
    dropCounts: { DRAFT: 1 },
    orderCounts: { PAID: 2 },
    paymentCounts: { SUCCEEDED: 2 },
    reconciliationRequired: 0,
    stockSummary: { available: 1, reserved: 2, sold: 3 },
  }
  get.mockResolvedValueOnce({ data: { success: true, data: summary } })
  await expect(
    getDashboardSummary('2026-10-01T00:00:00', '2026-10-15T23:59:59'),
  ).resolves.toEqual(summary)
  expect(get).toHaveBeenCalledWith('/seller/dashboard/summary', {
    params: { from: '2026-10-01T00:00:00', to: '2026-10-15T23:59:59' },
  })

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getDashboardSummary()).rejects.toThrow(
    '대시보드 요약 응답 형식이 올바르지 않습니다.',
  )
})

test('임박 DROP을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const drops = [
    {
      dropId: 1,
      name: '상품',
      status: 'WISH',
      eventType: 'START',
      upcomingAt: '2026-10-16T10:00:00',
    },
  ]
  get.mockResolvedValueOnce({ data: { success: true, data: drops } })
  await expect(getUpcomingDrops('START')).resolves.toEqual(drops)
  expect(get).toHaveBeenCalledWith('/seller/dashboard/upcoming-drops', {
    params: { eventType: 'START', withinMinutes: 1440 },
  })

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getUpcomingDrops('END')).rejects.toThrow(
    '임박 DROP 응답 형식이 올바르지 않습니다.',
  )
})

test('DROP별 통계를 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [
      {
        dropId: 100,
        name: '한정판 스니커즈',
        status: 'ENDED',
        saleEndsAt: '2026-09-18T08:00:00Z',
        activeWishCount: 152,
        availableStock: 4,
        reservedStock: 2,
        soldStock: 14,
        orderCount: 10,
        soldQuantity: 14,
        salesAmount: 1806000,
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
    getDropStats({ status: 'ENDED', dropId: 100, page: 0, size: 20 }),
  ).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/seller/dashboard/drops', {
    params: { status: 'ENDED', dropId: 100, page: 0, size: 20 },
  })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getDropStats()).rejects.toThrow(
    'DROP별 통계 응답 형식이 올바르지 않습니다.',
  )
})
