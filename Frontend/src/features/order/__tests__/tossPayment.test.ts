import { afterEach, expect, test, vi } from 'vitest'
import {
  TossConfigError,
  isTossUserCancel,
  openTossPayment,
  readTossCallback,
  tossCallbackUrls,
} from '../logic/tossPayment'

const requestPayment = vi.fn()
const payment = vi.fn(() => ({ requestPayment }))
vi.mock('@tosspayments/tosspayments-sdk', () => ({
  ANONYMOUS: '@@ANONYMOUS',
  loadTossPayments: vi.fn(async () => ({ payment })),
}))

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

const orderId = 'b2d4f6a8-1c3e-4a5b-8c7d-9e0f1a2b3c4d'

test('콜백 주소는 같은 결제 화면의 해시 경로에 결과를 붙인다', () => {
  expect(
    tossCallbackUrls(orderId, {
      origin: 'http://localhost:5173',
      pathname: '/',
    }),
  ).toEqual({
    successUrl: `http://localhost:5173/#/checkout/${orderId}?result=success`,
    failUrl: `http://localhost:5173/#/checkout/${orderId}?result=fail`,
  })
})

test('해시 앞 쿼리에서 성공·실패 콜백 값을 읽는다', () => {
  expect(
    readTossCallback(
      '?orderId=ORD-1&paymentKey=tgen_test&amount=89000',
      'success',
    ),
  ).toEqual({
    result: 'success',
    paymentKey: 'tgen_test',
    orderId: 'ORD-1',
    amount: 89000,
  })
  expect(
    readTossCallback('?code=REJECT_CARD_COMPANY&message=거절', 'fail'),
  ).toEqual({ result: 'fail', code: 'REJECT_CARD_COMPANY', message: '거절' })
  // 성공 표시인데 값이 빠졌거나 결과 표시가 없으면 콜백으로 보지 않는다.
  expect(readTossCallback('?orderId=ORD-1&amount=89000', 'success')).toBeNull()
  expect(readTossCallback('?paymentKey=tgen_test', null)).toBeNull()
})

test('결제창 닫기는 USER_CANCEL로 구분한다', () => {
  expect(isTossUserCancel({ code: 'USER_CANCEL' })).toBe(true)
  expect(isTossUserCancel(new Error('네트워크'))).toBe(false)
  expect(isTossUserCancel(null)).toBe(false)
})

test('결제창은 카드·비회원·주문번호로 연다', async () => {
  vi.stubEnv('VITE_TOSS_CLIENT_KEY', 'test_ck_dummy')
  // 단위 테스트는 Node 환경이라 콜백 주소에 쓰는 window.location만 둔다.
  vi.stubGlobal('window', {
    location: { origin: 'http://localhost:5173', pathname: '/' },
  })
  requestPayment.mockResolvedValueOnce(undefined)
  await openTossPayment({
    orderId,
    orderNumber: 'ORD-20261001-000001',
    orderName: '뮤직 클럽 티셔츠',
    amount: 89000,
  })
  expect(payment).toHaveBeenCalledWith({ customerKey: '@@ANONYMOUS' })
  expect(requestPayment).toHaveBeenCalledWith({
    method: 'CARD',
    amount: { currency: 'KRW', value: 89000 },
    orderId: 'ORD-20261001-000001',
    orderName: '뮤직 클럽 티셔츠',
    successUrl: `http://localhost:5173/#/checkout/${orderId}?result=success`,
    failUrl: `http://localhost:5173/#/checkout/${orderId}?result=fail`,
  })
})

test('클라이언트 키가 없으면 결제창을 열지 않는다', async () => {
  vi.stubEnv('VITE_TOSS_CLIENT_KEY', ' ')
  await expect(
    openTossPayment({
      orderId,
      orderNumber: 'ORD-1',
      orderName: '상품',
      amount: 1000,
    }),
  ).rejects.toBeInstanceOf(TossConfigError)
  expect(requestPayment).not.toHaveBeenCalled()
})
