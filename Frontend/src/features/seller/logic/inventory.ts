import type { Sku } from '../../../types/drop'
import {
  sellerDropStatuses,
  type SellerMockDrop,
} from '../mock/mockSellerDrops'

export const inventoryPageSize = 4

export function readInventoryQuery(params: URLSearchParams) {
  const status =
    sellerDropStatuses.find((item) => item === params.get('status')) ?? 'ALL'
  const rawPage = Number(params.get('page') ?? 0)
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  return { status, page, dropId: params.get('dropId') }
}

export function stockBreakdown(sku: Sku) {
  const reserved = sku.reserved ?? 0
  const sold = sku.sold ?? 0
  return {
    available: sku.stock,
    reserved,
    sold,
    total: sku.stock + reserved + sold,
  }
}

export function optionLabel(drop: SellerMockDrop, sku: Sku) {
  return (
    drop.optionGroups
      .map(
        (group) =>
          group.values.find((value) => value.id === sku.selections[group.id])
            ?.label,
      )
      .filter(Boolean)
      .join(' / ') || '기본 옵션'
  )
}

export function inventoryTotals(drop: SellerMockDrop) {
  if (drop.soldStock !== undefined && drop.skus.length === 0)
    return { available: 0, reserved: 0, sold: drop.soldStock }
  return drop.skus.reduce(
    (total, sku) => {
      const stock = stockBreakdown(sku)
      return {
        available: total.available + stock.available,
        reserved: total.reserved + stock.reserved,
        sold: total.sold + stock.sold,
      }
    },
    { available: 0, reserved: 0, sold: 0 },
  )
}
