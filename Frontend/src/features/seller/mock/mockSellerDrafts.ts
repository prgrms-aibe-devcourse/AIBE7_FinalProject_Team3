import { drops } from '../../drop/mock/mockDrops'
import type { OptionGroup } from '../../../types/drop'

const inputDateTime = (value: string) => {
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

export type DraftImage = {
  id: string
  url: string
  altText: string
  failed?: boolean
}

export type DraftGroup = OptionGroup & { rawValues: string }

export type SellerDraft = {
  id: string
  values: Record<string, string>
  groups: DraftGroup[]
  gallery: DraftImage[]
  detail: DraftImage[]
}

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
      saleStartsAt: inputDateTime(drop.saleStartsAt),
      saleEndsAt: inputDateTime(drop.saleEndsAt),
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

export function imageError(file: File, count: number): string {
  if (count >= 10) return '이미지는 두 용도 합계 10장까지 추가할 수 있어요.'
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type))
    return 'JPEG, PNG, WebP 이미지만 추가할 수 있어요.'
  if (file.size > 5 * 1024 * 1024) return '파일당 5MB 이하만 추가할 수 있어요.'
  return ''
}

export function moveImage(
  images: DraftImage[],
  index: number,
  direction: -1 | 1,
) {
  const nextIndex = index + direction
  if (nextIndex < 0 || nextIndex >= images.length) return images
  const next = [...images]
  ;[next[index], next[nextIndex]] = [next[nextIndex], next[index]]
  return next
}
