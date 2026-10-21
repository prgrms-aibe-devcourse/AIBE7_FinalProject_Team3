import { expect, test, type Page } from '../../fixtures'
import { BUYER_ORDER_IDS } from '../../mock/api/buyerOrders'
import { lastTossRequest, setTossScenario } from '../../mock/toss'

// 로그인 뒤 원래 결제 화면(쿼리 포함)으로 돌아온다.
async function openCheckout(page: Page, path: string) {
  await page.goto(`/#${path}`)
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.locator('.buyer-page h1')).toHaveText(
    '주문을 확인해 주세요',
  )
}

async function fillAddress(page: Page) {
  await page.getByLabel('받는 분').fill('김그랩')
  await page.getByLabel('연락처').fill('01012345678')
  await page.getByLabel('우편번호').fill('06236')
  await page.getByLabel('기본 주소').fill('서울시 강남구')
}

const result = (page: Page) => page.locator('.buyer-result')
const payButton = (page: Page) => page.getByRole('button', { name: '결제하기' })

test('배송지를 입력해 주문을 한 번만 만들고 서버 확정 금액으로 결제를 기다린다', async ({
  page,
}) => {
  const created: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/orders'))
      created.push(request.url())
  })
  await openCheckout(page, '/checkout?dropId=201&optionId=2012&quantity=2')
  await expect(page.getByText('착용감: 릴랙스 · 소매: 롱 · 2개')).toBeVisible()
  // 43,000원 × 2 + 배송비 3,000원
  await expect(page.locator('.buyer-total')).toContainText('89,000원')
  await fillAddress(page)
  await page.getByRole('button', { name: '주문하기' }).dblclick()

  await expect(page).toHaveURL(/#\/checkout\/[0-9a-f-]{36}$/)
  await expect(page.getByText(/결제 마감:/)).toBeVisible()
  await expect(page.getByText('받는 분 김그랩')).toBeVisible()
  await expect(result(page)).toContainText('결제 대기')
  await expect(payButton(page)).toBeEnabled()
  expect(created).toHaveLength(1)
})

test('결제 성공 콜백은 바로 승인하고 결제 키를 주소에서 지운다', async ({
  page,
}) => {
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.pending}`)
  await setTossScenario(page, 'success')
  await payButton(page).click()

  await expect(result(page)).toContainText('결제 완료')
  await expect(page).toHaveURL(
    new RegExp(`/#/checkout/${BUYER_ORDER_IDS.pending}$`),
  )
  expect(page.url()).not.toContain('paymentKey')
  expect(await lastTossRequest(page)).toMatchObject({
    method: 'CARD',
    orderId: 'ORD-20261001-000001',
    amount: { currency: 'KRW', value: 89000 },
  })
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()
})

test('거절되면 사유를 보여주고 마감 전이라 다시 결제할 수 있다', async ({
  page,
}) => {
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.pending}`)
  await setTossScenario(page, 'reject')
  await payButton(page).click()

  await expect(result(page)).toContainText('결제 실패')
  await expect(result(page)).toContainText('한도초과')
  await expect(payButton(page)).toBeEnabled()
})

test('결과 불명은 결제창을 막고 같은 요청으로 다시 확인한다', async ({
  page,
}) => {
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.pending}`)
  await setTossScenario(page, 'unknown')
  await payButton(page).click()

  await expect(result(page)).toContainText('결과 확인 중')
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()
  await page.getByRole('button', { name: '결제 결과 다시 확인' }).click()
  await expect(result(page)).toContainText('결제 완료')
})

test('결제창을 닫으면 결제 대기를 유지한다', async ({ page }) => {
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.pending}`)
  await setTossScenario(page, 'cancel')
  await payButton(page).click()

  await expect(page.getByText('결제창을 닫았어요.')).toBeVisible()
  await expect(payButton(page)).toBeEnabled()
})

test('실패 콜백과 콜백 금액 불일치를 구분한다', async ({ page }) => {
  const approvals: string[] = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/payments'))
      approvals.push(request.url())
  })
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.pending}`)
  await setTossScenario(page, 'fail')
  await payButton(page).click()
  await expect(result(page)).toContainText('결제 실패 · 결제가 중단되었습니다.')

  await setTossScenario(page, 'mismatch')
  await payButton(page).click()
  await expect(result(page)).toContainText('결제 정보 불일치')
  expect(approvals).toHaveLength(0)
})

test('만료 주문과 잘못된 진입은 결제를 시작하지 않는다', async ({ page }) => {
  await openCheckout(page, `/checkout/${BUYER_ORDER_IDS.expired}`)
  await expect(result(page)).toContainText('결제 불가')
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()

  await page.goto('/#/checkout/c1a00000-0000-4000-8000-000000000099')
  await expect(page.getByText('주문을 찾을 수 없어요.')).toBeVisible()
  await page.goto('/#/checkout?dropId=201&optionId=2012&quantity=9')
  await expect(page.getByText('주문 정보가 올바르지 않아요.')).toBeVisible()
})

test('재고가 부족하면 주문을 만들지 않고 안내한다', async ({ page }) => {
  await openCheckout(page, '/checkout?dropId=201&optionId=2014&quantity=1')
  await fillAddress(page)
  await page.getByRole('button', { name: '주문하기' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    '재고가 부족해요. 수량이나 옵션을 다시 골라주세요.',
  )
  await expect(page).toHaveURL(/#\/checkout\?dropId=201/)
})
