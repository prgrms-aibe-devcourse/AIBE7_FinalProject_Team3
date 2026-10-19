import { api } from '../client'
import type { paths } from '../schema/seller'

export type AdminApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

type FindApplicationsQuery = NonNullable<
  paths['/api/v1/admin/seller-applications']['get']['parameters']['query']
>
type FindApplicationsResponse =
  paths['/api/v1/admin/seller-applications']['get']['responses'][200]['content']['*/*']
type ApproveResponse =
  paths['/api/v1/admin/seller-applications/{applicationId}/approve']['post']['responses'][200]['content']['*/*']
type RejectResponse =
  paths['/api/v1/admin/seller-applications/{applicationId}/reject']['post']['responses'][200]['content']['*/*']

export async function getAdminSellerApplications(
  query: FindApplicationsQuery = {},
) {
  const { data } = await api.get<FindApplicationsResponse>(
    '/admin/seller-applications',
    { params: query },
  )
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('판매자 신청 목록 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function approveSellerApplication(applicationId: string) {
  const { data } = await api.post<ApproveResponse>(
    `/admin/seller-applications/${applicationId}/approve`,
  )
  if (data.success !== true || !data.data) {
    throw new Error('판매자 신청 승인 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function rejectSellerApplication(
  applicationId: string,
  reason: string,
) {
  const { data } = await api.post<RejectResponse>(
    `/admin/seller-applications/${applicationId}/reject`,
    { reason },
  )
  if (data.success !== true || !data.data) {
    throw new Error('판매자 신청 반려 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
