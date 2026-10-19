import { sellerDropStatuses } from '../mock/mockSellerDrops'

export const salesPageSize = 4

export function readSalesQuery(params: URLSearchParams) {
  const status =
    sellerDropStatuses.find((item) => item === params.get('status')) ?? 'ALL'
  const dropId = params.get('dropId')
  // dropId로 단건 필터링 중에는 이전 상태/페이지에서 넘어온 page 값이 무의미하다
  if (dropId) return { status, page: 0, dropId }
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  return { status, page, dropId }
}
