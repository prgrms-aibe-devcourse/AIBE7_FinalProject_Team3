import { expect, test, toast } from './fixtures'

const row = (page: import('@playwright/test').Page, orderNumber: string) =>
  page.getByRole('article').filter({ hasText: orderNumber })

test.beforeEach(async ({ page }) => {
  await page.goto('/#/seller/orders')
})

test('주문 상세를 펼치고 접는다', async ({ page }) => {
  const order = row(page, 'ORD-20260918-000500')
  const toggle = order.getByRole('button', {
    name: 'ORD-20260918-000500 주문 상세',
  })

  await toggle.click()
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  await expect(order.getByRole('table')).toContainText('루즈 / 긴소매')
  await expect(order).toContainText('등록된 송장이 없습니다.')

  await toggle.click()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(order.getByRole('table')).toBeHidden()
})

test('결제 확인 중인 주문은 배송 준비로 바꿀 수 없다', async ({ page }) => {
  const order = row(page, 'ORD-20260918-000502')
  await expect(order).toContainText('결제 확인 중')
  await expect(order.getByRole('button', { name: '배송 준비' })).toHaveCount(0)
})

test('배송 준비 후 송장을 검증하고 발송 처리한다', async ({ page }) => {
  await row(page, 'ORD-20260918-000500')
    .getByRole('button', { name: '배송 준비' })
    .click()
  await expect(toast(page)).toHaveText(
    'ORD-20260918-000500 배송 준비로 전환했어요.',
  )
  await expect(page.getByRole('button', { name: '결제 완료 2' })).toBeVisible()

  await page.getByRole('button', { name: '배송 준비 3' }).click()
  const order = row(page, 'ORD-20260918-000500')
  await order.getByRole('button', { name: '송장 등록' }).click()

  const submit = order.getByRole('button', { name: '발송 처리' })
  await submit.click()
  await expect(order.getByRole('alert')).toHaveText('택배사를 입력하세요.')

  await order.getByLabel('택배사').fill('CJ대한통운')
  await submit.click()
  await expect(order.getByRole('alert')).toHaveText('송장번호를 입력하세요.')

  // 앞뒤 공백은 저장 전에 지운다
  await order.getByLabel('송장번호').fill('  123456789012  ')
  await submit.click()
  await expect(toast(page)).toHaveText(
    'ORD-20260918-000500 송장을 등록하고 발송 처리했어요.',
  )

  await page.getByRole('button', { name: '배송 중 2' }).click()
  const shipped = row(page, 'ORD-20260918-000500')
  await shipped
    .getByRole('button', { name: 'ORD-20260918-000500 주문 상세' })
    .click()
  await expect(shipped).toContainText('CJ대한통운 · 123456789012')
  await expect(shipped.getByRole('button', { name: '송장 수정' })).toBeVisible()
})
