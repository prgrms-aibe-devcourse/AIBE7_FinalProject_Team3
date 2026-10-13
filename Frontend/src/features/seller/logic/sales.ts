import { sellerDropStatuses } from '../mock/mockSellerDrops'

export const salesPageSize = 4

export function readSalesQuery(params: URLSearchParams) {
  const status =
    sellerDropStatuses.find((item) => item === params.get('status')) ?? 'ALL'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  return { status, page, dropId: params.get('dropId') }
}
