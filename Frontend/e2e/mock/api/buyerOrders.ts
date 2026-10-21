import type { Route } from '@playwright/test'
import { FIXED_NOW, type FixtureDrop } from './drop/fixtures'
import { json } from './shared'

type OrderStatus =
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELED'
  | 'EXPIRED'

type Payment = {
  paymentId: string
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'CANCELED'
  reconciliationStatus: 'NONE' | 'REQUIRED' | 'RESOLVED'
  failure: { code: string; message: string } | null
}

// 취소 요청에 대한 서버 응답 시나리오. UNKNOWN_THEN_SUCCEEDED는 같은 키 재요청에서 확정된다.
type CancelBehavior = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN_THEN_SUCCEEDED'

type BuyerOrder = {
  orderId: string
  orderNumber: string
  productName: string
  optionName: string
  unitPrice: number
  quantity: number
  shippingAmount: number
  status: OrderStatus
  paymentStatus: Payment['status'] | null
  paymentExpiresAt: string | null
  shipping: {
    carrier: string
    trackingNumber: string
    deliveredAt: string | null
  } | null
  payments: Payment[]
  cancelBehavior: CancelBehavior
  cancelKey: string | null
  createKey: string | null
  // 결과 불명(UNKNOWN) 승인을 같은 키로 다시 확인할 때 쓴다.
  approveKey: string | null
}

export type BuyerOrdersFixture = BuyerOrder[]

// 라우트 핸들러는 page.clock의 영향을 받지 않으므로 FIXED_NOW를 기준으로 시각을 만든다.
const minutesFrom = (minutes: number) =>
  new Date(FIXED_NOW.getTime() + minutes * 60_000).toISOString()

export const BUYER_ORDER_IDS = {
  pending: 'c1a00000-0000-4000-8000-000000000001',
  unknown: 'c1a00000-0000-4000-8000-000000000002',
  paid: 'c1a00000-0000-4000-8000-000000000003',
  preparing: 'c1a00000-0000-4000-8000-000000000004',
  shipped: 'c1a00000-0000-4000-8000-000000000005',
  delivered: 'c1a00000-0000-4000-8000-000000000006',
  canceled: 'c1a00000-0000-4000-8000-000000000007',
  expired: 'c1a00000-0000-4000-8000-000000000008',
}

const failed: Payment = {
  paymentId: 'p-failed',
  status: 'FAILED',
  reconciliationStatus: 'NONE',
  failure: {
    code: 'REJECT_CARD_PAYMENT',
    message: '한도초과 혹은 잔액부족으로 결제에 실패했습니다.',
  },
}
const succeeded: Payment = {
  paymentId: 'p-succeeded',
  status: 'SUCCEEDED',
  reconciliationStatus: 'NONE',
  failure: null,
}

function order(
  key: keyof typeof BUYER_ORDER_IDS,
  index: number,
  overrides: Partial<BuyerOrder>,
): BuyerOrder {
  return {
    orderId: BUYER_ORDER_IDS[key],
    orderNumber: `ORD-20261001-00000${index}`,
    productName: '뮤직 클럽 티셔츠 · 리미티드',
    optionName: '릴랙스 / 롱',
    unitPrice: 43_000,
    quantity: 2,
    shippingAmount: 3_000,
    status: 'PAID',
    paymentStatus: 'SUCCEEDED',
    paymentExpiresAt: null,
    shipping: null,
    payments: [succeeded],
    cancelBehavior: 'SUCCEEDED',
    cancelKey: null,
    createKey: null,
    approveKey: null,
    ...overrides,
  }
}

export function createBuyerOrdersFixture(): BuyerOrdersFixture {
  return [
    order('pending', 1, {
      status: 'PAYMENT_PENDING',
      paymentStatus: null,
      paymentExpiresAt: minutesFrom(10),
      payments: [],
    }),
    order('unknown', 2, {
      status: 'PAYMENT_PENDING',
      paymentStatus: 'UNKNOWN',
      paymentExpiresAt: minutesFrom(10),
      payments: [
        {
          paymentId: 'p-unknown',
          status: 'UNKNOWN',
          reconciliationStatus: 'REQUIRED',
          failure: null,
        },
      ],
    }),
    order('paid', 3, {
      payments: [failed, succeeded],
      cancelBehavior: 'UNKNOWN_THEN_SUCCEEDED',
    }),
    order('preparing', 4, { status: 'PREPARING', cancelBehavior: 'FAILED' }),
    order('shipped', 5, {
      status: 'SHIPPED',
      shipping: {
        carrier: 'CJ대한통운',
        trackingNumber: '123456789012',
        deliveredAt: null,
      },
    }),
    order('delivered', 6, {
      status: 'DELIVERED',
      shipping: {
        carrier: 'CJ대한통운',
        trackingNumber: '123456789012',
        deliveredAt: minutesFrom(-60),
      },
    }),
    order('canceled', 7, {
      status: 'CANCELED',
      paymentStatus: 'CANCELED',
      payments: [{ ...succeeded, status: 'CANCELED' }],
    }),
    order('expired', 8, {
      status: 'EXPIRED',
      paymentStatus: null,
      paymentExpiresAt: minutesFrom(-1),
      payments: [],
    }),
  ]
}

const errorBody = (code: string) => ({
  success: false,
  data: null,
  error: { code, message: code, fieldErrors: [] },
})

const itemsAmount = (item: BuyerOrder) => item.unitPrice * item.quantity
const totalAmount = (item: BuyerOrder) =>
  itemsAmount(item) + item.shippingAmount

function detailBody(item: BuyerOrder, index: number) {
  return {
    orderId: item.orderId,
    orderNumber: item.orderNumber,
    status: item.status,
    items: [
      {
        productName: item.productName,
        optionName: item.optionName,
        unitPrice: item.unitPrice,
        quantity: item.quantity,
        subtotal: itemsAmount(item),
      },
    ],
    itemsAmount: itemsAmount(item),
    shippingAmount: item.shippingAmount,
    totalAmount: totalAmount(item),
    paymentStatus: item.paymentStatus,
    paymentExpiresAt:
      item.status === 'PAYMENT_PENDING' ? item.paymentExpiresAt : null,
    shippingAddress: {
      recipient: '김그랩',
      phone: '01012345678',
      postalCode: '06236',
      address1: '서울특별시 강남구 테헤란로 1',
      address2: '101호',
      deliveryMemo: '문 앞에 놓아주세요',
    },
    shipping: item.shipping
      ? {
          status: item.status,
          ...item.shipping,
          deliveryStage: 'COLLECTED',
        }
      : {
          status: null,
          carrier: null,
          trackingNumber: null,
          deliveredAt: null,
          deliveryStage: null,
        },
    orderedAt: minutesFrom(-(index + 1) * 60),
  }
}

function paymentsBody(item: BuyerOrder) {
  return item.payments.map((payment, index) => ({
    paymentId: `${payment.paymentId}-${index}-${item.orderId}`,
    orderId: item.orderId,
    provider: 'TOSS',
    amount: totalAmount(item),
    status: payment.status,
    reconciliationStatus: payment.reconciliationStatus,
    requestedAt: minutesFrom(-30 + index),
    paidAt:
      payment.status === 'SUCCEEDED' || payment.status === 'CANCELED'
        ? minutesFrom(-30 + index)
        : null,
    canceledAt: payment.status === 'CANCELED' ? minutesFrom(-5) : null,
    failure: payment.failure,
  }))
}

// ORDER.md 1.1 주문 생성 중 화면이 구분해야 하는 응답만 재현한다.
async function create(
  route: Route,
  orders: BuyerOrder[],
  drops: FixtureDrop[],
) {
  const key = route.request().headers()['idempotency-key'] ?? ''
  const replay = orders.find((item) => item.createKey === key)
  const body = route.request().postDataJSON() as {
    dropId: number
    items: { optionId: number; quantity: number }[]
  }
  const drop = drops.find((item) => item.dropId === body.dropId)
  const [requested] = body.items
  const option = drop?.options.find(
    (item) => item.optionId === requested?.optionId,
  )
  if (!replay) {
    if (!drop || drop.status !== 'GRAB') {
      await json(route, errorBody('DROP_NOT_ON_SALE'), 409)
      return
    }
    if (!option) {
      await json(route, errorBody('OPTION_NOT_FOUND'), 404)
      return
    }
    if (requested.quantity > (option.availableStock ?? 0)) {
      await json(route, errorBody('INSUFFICIENT_STOCK'), 409)
      return
    }
  }
  const created =
    replay ??
    order('pending', 1, {
      orderId: `c1a00000-0000-4000-8000-${String(100 + orders.length).padStart(12, '0')}`,
      orderNumber: `ORD-20261001-000${100 + orders.length}`,
      productName: drop!.name,
      optionName: option!.selections
        .map(
          (selection) =>
            drop!.groups
              .find((group) => group.groupId === selection.groupId)
              ?.values.find((value) => value.valueId === selection.valueId)
              ?.value,
        )
        .join(' / '),
      unitPrice: option!.unitPrice,
      quantity: requested.quantity,
      shippingAmount: drop!.shippingFee,
      status: 'PAYMENT_PENDING',
      paymentStatus: null,
      paymentExpiresAt: minutesFrom(10),
      payments: [],
      createKey: key,
    })
  if (!replay) orders.unshift(created)
  const detail = detailBody(created, 0)
  await json(
    route,
    {
      success: true,
      data: {
        orderId: created.orderId,
        orderNumber: created.orderNumber,
        status: created.status,
        items: detail.items,
        itemsAmount: detail.itemsAmount,
        shippingAmount: detail.shippingAmount,
        totalAmount: detail.totalAmount,
        paymentExpiresAt: created.paymentExpiresAt,
      },
    },
    201,
  )
}

// PAYMENT.md 1.1 승인 중 화면이 구분해야 하는 결과만 재현한다. 결과는 가짜 결제창이 만든 paymentKey로 정한다.
async function approve(route: Route, item: BuyerOrder) {
  const key = route.request().headers()['idempotency-key'] ?? ''
  const { paymentKey, amount } = route.request().postDataJSON() as {
    paymentKey: string
    amount: number
  }
  const respond = (payment: Payment) =>
    json(route, {
      success: true,
      data: {
        paymentId: `${payment.paymentId}-${item.orderId}`,
        orderId: item.orderId,
        orderNumber: item.orderNumber,
        amount,
        status: payment.status,
        reconciliationStatus: payment.reconciliationStatus,
        paidAt: payment.status === 'SUCCEEDED' ? minutesFrom(0) : null,
        failure: payment.failure,
      },
    })
  const pending = item.payments.find((payment) => payment.status === 'UNKNOWN')
  if (pending && item.approveKey === key) {
    Object.assign(pending, {
      status: 'SUCCEEDED',
      reconciliationStatus: 'NONE',
    })
    Object.assign(item, { status: 'PAID', paymentStatus: 'SUCCEEDED' })
    await respond(pending)
    return
  }
  if (item.status !== 'PAYMENT_PENDING') {
    await json(route, errorBody('INVALID_STATE_TRANSITION'), 409)
    return
  }
  if (
    !item.paymentExpiresAt ||
    new Date(item.paymentExpiresAt).getTime() <= FIXED_NOW.getTime()
  ) {
    await json(route, errorBody('PAYMENT_EXPIRED'), 422)
    return
  }
  if (amount !== totalAmount(item)) {
    await json(route, errorBody('PAYMENT_AMOUNT_MISMATCH'), 422)
    return
  }
  if (pending) {
    await json(route, errorBody('PAYMENT_ALREADY_PROCESSED'), 409)
    return
  }
  const payment: Payment = paymentKey.includes('reject')
    ? { ...failed, paymentId: 'p-rejected' }
    : paymentKey.includes('unknown')
      ? {
          paymentId: 'p-unknown',
          status: 'UNKNOWN',
          reconciliationStatus: 'REQUIRED',
          failure: null,
        }
      : { ...succeeded, paymentId: 'p-approved' }
  item.payments.push(payment)
  item.paymentStatus = payment.status
  item.approveKey = key
  if (payment.status === 'SUCCEEDED') item.status = 'PAID'
  await respond(payment)
}

// ORDER.md 1.4 취소 규칙 중 화면이 구분해야 하는 응답만 재현한다.
async function cancel(route: Route, item: BuyerOrder) {
  const key = route.request().headers()['idempotency-key'] ?? ''
  if (!['PAYMENT_PENDING', 'PAID', 'PREPARING'].includes(item.status)) {
    await json(route, errorBody('ORDER_NOT_CANCELABLE'), 409)
    return
  }
  if (item.cancelKey && item.cancelKey !== key) {
    await json(route, errorBody('ORDER_STATUS_CONFLICT'), 409)
    return
  }
  if (item.status !== 'PAYMENT_PENDING' && item.cancelBehavior === 'FAILED') {
    await json(route, errorBody('PAYMENT_CANCEL_FAILED'), 502)
    return
  }
  if (
    item.cancelBehavior === 'UNKNOWN_THEN_SUCCEEDED' &&
    item.cancelKey === null
  ) {
    item.cancelKey = key
    await json(route, {
      success: true,
      data: {
        orderId: item.orderId,
        status: item.status,
        refundStatus: 'UNKNOWN',
        canceledAt: null,
      },
    })
    return
  }
  const refundStatus = item.status === 'PAYMENT_PENDING' ? 'NONE' : 'SUCCEEDED'
  if (refundStatus === 'SUCCEEDED') {
    item.payments = item.payments.map((payment) =>
      payment.status === 'SUCCEEDED'
        ? { ...payment, status: 'CANCELED' }
        : payment,
    )
    item.paymentStatus = 'CANCELED'
  }
  item.status = 'CANCELED'
  item.cancelKey = null
  await json(route, {
    success: true,
    data: {
      orderId: item.orderId,
      status: 'CANCELED',
      refundStatus,
      canceledAt: minutesFrom(0),
    },
  })
}

export async function handleBuyerOrdersRoute(
  route: Route,
  url: URL,
  orders: BuyerOrdersFixture,
  drops: FixtureDrop[],
) {
  const method = route.request().method()
  if (url.pathname === '/api/v1/orders' && method === 'POST') {
    await create(route, orders, drops)
    return true
  }
  if (url.pathname === '/api/v1/orders' && method === 'GET') {
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    const status = url.searchParams.get('status')
    // 최신순이 실제 API와 같은 정렬 기준이다. fixture는 이미 최신순이다.
    const filtered = orders
      .map((item, index) => ({ item, index }))
      .filter(({ item }) => !status || item.status === status)
    await json(route, {
      success: true,
      data: {
        content: filtered
          .slice(page * size, page * size + size)
          .map(({ item, index }) => ({
            orderId: item.orderId,
            orderNumber: item.orderNumber,
            productName: item.productName,
            status: item.status,
            totalAmount: totalAmount(item),
            orderedAt: detailBody(item, index).orderedAt,
          })),
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
        hasNext: (page + 1) * size < filtered.length,
      },
    })
    return true
  }

  const match = url.pathname.match(
    /^\/api\/v1\/orders\/([^/]+)(\/payments|\/cancel)?$/,
  )
  if (!match) return false
  const [, orderId, action] = match
  if (!/^[0-9a-f-]{36}$/.test(orderId)) {
    await json(route, errorBody('RESOURCE_NOT_FOUND'), 404)
    return true
  }
  const index = orders.findIndex((item) => item.orderId === orderId)
  if (index < 0) {
    await json(route, errorBody('ORDER_NOT_FOUND'), 404)
    return true
  }
  const item = orders[index]
  if (action === '/cancel' && method === 'POST') {
    await cancel(route, item)
  } else if (action === '/payments' && method === 'POST') {
    await approve(route, item)
  } else if (action === '/payments') {
    await json(route, { success: true, data: paymentsBody(item) })
  } else {
    await json(route, { success: true, data: detailBody(item, index) })
  }
  return true
}
