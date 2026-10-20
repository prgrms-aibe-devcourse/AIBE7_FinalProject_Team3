import { ANONYMOUS, loadTossPayments } from '@tosspayments/tosspayments-sdk'

export type TossCallback =
  | { result: 'success'; paymentKey: string; orderId: string; amount: number }
  | { result: 'fail'; code: string; message: string }

export class TossConfigError extends Error {
  constructor() {
    super(
      '토스페이먼츠 클라이언트 키(VITE_TOSS_CLIENT_KEY)가 설정되지 않았습니다.',
    )
  }
}

// 결제창 결과는 같은 결제 화면으로 돌아온다. 해시 쿼리의 result로 성공·실패를 구분한다(CheckoutPage.md).
export function tossCallbackUrls(
  orderId: string,
  location: Pick<Location, 'origin' | 'pathname'> = window.location,
) {
  const base = `${location.origin}${location.pathname}#/checkout/${orderId}`
  return {
    successUrl: `${base}?result=success`,
    failUrl: `${base}?result=fail`,
  }
}

// 토스는 결과 값을 해시(#) 앞 쿼리에 붙여 돌려준다(2026-10-20 테스트 결제로 확인).
export function readTossCallback(
  search: string,
  result: string | null,
): TossCallback | null {
  const params = new URLSearchParams(search)
  if (result === 'success') {
    const paymentKey = params.get('paymentKey')
    const orderId = params.get('orderId')
    const amount = Number(params.get('amount'))
    if (!paymentKey || !orderId || !Number.isSafeInteger(amount)) return null
    return { result, paymentKey, orderId, amount }
  }
  if (result === 'fail') {
    return {
      result,
      code: params.get('code') ?? '',
      message: params.get('message') ?? '',
    }
  }
  return null
}

// 결제 키가 주소창·방문 기록에 남지 않도록 해시 앞 쿼리를 지운다. 해시 라우트는 유지한다.
export function clearTossCallback() {
  const { pathname, hash } = window.location
  window.history.replaceState(window.history.state, '', `${pathname}${hash}`)
}

// 결제창을 닫으면 실패 콜백 없이 USER_CANCEL로 거부된다(테스트 결제로 확인).
export function isTossUserCancel(error: unknown) {
  return (error as { code?: unknown } | null)?.code === 'USER_CANCEL'
}

export async function openTossPayment(request: {
  orderId: string
  orderNumber: string
  orderName: string
  amount: number
}) {
  const clientKey = import.meta.env.VITE_TOSS_CLIENT_KEY as string | undefined
  if (!clientKey?.trim()) throw new TossConfigError()
  const toss = await loadTossPayments(clientKey.trim())
  // 성공하면 successUrl로 이동하므로 이 Promise는 실패(닫기·검증 오류)일 때만 끝난다.
  await toss.payment({ customerKey: ANONYMOUS }).requestPayment({
    method: 'CARD',
    amount: { currency: 'KRW', value: request.amount },
    orderId: request.orderNumber,
    orderName: request.orderName,
    ...tossCallbackUrls(request.orderId),
  })
}
