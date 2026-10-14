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

// 공개 목록·카드가 쓰는 화면 모델. Mock Drop은 skus로 가격·재고를 계산하고,
// API 응답은 minPrice·soldOut으로 표시한다. brandName·availableStock은 GR-96 후 연결한다.
export type CardDrop = {
  id: number
  name: string
  image: string | null
  status: DropStatus
  wishCount: number
  saleStartsAt: string
  saleEndsAt: string
  brand?: string
  minPrice?: number | null
  availableStock?: number
  soldOut?: boolean
  skus?: Sku[]
}

export type DetailImage = { imageUrl: string; altText: string }

export type DropActions = {
  wishable: boolean
  wishCancelable: boolean
  orderable: boolean
}

// 공개 상세가 쓰는 화면 모델. 재고는 옵션별 재조회 값으로 덮어쓸 수 있어 optional이다.
export type DetailSku = {
  id: number
  selections: Record<string, string>
  price: number
  stock?: number
}

export type DropDetail = {
  id: number
  name: string
  brand?: string
  description: string
  image: string
  category: string
  status: DropStatus
  wishCount: number
  wishNotice: string
  saleStartsAt: string
  saleEndsAt: string
  shippingFee: number
  shippingNotice: string
  optionGroups: OptionGroup[]
  skus: DetailSku[]
  galleryImages: DetailImage[]
  detailImages: DetailImage[]
  actions: DropActions
  serverTime: string
  minPrice: number | null
  soldOut: boolean
}
