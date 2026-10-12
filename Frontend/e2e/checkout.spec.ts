import { expect, test, type Page } from './fixtures'

async function openCheckout(page: Page, id: string) {
  await page.goto(`/#/checkout/${id}`)
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`#/checkout/${id}$`))
}

test('배송지·서버 확정 금액을 확인하고 Mock 성공 상태를 표시한다', async ({
  page,
}) => {
  await openCheckout(page, 'demo-pending')
  await expect(page.getByText('89,000원').last()).toBeVisible()
  await expect(page.getByText('결제 마감:')).toBeVisible()
  await page.getByRole('button', { name: 'Mock 결제 결과 보기' }).click()
  await expect(page.getByText('결제 성공 예시')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()
})

test('거절은 재시도하고 결과 불명·만료는 재결제를 막는다', async ({ page }) => {
  await openCheckout(page, 'demo-failed')
  await expect(page.getByText('결제 거절 예시')).toBeVisible()
  await page.getByLabel('결제 결과 예시').selectOption('UNKNOWN')
  await page.getByRole('button', { name: 'Mock 결제 결과 보기' }).click()
  await expect(page.getByText('결과 확인 중')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()

  await page.goto('/#/checkout/demo-expired')
  await expect(page.getByText('결제 불가')).toBeVisible()
  await expect(
    page.getByRole('button', { name: '결제할 수 없는 주문' }),
  ).toBeDisabled()
})

test('없는 주문은 찾을 수 없음으로 표시한다', async ({ page }) => {
  await openCheckout(page, 'missing')
  await expect(page.getByText('주문을 찾을 수 없어요.')).toBeVisible()
})
