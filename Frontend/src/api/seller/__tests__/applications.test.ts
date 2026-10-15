import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import {
  getMySellerApplication,
  submitSellerApplication,
  uploadSellerDocument,
} from '../applications'

afterEach(() => vi.restoreAllMocks())

test('서류를 업로드하고 documentId를 반환하며 누락 시 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  post.mockResolvedValueOnce({
    data: {
      success: true,
      data: { documentId: 'doc-1', documentType: 'ID_CARD' },
    },
  })
  const file = new File(['x'], 'id.png', { type: 'image/png' })
  await expect(uploadSellerDocument('ID_CARD', file)).resolves.toEqual({
    documentId: 'doc-1',
    documentType: 'ID_CARD',
  })
  expect(post).toHaveBeenCalledWith('/seller-documents', expect.any(FormData), {
    params: { documentType: 'ID_CARD' },
  })

  post.mockResolvedValueOnce({ data: { success: true, data: {} } })
  await expect(uploadSellerDocument('ID_CARD', file)).rejects.toThrow(
    '서류 업로드 응답 형식이 올바르지 않습니다.',
  )
})

test('판매자 신청을 제출하고 잘못된 응답은 거부한다', async () => {
  const post = vi.spyOn(api, 'post')
  const application = {
    applicationId: 'app-1',
    brandName: '길동상점',
    status: 'PENDING',
    appliedAt: '2026-09-18T14:00:00+09:00',
  }
  post.mockResolvedValueOnce({ data: { success: true, data: application } })
  const input = {
    brandName: '길동상점',
    contactEmail: 'seller@example.com',
    businessRegistrationNumber: '1234567890',
    documentIds: {
      BUSINESS_LICENSE: 'a',
      ID_CARD: 'b',
      BANKBOOK: 'c',
      MAIL_ORDER_BUSINESS_REPORT: 'd',
    },
  } as const
  await expect(submitSellerApplication(input)).resolves.toEqual(application)
  expect(post).toHaveBeenCalledWith('/seller-applications', input)

  post.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(submitSellerApplication(input)).rejects.toThrow(
    '판매자 신청 응답 형식이 올바르지 않습니다.',
  )
})

test('내 신청을 조회하고 잘못된 응답은 거부한다', async () => {
  const get = vi.spyOn(api, 'get')
  const application = {
    applicationId: 'app-1',
    brandName: '길동상점',
    contactEmail: 'seller@example.com',
    businessRegistrationNumber: '1234567890',
    description: '설명',
    status: 'PENDING',
    appliedAt: '2026-09-18T14:00:00+09:00',
  }
  get.mockResolvedValueOnce({ data: { success: true, data: application } })
  await expect(getMySellerApplication()).resolves.toEqual(application)
  expect(get).toHaveBeenCalledWith('/seller-applications/me')

  get.mockResolvedValueOnce({ data: { success: false, data: null } })
  await expect(getMySellerApplication()).rejects.toThrow(
    '내 판매자 신청 응답 형식이 올바르지 않습니다.',
  )
})
