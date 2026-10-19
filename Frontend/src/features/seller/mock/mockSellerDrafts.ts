import { drops } from '../../drop/mock/mockDrops'
import { inputDateTime } from '../../../utils/date'
import type { DraftGroup, SellerDraft } from '../logic/sellerDropForm'

export const defaultGroups: DraftGroup[] = [
  {
    id: 'group-1',
    name: '소재',
    rawValues: '코튼, 린넨',
    values: [
      { id: 'value-1', label: '코튼' },
      { id: 'value-2', label: '린넨' },
    ],
  },
]

export function existingSellerDraft(id: string): SellerDraft | undefined {
  const drop = drops.find(
    (item) => String(item.id) === id && item.status === 'DRAFT',
  )
  if (!drop) return undefined
  return {
    id,
    values: {
      name: drop.name,
      description: drop.description,
      category: drop.category,
      saleStartsAt: inputDateTime(drop.saleStartsAt).slice(0, 16),
      saleEndsAt: inputDateTime(drop.saleEndsAt).slice(0, 16),
      shippingFee: String(drop.shippingFee),
      shippingNotice: drop.shippingNotice,
      ...Object.fromEntries(
        drop.skus.flatMap((sku) => [
          [`sku:${JSON.stringify(sku.selections)}:price`, String(sku.price)],
          [`sku:${JSON.stringify(sku.selections)}:stock`, String(sku.stock)],
        ]),
      ),
    },
    groups: drop.optionGroups.length
      ? drop.optionGroups.map((group) => ({
          ...group,
          rawValues: group.values.map((value) => value.label).join(', '),
        }))
      : defaultGroups,
    gallery: [
      {
        id: `${id}-gallery`,
        url: drop.image,
        altText: `${drop.name} 대표 이미지`,
      },
    ],
    detail: [],
  }
}
