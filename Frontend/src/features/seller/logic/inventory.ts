import { readSellerDropQuery } from './sellerDropQuery'

export const inventoryPageSize = 4

export function readInventoryQuery(params: URLSearchParams) {
  return { ...readSellerDropQuery(params), dropId: params.get('dropId') }
}
