import { api } from '../client'
import type { components, paths } from '../schema/drop'

export type SellerDropDraftInput = components['schemas']['DropDraftRequest']
export type SellerDropDetail = components['schemas']['SellerDropDetailResponse']
export type SellerDropListParams = {
  status?: NonNullable<SellerDropDetail['status']>
  page: number
  size: number
}

export async function getSellerDrops(
  params: SellerDropListParams,
  signal?: AbortSignal,
) {
  const { data } = await api.get<
    components['schemas']['ApiResponsePageResponseSellerDropListResponse']
  >('/seller/drops', { params, signal })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content) ||
    data.data.content.some(
      (drop) =>
        !Number.isSafeInteger(drop.dropId) ||
        !drop.dropId ||
        drop.dropId < 1 ||
        !['DRAFT', 'WISH', 'GRAB', 'ENDED', 'CANCELED'].includes(
          drop.status ?? '',
        ),
    ) ||
    !Number.isInteger(data.data.totalPages) ||
    !Number.isInteger(data.data.totalElements) ||
    (data.data.totalPages ?? -1) < 0 ||
    (data.data.totalElements ?? -1) < 0 ||
    typeof data.data.hasNext !== 'boolean'
  )
    throw new Error('판매자 DROP 목록 응답 형식이 올바르지 않습니다.')
  return data.data
}

export async function cancelSellerDrop(dropId: number, reason: string) {
  const { data } = await api.post<
    components['schemas']['ApiResponseDropCancelResponse']
  >(`/seller/drops/${dropId}/cancel`, { reason })
  if (
    data.success !== true ||
    data.data?.dropId !== dropId ||
    data.data.status !== 'CANCELED'
  )
    throw new Error('DROP 취소 응답 형식이 올바르지 않습니다.')
  return data.data
}

export async function getSellerDropWishCount(dropId: number) {
  const { data } = await api.get<
    components['schemas']['ApiResponseSellerDropWishCountResponse']
  >(`/seller/drops/${dropId}/wish-count`)
  if (
    data.success !== true ||
    data.data?.dropId !== dropId ||
    !Number.isSafeInteger(data.data.activeWishCount) ||
    (data.data.activeWishCount ?? -1) < 0
  )
    throw new Error('DROP WISH 수 응답 형식이 올바르지 않습니다.')
  return data.data.activeWishCount!
}

type DetailResponse =
  paths['/api/v1/seller/drops/{dropId}']['get']['responses'][200]['content']['*/*']
type DraftResponse =
  paths['/api/v1/seller/drops']['post']['responses'][201]['content']['*/*']
type PublishResponse =
  paths['/api/v1/seller/drops/{dropId}/publish']['post']['responses'][200]['content']['*/*']

export async function getSellerDropDetail(
  dropId: number,
  signal?: AbortSignal,
) {
  const { data } = await api.get<DetailResponse>(`/seller/drops/${dropId}`, {
    signal,
  })
  if (
    data.success !== true ||
    data.data?.dropId !== dropId ||
    !['DRAFT', 'WISH', 'GRAB', 'ENDED', 'CANCELED'].includes(
      data.data.status ?? '',
    ) ||
    !Array.isArray(data.data.images) ||
    !Array.isArray(data.data.detailImages) ||
    !Array.isArray(data.data.optionGroups) ||
    !Array.isArray(data.data.options)
  ) {
    throw new Error('판매자 DROP 상세 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function createSellerDrop(input: SellerDropDraftInput) {
  const { data } = await api.post<DraftResponse>('/seller/drops', input)
  if (
    data.success !== true ||
    !Number.isSafeInteger(data.data?.dropId) ||
    !data.data?.dropId ||
    data.data.dropId < 1 ||
    data.data.status !== 'DRAFT'
  ) {
    throw new Error('DROP 임시 저장 응답 형식이 올바르지 않습니다.')
  }
  return { ...data.data, dropId: data.data.dropId }
}

export async function updateSellerDrop(
  dropId: number,
  input: SellerDropDraftInput,
) {
  const { data } = await api.patch<DraftResponse>(
    `/seller/drops/${dropId}`,
    input,
  )
  if (
    data.success !== true ||
    data.data?.dropId !== dropId ||
    data.data.status !== 'DRAFT'
  ) {
    throw new Error('DROP 수정 응답 형식이 올바르지 않습니다.')
  }
  return { ...data.data, dropId }
}

export async function publishSellerDrop(dropId: number) {
  const { data } = await api.post<PublishResponse>(
    `/seller/drops/${dropId}/publish`,
  )
  if (
    data.success !== true ||
    data.data?.dropId !== dropId ||
    data.data.status !== 'WISH'
  ) {
    throw new Error('DROP 공개 응답 형식이 올바르지 않습니다.')
  }
  return { ...data.data, dropId }
}

type SellerDropStocksResponse =
  paths['/api/v1/seller/drops/{dropId}/stocks']['get']['responses'][200]['content']['*/*']

export async function getSellerDropStocks(dropId: number) {
  const { data } = await api.get<SellerDropStocksResponse>(
    `/seller/drops/${dropId}/stocks`,
  )
  if (
    data.success !== true ||
    !data.data ||
    data.data.dropId !== dropId ||
    !Array.isArray(data.data.options)
  ) {
    throw new Error('판매자 재고 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
