import { api } from '../client'
import type { paths } from '../schema/seller'

export type SellerDocumentType =
  'BUSINESS_LICENSE' | 'ID_CARD' | 'BANKBOOK' | 'MAIL_ORDER_BUSINESS_REPORT'

type UploadResponse =
  paths['/api/v1/seller-documents']['post']['responses'][201]['content']['*/*']
type ApplyResponse =
  paths['/api/v1/seller-applications']['post']['responses'][201]['content']['*/*']
type MyApplicationResponse =
  paths['/api/v1/seller-applications/me']['get']['responses'][200]['content']['*/*']

export type SellerApplicationInput = {
  brandName: string
  contactEmail: string
  businessRegistrationNumber: string
  description?: string
  documentIds: Record<SellerDocumentType, string>
}

export async function uploadSellerDocument(
  documentType: SellerDocumentType,
  file: File,
) {
  const form = new FormData()
  form.append('file', file)
  // documentType은 쿼리 파라미터다(schema 기준, SELLER.md 1.1절의 "폼 필드" 서술과 다르다)
  const { data } = await api.post<UploadResponse>('/seller-documents', form, {
    params: { documentType },
  })
  if (data.success !== true || !data.data?.documentId) {
    throw new Error('서류 업로드 응답 형식이 올바르지 않습니다.')
  }
  return {
    documentId: data.data.documentId,
    documentType: data.data.documentType,
  }
}

export async function submitSellerApplication(input: SellerApplicationInput) {
  const { data } = await api.post<ApplyResponse>('/seller-applications', input)
  if (data.success !== true || !data.data) {
    throw new Error('판매자 신청 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// 신청 없음(404)은 호출부에서 null로 다룬다. 여기서는 성공 응답 형식만 검증한다.
export async function getMySellerApplication() {
  const { data } = await api.get<MyApplicationResponse>(
    '/seller-applications/me',
  )
  if (data.success !== true || !data.data) {
    throw new Error('내 판매자 신청 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
