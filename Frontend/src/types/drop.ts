export type OptionGroup = {
  id: string
  name: string
  values: { id: string; label: string }[]
}

export type Sku = {
  id: number
  selections: Record<string, string>
  price: number
  // 가용 재고. 서버는 total - reserved - sold - withheld 로 계산해 내려준다(ERD 1.2).
  stock: number
  // 판매자 상세에서만 쓴다. 소비자 화면은 가용 재고만 본다.
  reserved?: number
  sold?: number
  active?: boolean
}

export type DropStatus = 'DRAFT' | 'WISH' | 'GRAB' | 'ENDED' | 'CANCELED'

export type Drop = {
  id: number
  name: string
  brand: string
  description: string
  image: string
  category: string
  status: DropStatus
  wishCount: number
  saleStartsAt: string
  saleEndsAt: string
  shippingFee: number
  shippingNotice: string
  optionGroups: OptionGroup[]
  skus: Sku[]
}
