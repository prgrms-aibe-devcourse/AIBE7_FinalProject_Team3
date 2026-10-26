import { expect, test } from 'vitest'
import type { DropDetail } from '../../../types/drop'
import {
  checkoutDraftPath,
  createOrderErrorMessage,
  isCallbackMismatch,
  paymentErrorMessage,
  readCheckoutDraft,
  toDraftSummary,
  toOrderCreateRequest,
  toPaymentOutcome,
} from '../logic/checkoutView'

function axiosError(status: number, code?: string) {
  return {
    isAxiosError: true,
    response: { status, data: code ? { error: { code } } : undefined },
  }
}

const detail = {
  id: 201,
  name: '뮤직 클럽 티셔츠',
  image: 'shirt.png',
  shippingFee: 3000,
  optionGroups: [
    { id: '1', name: '착용감', values: [{ id: '11', label: '릴랙스' }] },
  ],
  skus: [{ id: 2001, selections: { '1': '11' }, price: 43000 }],
  actions: { wishable: false, wishCancelable: false, orderable: true },
} as unknown as DropDetail

test('결제 화면 쿼리에서 주문 의도를 읽고 잘못된 값은 거부한다', () => {
  const draft = { dropId: 201, optionId: 2001, quantity: 2 }
  expect(
    readCheckoutDraft(
      new URLSearchParams(checkoutDraftPath(draft).split('?')[1]),
    ),
  ).toEqual(draft)
  for (const query of [
    'dropId=201&optionId=2001',
    'dropId=201&optionId=2001&quantity=0',
    'dropId=201&optionId=2001&quantity=6',
    'dropId=abc&optionId=2001&quantity=1',
    'dropId=201&optionId=-1&quantity=1',
  ])
    expect(readCheckoutDraft(new URLSearchParams(query))).toBeNull()
})

test('상품 상세로 예상 금액을 계산하고 없는 옵션은 null로 둔다', () => {
  expect(
    toDraftSummary(detail, { dropId: 201, optionId: 2001, quantity: 2 }),
  ).toEqual({
    productName: '뮤직 클럽 티셔츠',
    image: 'shirt.png',
    optionLabel: '착용감: 릴랙스',
    quantity: 2,
    itemsAmount: 86000,
    shippingAmount: 3000,
    totalAmount: 89000,
    orderable: true,
  })
  expect(
    toDraftSummary(detail, { dropId: 201, optionId: 9999, quantity: 1 }),
  ).toBeNull()
})

test('주문 생성 요청은 배송지를 다듬고 빈 선택 값은 보내지 않는다', () => {
  expect(
    toOrderCreateRequest(
      { dropId: 201, optionId: 2001, quantity: 2 },
      {
        recipient: ' 김그랩 ',
        phone: '01012345678',
        postalCode: '06236',
        address1: '서울시 강남구',
        address2: '  ',
        deliveryMemo: '문 앞',
      },
    ),
  ).toEqual({
    dropId: 201,
    items: [{ optionId: 2001, quantity: 2 }],
    shippingAddress: {
      recipient: '김그랩',
      phone: '01012345678',
      postalCode: '06236',
      address1: '서울시 강남구',
      address2: undefined,
      deliveryMemo: '문 앞',
    },
  })
})

test('결제 성공은 SUCCEEDED·보정 없음일 때만이고 나머지는 거절·확인 중으로 나눈다', () => {
  expect(
    toPaymentOutcome({ status: 'SUCCEEDED', reconciliationStatus: 'NONE' }),
  ).toEqual({ kind: 'SUCCEEDED' })
  expect(
    toPaymentOutcome({ status: 'SUCCEEDED', reconciliationStatus: 'REQUIRED' }),
  ).toEqual({ kind: 'CHECKING' })
  expect(
    toPaymentOutcome({ status: 'UNKNOWN', reconciliationStatus: 'REQUIRED' }),
  ).toEqual({ kind: 'CHECKING' })
  expect(toPaymentOutcome({ status: 'PENDING' })).toEqual({ kind: 'CHECKING' })
  expect(
    toPaymentOutcome({
      status: 'FAILED',
      failure: { code: 'REJECT_CARD_PAYMENT', message: '한도초과' },
    }),
  ).toEqual({ kind: 'FAILED', message: '한도초과' })
})

test('콜백 주문번호·금액이 주문과 다르면 불일치로 본다', () => {
  const order = { orderNumber: 'ORD-1', totalAmount: 89000 }
  expect(isCallbackMismatch({ orderId: 'ORD-1', amount: 89000 }, order)).toBe(
    false,
  )
  expect(isCallbackMismatch({ orderId: 'ORD-2', amount: 89000 }, order)).toBe(
    true,
  )
  expect(isCallbackMismatch({ orderId: 'ORD-1', amount: 1000 }, order)).toBe(
    true,
  )
})

test('주문 생성 오류는 재고 부족·판매 기간·연결 실패를 구분한다', () => {
  expect(createOrderErrorMessage(axiosError(409, 'INSUFFICIENT_STOCK'))).toBe(
    '재고가 부족해요. 수량이나 옵션을 다시 골라주세요.',
  )
  expect(createOrderErrorMessage(axiosError(409, 'SALE_NOT_STARTED'))).toBe(
    '아직 판매가 시작되지 않았어요.',
  )
  expect(createOrderErrorMessage(axiosError(409, 'SALE_ENDED'))).toBe(
    '판매가 종료됐어요.',
  )
  expect(createOrderErrorMessage(axiosError(401))).toBe(
    '로그인 후 주문할 수 있어요.',
  )
  expect(createOrderErrorMessage({ isAxiosError: true })).toBe(
    '연결에 실패했어요. 같은 내용으로 다시 시도해주세요.',
  )
})

test('결제 승인 오류는 만료·이미 처리됨을 구분한다', () => {
  expect(paymentErrorMessage(axiosError(422, 'PAYMENT_EXPIRED'))).toBe(
    '결제 가능 시간이 지났어요. 주문을 다시 진행해주세요.',
  )
  expect(
    paymentErrorMessage(axiosError(409, 'PAYMENT_ALREADY_PROCESSED')),
  ).toBe('이미 처리 중이거나 처리된 결제가 있어요. 주문 상태를 확인해주세요.')
  expect(paymentErrorMessage(new Error('?'))).toBe(
    '결제 결과를 처리하지 못했어요. 주문 상태를 확인해주세요.',
  )
})
