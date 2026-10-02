export type SellerOrderStatus = 'PAID' | 'PREPARING' | 'SHIPPED' | 'DELIVERED'

// GET /api/v1/seller/orders (GR-37) 의 한 행. 송장 정보는 여기 없다 — 상세에서 받는다.
export type SellerOrder = {
  orderId: string
  orderNumber: string
  dropId: number
  productName: string
  orderStatus: SellerOrderStatus
  paymentStatus: 'SUCCEEDED' | 'UNKNOWN'
  totalAmount: number
  orderedAt: string
}

// GET /api/v1/seller/orders/{orderId} (GR-39). 행을 펼칠 때 받아온다.
// status·paymentStatus·orderedAt도 함께 오지만 목록에 이미 있어 목에서는 생략했다.
export type SellerOrderDetail = {
  items: {
    productName: string
    optionName: string
    unitPrice: number
    quantity: number
    subtotal: number
  }[]
  itemsAmount: number
  shippingAmount: number
  totalAmount: number
  shipping: {
    carrier: string
    trackingNumber: string
    deliveredAt: string | null
  } | null
}

const hoursAgo = (hours: number) =>
  new Date(Date.now() - hours * 3_600_000).toISOString()

export const sellerOrders: SellerOrder[] = [
  {
    orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
    orderNumber: 'ORD-20260918-000500',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    orderStatus: 'PAID',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 261000,
    orderedAt: hoursAgo(2),
  },
  {
    orderId: 'c3e5a7b9-2d4f-4b6c-9d8e-0f1a2b3c4d5e',
    orderNumber: 'ORD-20260918-000501',
    dropId: 202,
    productName: '슬로우 모닝 머그',
    orderStatus: 'PAID',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 42000,
    orderedAt: hoursAgo(5),
  },
  {
    orderId: 'd4f6b8ca-3e5a-4c7d-8e9f-1a2b3c4d5e6f',
    orderNumber: 'ORD-20260918-000502',
    dropId: 203,
    productName: '에브리데이 캔버스 백',
    orderStatus: 'PAID',
    paymentStatus: 'UNKNOWN',
    totalAmount: 88000,
    orderedAt: hoursAgo(9),
  },
  {
    orderId: 'e5a7c9db-4f6b-4d8e-9fa0-2b3c4d5e6f70',
    orderNumber: 'ORD-20260917-000488',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    orderStatus: 'PREPARING',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 132000,
    orderedAt: hoursAgo(26),
  },
  {
    orderId: 'f6b8daec-5a7c-4e9f-a0b1-3c4d5e6f7081',
    orderNumber: 'ORD-20260917-000471',
    dropId: 202,
    productName: '슬로우 모닝 머그',
    orderStatus: 'PREPARING',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 63000,
    orderedAt: hoursAgo(31),
  },
  {
    orderId: 'a7c9ebfd-6b8d-4fa0-b1c2-4d5e6f708192',
    orderNumber: 'ORD-20260916-000433',
    dropId: 203,
    productName: '에브리데이 캔버스 백',
    orderStatus: 'SHIPPED',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 91000,
    orderedAt: hoursAgo(50),
  },
  {
    orderId: 'b8daec07-7c9e-40b1-c2d3-5e6f70819203',
    orderNumber: 'ORD-20260914-000390',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    orderStatus: 'DELIVERED',
    paymentStatus: 'SUCCEEDED',
    totalAmount: 132000,
    orderedAt: hoursAgo(98),
  },
]

export const orderDetails: Record<string, SellerOrderDetail> = {
  'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d': {
    items: [
      {
        productName: '뮤직 클럽 티셔츠 · 리미티드',
        optionName: '루즈 / 긴소매',
        unitPrice: 129000,
        quantity: 2,
        subtotal: 258000,
      },
    ],
    itemsAmount: 258000,
    shippingAmount: 3000,
    totalAmount: 261000,
    shipping: null,
  },
  'c3e5a7b9-2d4f-4b6c-9d8e-0f1a2b3c4d5e': {
    items: [
      {
        productName: '슬로우 모닝 머그',
        optionName: '무광 / 기본 포장',
        unitPrice: 19500,
        quantity: 2,
        subtotal: 39000,
      },
    ],
    itemsAmount: 39000,
    shippingAmount: 3000,
    totalAmount: 42000,
    shipping: null,
  },
  'd4f6b8ca-3e5a-4c7d-8e9f-1a2b3c4d5e6f': {
    items: [
      {
        productName: '에브리데이 캔버스 백',
        optionName: '워시드 코튼 / 롱',
        unitPrice: 85000,
        quantity: 1,
        subtotal: 85000,
      },
    ],
    itemsAmount: 85000,
    shippingAmount: 3000,
    totalAmount: 88000,
    shipping: null,
  },
  'e5a7c9db-4f6b-4d8e-9fa0-2b3c4d5e6f70': {
    items: [
      {
        productName: '뮤직 클럽 티셔츠 · 리미티드',
        optionName: '레귤러 / 반소매',
        unitPrice: 129000,
        quantity: 1,
        subtotal: 129000,
      },
    ],
    itemsAmount: 129000,
    shippingAmount: 3000,
    totalAmount: 132000,
    shipping: null,
  },
  'f6b8daec-5a7c-4e9f-a0b1-3c4d5e6f7081': {
    items: [
      {
        productName: '슬로우 모닝 머그',
        optionName: '유광 / 선물 포장',
        unitPrice: 20000,
        quantity: 3,
        subtotal: 60000,
      },
    ],
    itemsAmount: 60000,
    shippingAmount: 3000,
    totalAmount: 63000,
    shipping: null,
  },
  'a7c9ebfd-6b8d-4fa0-b1c2-4d5e6f708192': {
    items: [
      {
        productName: '에브리데이 캔버스 백',
        optionName: '캔버스 / 스탠다드',
        unitPrice: 88000,
        quantity: 1,
        subtotal: 88000,
      },
    ],
    itemsAmount: 88000,
    shippingAmount: 3000,
    totalAmount: 91000,
    shipping: {
      carrier: 'CJ대한통운',
      trackingNumber: '482910355174',
      deliveredAt: null,
    },
  },
  'b8daec07-7c9e-40b1-c2d3-5e6f70819203': {
    items: [
      {
        productName: '뮤직 클럽 티셔츠 · 리미티드',
        optionName: '루즈 / 반소매',
        unitPrice: 129000,
        quantity: 1,
        subtotal: 129000,
      },
    ],
    itemsAmount: 129000,
    shippingAmount: 3000,
    totalAmount: 132000,
    shipping: {
      carrier: '한진택배',
      trackingNumber: '771204839055',
      deliveredAt: hoursAgo(20),
    },
  },
}
