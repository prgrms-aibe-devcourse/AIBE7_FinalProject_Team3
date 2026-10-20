import type { components } from '../../../api/schema/drop'
import type {
  DetailImage,
  DetailSku,
  DropActions,
  DropDetail,
  OptionGroup,
} from '../../../types/drop'

type PublicDropDetail = components['schemas']['PublicDropDetailResponse']
type PublicOption = components['schemas']['PublicDropDetailOption']

const toImages = (
  images: components['schemas']['Image'][] | undefined,
  fallbackAlt: string,
): DetailImage[] =>
  (images ?? []).map((image) => ({
    imageUrl: image.imageUrl ?? '',
    altText: image.altText ?? fallbackAlt,
  }))

const toOptionGroups = (
  groups: components['schemas']['DropOptionGroupResponse'][] | undefined,
): OptionGroup[] =>
  (groups ?? []).map((group) => ({
    id: String(group.groupId ?? ''),
    name: group.name ?? '',
    values: (group.values ?? []).map((value) => ({
      id: String(value.valueId ?? ''),
      label: value.value ?? '',
    })),
  }))

const toSkus = (options: PublicOption[] | undefined): DetailSku[] =>
  (options ?? []).map((option) => ({
    id: option.optionId ?? 0,
    selections: Object.fromEntries(
      (option.selections ?? []).map((selection) => [
        String(selection.groupId ?? ''),
        String(selection.valueId ?? ''),
      ]),
    ),
    price: option.unitPrice ?? 0,
    stock: option.availableStock,
  }))

const toActions = (
  actions: components['schemas']['Actions'] | undefined,
): DropActions => ({
  wishable: actions?.wishable ?? false,
  wishCancelable: actions?.wishCancelable ?? false,
  orderable: actions?.orderable ?? false,
})

export function toDropDetail(drop: PublicDropDetail): DropDetail {
  const name = drop.name ?? ''
  const gallery = toImages(drop.galleryImages, `${name} 이미지`)
  const imageUrls = (drop.imageUrls ?? []).map((imageUrl, index) => ({
    imageUrl,
    altText: `${name} 이미지 ${index + 1}`,
  }))
  return {
    id: drop.dropId ?? 0,
    name,
    brand: drop.brandName,
    description: drop.description ?? '',
    image: gallery[0]?.imageUrl ?? imageUrls[0]?.imageUrl ?? '',
    category: drop.category?.name ?? '',
    status: drop.status ?? 'GRAB',
    wishCount: drop.wishCount ?? 0,
    wishNotice: drop.wishNotice ?? '',
    saleStartsAt: drop.saleStartsAt ?? '',
    saleEndsAt: drop.saleEndsAt ?? '',
    shippingFee: drop.shipping?.shippingFee ?? 0,
    shippingNotice: drop.shipping?.shippingNotice ?? '',
    optionGroups: toOptionGroups(drop.optionGroups),
    skus: toSkus(drop.options),
    galleryImages: gallery.length ? gallery : imageUrls,
    detailImages: toImages(drop.detailImages, `${name} 상세 이미지`),
    actions: toActions(drop.actions),
    serverTime: drop.serverTime ?? '',
    minPrice: drop.minPrice ?? null,
    soldOut: drop.soldOut ?? false,
  }
}
