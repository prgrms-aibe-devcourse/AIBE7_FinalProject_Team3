import { optionCombinations } from '../../drop/logic/option'
import { drops } from '../../drop/mock/mockDrops'
import { endedDrops } from '../../drop/mock/mockEndedDrops'
import type { DropStatus, OptionGroup, Sku } from '../../../types/drop'
import type { SellerDraft } from './mockSellerDrafts'

export type SellerMockDrop = {
  id: string
  ownerEmail: string
  name: string
  description: string
  image: string | null
  category: string
  status: DropStatus
  wishCount: number
  saleStartsAt: string | null
  saleEndsAt: string | null
  shippingFee: number
  shippingNotice: string
  optionGroups: OptionGroup[]
  skus: Sku[]
  soldStock?: number
}

const ownerEmail = 'grab@example.com'

export const demoSellerDrops: SellerMockDrop[] = [
  ...drops.map((drop) => ({ ...drop, id: String(drop.id), ownerEmail })),
  ...endedDrops.map((drop) => ({
    id: String(drop.dropId),
    ownerEmail,
    name: drop.name,
    description: '판매가 종료된 DROP입니다.',
    image: null,
    category: '패션',
    status: drop.status,
    wishCount: 0,
    saleStartsAt: null,
    saleEndsAt: drop.saleEndsAt,
    shippingFee: 0,
    shippingNotice: '',
    optionGroups: [],
    skus: [],
    soldStock: drop.soldStock,
  })),
].sort((a, b) => Number(b.id) - Number(a.id))

export const otherSellerDrop: SellerMockDrop = {
  ...demoSellerDrops[0],
  id: '999',
  ownerEmail: 'other@example.com',
  name: '다른 판매자의 DROP',
}

export function dropFromDraft(
  draft: SellerDraft,
  owner: string,
): SellerMockDrop {
  const optionGroups = draft.groups.filter(
    (group) => group.name.trim() && group.values.length,
  )
  const skus = optionCombinations(optionGroups).map((selections, index) => ({
    id: index + 1,
    selections,
    price: Number(draft.values[`sku:${JSON.stringify(selections)}:price`] ?? 0),
    stock: Number(draft.values[`sku:${JSON.stringify(selections)}:stock`] ?? 0),
  }))
  return {
    id: draft.id,
    ownerEmail: owner,
    name: draft.values.name ?? '',
    description: draft.values.description ?? '',
    image: draft.gallery[0]?.url ?? null,
    category: draft.values.category ?? '',
    status: 'DRAFT',
    wishCount: 0,
    saleStartsAt: draft.values.saleStartsAt || null,
    saleEndsAt: draft.values.saleEndsAt || null,
    shippingFee: Number(draft.values.shippingFee ?? 0),
    shippingNotice: draft.values.shippingNotice ?? '',
    optionGroups,
    skus,
  }
}

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
  const page = Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0
  return { status, page }
}

export function transitionSellerDrop(
  drops: SellerMockDrop[],
  id: string,
  action: 'PUBLISH' | 'CANCEL',
  reason = '',
  now = Date.now(),
): { drops: SellerMockDrop[]; error: string } {
  const drop = drops.find((item) => item.id === id)
  if (!drop) return { drops, error: 'DROP을 찾을 수 없어요.' }
  if (action === 'PUBLISH') {
    if (drop.status !== 'DRAFT')
      return { drops, error: 'DRAFT 상태에서만 공개할 수 있어요.' }
    if (
      !drop.name ||
      !drop.description ||
      !drop.image ||
      !drop.category ||
      !drop.saleStartsAt ||
      !drop.saleEndsAt ||
      !drop.shippingNotice ||
      !drop.skus.length
    )
      return {
        drops,
        error: '공개에 필요한 상품·GALLERY 이미지·일정·옵션 정보를 채워주세요.',
      }
    if (
      new Date(drop.saleStartsAt).getTime() >=
      new Date(drop.saleEndsAt).getTime()
    )
      return { drops, error: '판매 종료는 시작보다 늦어야 해요.' }
    if (
      drop.skus.some((sku) => sku.price < 0 || sku.stock < 0) ||
      !drop.skus.some((sku) => sku.active !== false && sku.stock > 0)
    )
      return {
        drops,
        error: '가격·재고가 올바르고 판매 가능한 SKU가 1개 이상 필요해요.',
      }
  } else {
    if (
      drop.status !== 'WISH' ||
      !drop.saleStartsAt ||
      new Date(drop.saleStartsAt).getTime() <= now
    )
      return { drops, error: '판매 시작 전 WISH DROP만 취소할 수 있어요.' }
    if (!reason.trim() || reason.length > 500)
      return { drops, error: '취소 사유를 1~500자로 입력해주세요.' }
  }
  return {
    drops: drops.map((item) =>
      item.id === id
        ? { ...item, status: action === 'PUBLISH' ? 'WISH' : 'CANCELED' }
        : item,
    ),
    error: '',
  }
}
