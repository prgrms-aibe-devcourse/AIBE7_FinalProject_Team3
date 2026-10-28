import { expect, goToMyPage, login, test, type Page } from '../../fixtures'
import { BUYER_ORDER_IDS } from '../../mock/api/buyerOrders'

async function openOrder(page: Page, id: string) {
  await page.goto(`/#/my/orders/${id}`)
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`#/my/orders/${id}$`))
}

const status = (page: Page) => page.locator('.buyer-status')

test('내 주문 목록에서 주문 상세로 이동한다', async ({ page }) => {
  await login(page)
  await goToMyPage(page)
  const orders = page.locator('.compact-item.order')
  await expect(orders).toHaveCount(8)
  await expect(orders.nth(2).locator('.order-status')).toHaveText('결제 완료')
  await expect(orders.nth(2)).toContainText('ORD-20261001-000003')
  await expect(orders.nth(2)).toContainText('89,000원')
  await orders
    .nth(2)
    .getByRole('link', { name: 'ORD-20261001-000003 주문 상세' })
    .click()
  await expect(page).toHaveURL(
    new RegExp(`#/my/orders/${BUYER_ORDER_IDS.paid}$`),
  )
  await expect(status(page)).toHaveText('결제 완료')
})

test('직접 진입한 결제 대기 주문에서 결제 화면으로 이동한다', async ({
  page,
}) => {
  await openOrder(page, BUYER_ORDER_IDS.pending)
  await expect(status(page)).toHaveText('결제 대기')
  await expect(page.getByText('송장이 등록되면')).toBeVisible()
  await expect(page.getByText('받는 분 김그랩')).toBeVisible()
  await expect(page.getByText('아직 결제 시도가 없어요.')).toBeVisible()
  await page.getByRole('link', { name: '결제 화면으로 이동 ↗' }).click()
  await expect(page).toHaveURL(
    new RegExp(`#/checkout/${BUYER_ORDER_IDS.pending}$`),
  )
})

test('결제 이력을 받지 못하면 상세는 보이되 결제 이동은 막는다', async ({
  page,
}) => {
  await page.route('**/api/v1/orders/*/payments', (route) =>
    route.fulfill({ status: 500 }),
  )
  await openOrder(page, BUYER_ORDER_IDS.pending)
  await expect(status(page)).toHaveText('결제 대기')
  await expect(page.getByText('결제 이력을 불러오지 못했어요.')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '결제 화면으로 이동 ↗' }),
  ).toHaveCount(0)
})

test('결제 결과 확인 중에는 재결제를 막고 취소는 받는다', async ({ page }) => {
  await openOrder(page, BUYER_ORDER_IDS.unknown)
  await expect(page.getByText('결제 결과를 확인 중입니다.')).toBeVisible()
  await expect(
    page.getByRole('link', { name: '결제 화면으로 이동 ↗' }),
  ).toHaveCount(0)
  await expect(page.getByLabel('결제 이력')).toContainText(
    '1차 · 결과 확인 중 · 확인 필요',
  )
  await expect(page.getByLabel('취소 사유 (필수)')).toBeVisible()
})

test('결제 이력을 시도 순서대로 실패 사유와 함께 보여준다', async ({
  page,
}) => {
  await openOrder(page, BUYER_ORDER_IDS.paid)
  const history = page.getByLabel('결제 이력').getByRole('listitem')
  await expect(history).toHaveCount(2)
  await expect(history.nth(0)).toContainText('1차 · 실패')
  await expect(history.nth(0)).toContainText('한도초과 혹은 잔액부족')
  await expect(history.nth(1)).toContainText('2차 · 성공')
})

test('배송·종료 상태에 맞는 정보와 취소 가능 여부를 보여준다', async ({
  page,
}) => {
  await openOrder(page, BUYER_ORDER_IDS.shipped)
  await expect(page.getByText('123456789012')).toBeVisible()
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)
  await page.getByRole('link', { name: '← 내 주문 목록' }).click()
  await expect(page).toHaveURL(/#\/my$/)

  await page.goto(`/#/my/orders/${BUYER_ORDER_IDS.delivered}`)
  await expect(status(page)).toHaveText('배송 완료')
  await page.goto(`/#/my/orders/${BUYER_ORDER_IDS.canceled}`)
  await expect(page.getByText('환불 상태: 환불 완료')).toBeVisible()
  await page.goto(`/#/my/orders/${BUYER_ORDER_IDS.expired}`)
  await expect(status(page)).toHaveText('주문 만료')
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)
})

test('취소는 최종 확인 뒤 요청하고 결과 불명은 같은 요청으로 확인한다', async ({
  page,
}) => {
  await openOrder(page, BUYER_ORDER_IDS.paid)
  await page.getByLabel('취소 사유 (필수)').fill('단순 변심')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await expect(page.getByText('“단순 변심” 사유로 주문을')).toBeVisible()
  await page.getByRole('button', { name: '주문 취소' }).click()
  await expect(page.getByText('취소 결과 확인 중입니다.')).toBeVisible()
  await expect(status(page)).toHaveText('결제 완료')
  await expect(page.getByLabel('취소 사유 (필수)')).toHaveCount(0)

  await page.getByRole('button', { name: '취소 결과 확인' }).click()
  await expect(status(page)).toHaveText('취소 완료')
  await expect(page.getByText('환불 상태: 환불 완료')).toBeVisible()
})

test('결제 취소 거절과 결제 전 취소를 구분한다', async ({ page }) => {
  await openOrder(page, BUYER_ORDER_IDS.preparing)
  await page.getByLabel('취소 사유 (필수)').fill('배송 전 취소')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await page.getByRole('button', { name: '주문 취소' }).click()
  await expect(page.getByRole('alert')).toContainText('결제 취소가 거절됐어요')
  await expect(status(page)).toHaveText('배송 준비')

  await page.goto(`/#/my/orders/${BUYER_ORDER_IDS.pending}`)
  await page.getByLabel('취소 사유 (필수)').fill('주문 실수')
  await page.getByRole('button', { name: '취소 내용 확인' }).click()
  await page.getByRole('button', { name: '주문 취소' }).click()
  await expect(status(page)).toHaveText('취소 완료')
  await expect(page.getByText('환불 상태: 결제 전 취소')).toBeVisible()
})

test('없는 주문과 형식이 틀린 주문은 찾을 수 없음으로 표시한다', async ({
  page,
}) => {
  await openOrder(page, 'c1a00000-0000-4000-8000-000000000099')
  await expect(page.getByText('주문을 찾을 수 없어요.')).toBeVisible()
  await page.goto('/#/my/orders/missing')
  await expect(page.getByText('주문을 찾을 수 없어요.')).toBeVisible()
})
