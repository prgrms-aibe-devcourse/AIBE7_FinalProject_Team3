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
