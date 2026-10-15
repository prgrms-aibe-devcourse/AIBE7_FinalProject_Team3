import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  approveSellerApplication,
  getAdminSellerApplications,
  rejectSellerApplication,
} from '../adminApplications'

afterEach(() => vi.restoreAllMocks())

test('신청 목록을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const page = {
    content: [
      {
        applicationId: 'app-1',
        userId: 'user-1',
        brandName: '길동상점',
        contactEmail: 'seller@example.com',
        businessRegistrationNumber: '1234567890',
        status: 'PENDING',
        appliedAt: '2026-09-18T14:00:00+09:00',
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
    getAdminSellerApplications({ status: 'PENDING', page: 0, size: 20 }),
  ).resolves.toEqual(page)
  expect(get).toHaveBeenCalledWith('/admin/seller-applications', {
    params: { status: 'PENDING', page: 0, size: 20 },
  })

  get.mockResolvedValueOnce({
    data: { success: true, data: { content: null } },
  })
  await expect(getAdminSellerApplications()).rejects.toThrow(
    '판매자 신청 목록 응답 형식이 올바르지 않습니다.',
  )
})

test('신청을 승인하고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const approval = {
    applicationId: 'app-1',
    status: 'APPROVED',
    approvedAt: '2026-09-18T15:00:00+09:00',
  }
  post.mockResolvedValueOnce({ data: { success: true, data: approval } })
  await expect(approveSellerApplication('app-1')).resolves.toEqual(approval)
  expect(post).toHaveBeenCalledWith('/admin/seller-applications/app-1/approve')

  post.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(approveSellerApplication('app-1')).rejects.toThrow(
    '판매자 신청 승인 응답 형식이 올바르지 않습니다.',
  )
})

test('신청을 반려하고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const rejection = {
    applicationId: 'app-1',
    status: 'REJECTED',
    rejectedReason: '사유',
    rejectedAt: '2026-09-18T15:00:00+09:00',
  }
  post.mockResolvedValueOnce({ data: { success: true, data: rejection } })
  await expect(rejectSellerApplication('app-1', '사유')).resolves.toEqual(
    rejection,
  )
  expect(post).toHaveBeenCalledWith('/admin/seller-applications/app-1/reject', {
    reason: '사유',
  })

  post.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(rejectSellerApplication('app-1', '사유')).rejects.toThrow(
    '판매자 신청 반려 응답 형식이 올바르지 않습니다.',
  )
})
