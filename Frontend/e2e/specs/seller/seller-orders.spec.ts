import { expect, test, toast } from '../../fixtures'

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
  await page.getByRole('link', { name: '다음' }).click()
  const order = row(page, 'ORD-20260918-000502')
  await expect(order).toContainText('결제 확인 중')
  await expect(order.getByRole('button', { name: '배송 준비' })).toHaveCount(0)
})

test('상태·결제·DROP 필터와 페이지를 URL에서 복원한다', async ({ page }) => {
  await page.getByLabel('DROP 필터').selectOption('201')
  await expect(page).toHaveURL(/dropId=201/)
  await page.getByRole('button', { name: '배송 준비 1' }).click()
  await expect(page).toHaveURL(/orderStatus=PREPARING/)
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
  await page.getByLabel('결제 상태 필터').selectOption('UNKNOWN')
  await expect(page.getByText('조건에 맞는 주문이 없어요.')).toBeVisible()
  await page.goBack()
  await expect(page.getByLabel('결제 상태 필터')).toHaveValue('')
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('DROP 필터')).toHaveValue('201')
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
})

test('대시보드 발송 대기 카드에서 PAID 주문으로 이동한다', async ({ page }) => {
  await page.goto('/#/seller')
  await page.getByRole('link', { name: /발송 대기/ }).click()
  await expect(page).toHaveURL(/seller\/orders\?orderStatus=PAID/)
  await expect(
    page.getByRole('button', { name: '결제 완료 3' }),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('잘못된 URL 조건은 초기화할 수 있다', async ({ page }) => {
  await page.goto('/#/seller/orders?orderStatus=INVALID&page=-1')
  await expect(page.getByText('조회 조건이 올바르지 않아요.')).toBeVisible()
  await page.getByRole('link', { name: '조건 초기화' }).click()
  await expect(page).toHaveURL(/#\/seller\/orders$/)
})

test('페이지와 오류 예시를 구분하고 다시 조회한다', async ({ page }) => {
  await page.getByRole('link', { name: '다음' }).click()
  await expect(page).toHaveURL(/page=1/)
  await expect(row(page, 'ORD-20260918-000502')).toBeVisible()
  await page.getByLabel('목록 결과 예시').selectOption('ERROR')
  await expect(page.getByText('주문 목록을 불러오지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(row(page, 'ORD-20260918-000502')).toBeVisible()
  await page.getByLabel('목록 결과 예시').selectOption('FORBIDDEN')
  await expect(page.getByText('판매자 주문을 볼 권한이 없어요.')).toBeVisible()
})

test('상세 실패와 배송 상태 충돌에서는 주문을 바꾸지 않는다', async ({
  page,
}) => {
  await page.getByLabel('상세 결과 예시').selectOption('ERROR')
  const order = row(page, 'ORD-20260918-000500')
  await order
    .getByRole('button', { name: 'ORD-20260918-000500 주문 상세' })
    .click()
  await expect(order.getByText('주문 상세를 불러오지 못했어요.')).toBeVisible()
  await order.getByRole('button', { name: '다시 시도' }).click()
  await expect(order.getByRole('table')).toBeVisible()
  await page.getByLabel('배송 결과 예시').selectOption('CONFLICT')
  await order.getByRole('button', { name: '배송 준비' }).click()
  await expect(order.getByRole('alert')).toContainText(
    '주문 상태가 바뀌었어요.',
  )
  await expect(row(page, 'ORD-20260918-000500')).toBeVisible()
  await page.getByLabel('배송 결과 예시').selectOption('SUCCESS')
  await order.getByRole('button', { name: '배송 준비' }).click()
  await expect(row(page, 'ORD-20260918-000500')).toHaveCount(0)
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

test('발송된 주문의 송장을 수정한다', async ({ page }) => {
  await page.getByRole('button', { name: '배송 중 1' }).click()
  const order = row(page, 'ORD-20260916-000433')
  await order
    .getByRole('button', { name: 'ORD-20260916-000433 주문 상세' })
    .click()

  const edit = order.getByRole('button', { name: '송장 수정' })
  const tracking = order.getByLabel('송장번호')

  // 취소한 입력은 버리고 저장된 송장으로 다시 연다
  await edit.click()
  await expect(tracking).toHaveValue('482910355174')
  await tracking.fill('999')
  await order.getByRole('button', { name: '취소' }).click()
  await edit.click()
  await expect(tracking).toHaveValue('482910355174')

  // 열린 폼에서 다시 눌러도 저장된 값으로 되돌린다
  await tracking.fill('999')
  await edit.click()
  await expect(tracking).toHaveValue('482910355174')

  await tracking.fill('482910355175')
  await order.getByRole('button', { name: '수정 저장' }).click()
  await expect(toast(page)).toHaveText('ORD-20260916-000433 송장을 수정했어요.')
  await expect(order).toContainText('CJ대한통운 · 482910355175')
  // 수정은 상태를 바꾸지 않는다
  await expect(page.getByRole('button', { name: '배송 중 1' })).toBeVisible()
})
