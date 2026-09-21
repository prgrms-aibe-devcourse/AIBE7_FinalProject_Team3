export type OptionGroup = {
  id: string
  name: string
  values: { id: string; label: string }[]
}

export type Sku = {
  id: number
  selections: Record<string, string>
  price: number
  stock: number
}

export type DropStatus = 'WISH' | 'GRAB' | 'ENDED'

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
