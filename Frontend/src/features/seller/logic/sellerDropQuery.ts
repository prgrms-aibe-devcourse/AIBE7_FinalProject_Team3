import type { DropStatus } from '../../../types/drop'

export const sellerDropStatuses: Array<'ALL' | DropStatus> = [
  'ALL',
  'DRAFT',
  'WISH',
  'GRAB',
  'ENDED',
  'CANCELED',
]

export function readSellerDropQuery(params: URLSearchParams) {
  const status =
    sellerDropStatuses.find((item) => item === params.get('status')) ?? 'ALL'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  return { status, page }
}
