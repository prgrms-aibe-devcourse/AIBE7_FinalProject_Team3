import { api } from '../client'
import type { components, paths } from '../schema/drop'

export type SellerDropDraftInput = components['schemas']['DropDraftRequest']
export type SellerDropDetail = components['schemas']['SellerDropDetailResponse']

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
    !Array.isArray(data.data.options)
  ) {
    throw new Error('판매자 재고 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
