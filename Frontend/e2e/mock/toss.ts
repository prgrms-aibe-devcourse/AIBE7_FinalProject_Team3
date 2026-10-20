import type { Page } from '@playwright/test'

export type TossScenario =
  'success' | 'reject' | 'unknown' | 'mismatch' | 'cancel' | 'fail'

// 토스 SDK(npm)는 이 스크립트를 불러와 window.TossPayments를 쓴다. e2e는 외부 요청을 막으므로
// 결제창 대신 실제 토스와 같은 형태(해시 앞 쿼리)로 콜백 주소에 이동만 하는 가짜를 둔다(CheckoutPage.md).
// 승인 결과는 가짜 API가 paymentKey로 정한다(e2e/mock/api/buyerOrders.ts).
const FAKE_TOSS_SCRIPT = `
(() => {
  const redirect = (target, query) => {
    const url = new URL(target)
    url.search = new URLSearchParams(query).toString()
    window.location.assign(url.toString())
  }
  function TossPayments() {
    return {
      payment: () => ({
        requestPayment: (request) => {
          sessionStorage.setItem('e2e-toss-request', JSON.stringify(request))
          const scenario = window.__tossScenario || 'success'
          if (scenario === 'cancel') {
            return Promise.reject(Object.assign(new Error('취소되었습니다.'), { code: 'USER_CANCEL' }))
          }
          if (scenario === 'fail') {
            redirect(request.failUrl, { code: 'PAY_PROCESS_ABORTED', message: '결제가 중단되었습니다.', orderId: request.orderId })
          } else {
            redirect(request.successUrl, {
              orderId: request.orderId,
              paymentKey: 'tgen_e2e_' + scenario,
              amount: String(request.amount.value + (scenario === 'mismatch' ? 1000 : 0)),
            })
          }
          return new Promise(() => {})
        },
      }),
    }
  }
  TossPayments.ANONYMOUS = '@@ANONYMOUS'
  window.TossPayments = TossPayments
})()
`

export async function installFakeToss(page: Page) {
  await page.route('https://js.tosspayments.com/**', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: FAKE_TOSS_SCRIPT,
    }),
  )
}

// 다음 결제창 호출의 결과를 정한다. 결제하기를 누르기 직전에 호출한다.
export async function setTossScenario(page: Page, scenario: TossScenario) {
  await page.evaluate((value) => {
    ;(window as unknown as { __tossScenario: string }).__tossScenario = value
  }, scenario)
}

export async function lastTossRequest(page: Page) {
  return page.evaluate(() =>
    JSON.parse(sessionStorage.getItem('e2e-toss-request') ?? 'null'),
  )
}
