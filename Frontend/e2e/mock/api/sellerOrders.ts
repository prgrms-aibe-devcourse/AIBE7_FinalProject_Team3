import type { Route } from '@playwright/test'
import { FIXED_NOW } from './drop/fixtures'
import { json } from './shared'

type SellerOrderRow = {
  orderId: string
  orderNumber: string
  dropId: number
  productName: string
  sellerName: string
  orderStatus: string
  paymentStatus: string
  itemsAmount: number
  shippingAmount: number
  totalAmount: number
  orderedAt: string
}

// 라우트 핸들러는 Node 프로세스에서 실행돼 page.clock의 영향을 받지 않는다.
// 실제 시각을 쓰면 스냅샷 생성 시점과 검증 시점의 "오후 HH:MM" 분이 달라져
// 시각 비교가 실행할 때마다 미세하게 깨진다. FIXED_NOW를 기준으로 고정한다.
const hoursAgo = (hours: number) =>
  new Date(FIXED_NOW.getTime() - hours * 3_600_000).toISOString()

export const SELLER_ORDERS: SellerOrderRow[] = [
  {
    orderId: 'b8daec07-7c9e-40b1-c2d3-5e6f70819203',
    orderNumber: 'ORD-20260914-000390',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    sellerName: 'GR105테스트샵',
    orderStatus: 'DELIVERED',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 129000,
    shippingAmount: 3000,
    totalAmount: 132000,
    orderedAt: hoursAgo(98),
  },
  {
    orderId: 'a7c9ebfd-6b8d-4fa0-b1c2-4d5e6f708192',
    orderNumber: 'ORD-20260916-000433',
    dropId: 203,
    productName: '에브리데이 캔버스 백',
    sellerName: 'GR105테스트샵',
    orderStatus: 'SHIPPED',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 88000,
    shippingAmount: 3000,
    totalAmount: 91000,
    orderedAt: hoursAgo(50),
  },
  {
    orderId: 'f6b8daec-5a7c-4e9f-a0b1-3c4d5e6f7081',
    orderNumber: 'ORD-20260917-000471',
    dropId: 202,
    productName: '슬로우 모닝 머그',
    sellerName: 'GR105테스트샵',
    orderStatus: 'PREPARING',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 60000,
    shippingAmount: 3000,
    totalAmount: 63000,
    orderedAt: hoursAgo(31),
  },
  {
    orderId: 'e5a7c9db-4f6b-4d8e-9fa0-2b3c4d5e6f70',
    orderNumber: 'ORD-20260917-000488',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    sellerName: 'GR105테스트샵',
    orderStatus: 'PREPARING',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 129000,
    shippingAmount: 3000,
    totalAmount: 132000,
    orderedAt: hoursAgo(26),
  },
  {
    orderId: 'd4f6b8ca-3e5a-4c7d-8e9f-1a2b3c4d5e6f',
    orderNumber: 'ORD-20260918-000502',
    dropId: 203,
    productName: '에브리데이 캔버스 백',
    sellerName: 'GR105테스트샵',
    orderStatus: 'PAID',
    paymentStatus: 'UNKNOWN',
    itemsAmount: 85000,
    shippingAmount: 3000,
    totalAmount: 88000,
    orderedAt: hoursAgo(9),
  },
  {
    orderId: 'c3e5a7b9-2d4f-4b6c-9d8e-0f1a2b3c4d5e',
    orderNumber: 'ORD-20260918-000501',
    dropId: 202,
    productName: '슬로우 모닝 머그',
    sellerName: 'GR105테스트샵',
    orderStatus: 'PAID',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 39000,
    shippingAmount: 3000,
    totalAmount: 42000,
    orderedAt: hoursAgo(5),
  },
  {
    orderId: 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d',
    orderNumber: 'ORD-20260918-000500',
    dropId: 201,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    sellerName: 'GR105테스트샵',
    orderStatus: 'PAID',
    paymentStatus: 'SUCCEEDED',
    itemsAmount: 258000,
    shippingAmount: 3000,
    totalAmount: 261000,
    orderedAt: hoursAgo(2),
  },
]

const SHIPPING: Record<
  string,
  { carrier: string; trackingNumber: string; deliveredAt: string | null }
> = {
  'a7c9ebfd-6b8d-4fa0-b1c2-4d5e6f708192': {
    carrier: 'CJ대한통운',
    trackingNumber: '482910355174',
    deliveredAt: null,
  },
  'b8daec07-7c9e-40b1-c2d3-5e6f70819203': {
    carrier: '한진택배',
    trackingNumber: '771204839055',
    deliveredAt: hoursAgo(20),
  },
}

const ITEMS: Record<
  string,
  {
    productName: string
    optionName: string
    unitPrice: number
    quantity: number
    subtotal: number
  }[]
> = {
  'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d': [
    {
      productName: '뮤직 클럽 티셔츠 · 리미티드',
      optionName: '루즈 / 긴소매',
      unitPrice: 129000,
      quantity: 2,
      subtotal: 258000,
    },
  ],
}

export async function handleSellerOrdersRoute(
  route: Route,
  url: URL,
): Promise<boolean> {
  if (url.pathname === '/api/v1/seller/orders') {
    const dropId = url.searchParams.get('dropId')
    const orderStatus = url.searchParams.get('orderStatus')
    const paymentStatus = url.searchParams.get('paymentStatus')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    // 최신순(created_at DESC)이 실제 API와 동일한 정렬 기준이다.
    const filtered = SELLER_ORDERS.filter(
      (order) =>
        (!dropId || order.dropId === Number(dropId)) &&
        (!orderStatus || order.orderStatus === orderStatus) &&
        (!paymentStatus || order.paymentStatus === paymentStatus),
    ).sort(
      (a, b) =>
        new Date(b.orderedAt).getTime() - new Date(a.orderedAt).getTime(),
    )
    const content = filtered.slice(page * size, page * size + size)
    await json(route, {
      success: true,
      data: {
        content,
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
        hasNext: (page + 1) * size < filtered.length,
      },
    })
    return true
  }
  const detailMatch = url.pathname.match(
    /^\/api\/v1\/seller\/orders\/([0-9a-f-]+)$/,
  )
  if (detailMatch) {
    const orderId = detailMatch[1]
    const order = SELLER_ORDERS.find((item) => item.orderId === orderId)
    if (!order) {
      await json(route, { success: false, message: 'ORDER_NOT_FOUND' }, 404)
      return true
    }
    const shipping = SHIPPING[orderId]
    await json(route, {
      success: true,
      data: {
        orderId: order.orderId,
        orderNumber: order.orderNumber,
        status: order.orderStatus,
        items: ITEMS[orderId] ?? [],
        itemsAmount: order.itemsAmount,
        shippingAmount: order.shippingAmount,
        totalAmount: order.totalAmount,
        paymentStatus: order.paymentStatus,
        shipping: shipping
          ? { status: order.orderStatus, ...shipping }
          : {
              status: null,
              carrier: null,
              trackingNumber: null,
              deliveredAt: null,
            },
        orderedAt: order.orderedAt,
      },
    })
    return true
  }
  return false
}
