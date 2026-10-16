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

test('PAID·PREPARING 버튼은 조건대로 나오고 클릭하면 안내만 띄운다', async ({
  page,
}) => {
  const paid = row(page, 'ORD-20260918-000500')
  await paid.getByRole('button', { name: '배송 준비' }).click()
  await expect(toast(page)).toHaveText('곧 지원 예정이에요.')
  // 실제 상태는 바뀌지 않는다
  await expect(row(page, 'ORD-20260918-000500')).toBeVisible()

  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()
  const preparing = row(page, 'ORD-20260917-000488')
  await preparing.getByRole('button', { name: '송장 등록' }).click()
  await expect(toast(page)).toHaveText('곧 지원 예정이에요.')
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
})

test('상태·결제·DROP 필터와 페이지를 URL에서 복원한다', async ({ page }) => {
  // dropId는 DROP 상세 등에서 링크로만 전달된다(사용자가 직접 입력하지 않는다)
  await page.goto('/#/seller/orders?dropId=201')
  await expect(page.getByText('DROP #201 주문')).toBeVisible()
  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()
  await expect(page).toHaveURL(/orderStatus=PREPARING/)
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
  await page.getByLabel('결제 상태 필터').selectOption('UNKNOWN')
  await expect(page.getByText('조건에 맞는 주문이 없어요.')).toBeVisible()
  await page.goBack()
  await expect(page.getByLabel('결제 상태 필터')).toHaveValue('')
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
  await page.reload()
  await expect(page.getByText('DROP #201 주문')).toBeVisible()
  await expect(row(page, 'ORD-20260917-000488')).toBeVisible()
})

test('대시보드 발송 대기 카드에서 PAID 주문으로 이동한다', async ({ page }) => {
  await page.goto('/#/seller')
  await page.getByRole('link', { name: /발송 대기/ }).click()
  await expect(page).toHaveURL(/seller\/orders\?orderStatus=PAID/)
  await expect(page.getByRole('button', { name: '결제 완료' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})

test('잘못된 URL 조건은 초기화할 수 있다', async ({ page }) => {
  await page.goto('/#/seller/orders?orderStatus=INVALID&page=-1')
  await expect(page.getByText('조회 조건이 올바르지 않아요.')).toBeVisible()
  await page.getByRole('link', { name: '조건 초기화' }).click()
  await expect(page).toHaveURL(/#\/seller\/orders$/)
})

test('페이지를 이동하고 목록 조회 실패를 다시 시도한다', async ({ page }) => {
  await page.getByRole('link', { name: '다음' }).click()
  await expect(page).toHaveURL(/page=1/)
  await expect(row(page, 'ORD-20260918-000502')).toBeVisible()

  let shouldFail = true
  await page.route('**/api/v1/seller/orders*', (route) => {
    const url = new URL(route.request().url())
    if (shouldFail && url.pathname === '/api/v1/seller/orders') {
      shouldFail = false
      return route.fulfill({
        status: 500,
        json: {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '조회 실패' },
        },
      })
    }
    return route.fallback()
  })
  await page.getByRole('link', { name: '이전' }).click()
  await expect(page.getByText('처리하지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(row(page, 'ORD-20260918-000500')).toBeVisible()
})

test('주문 상세 조회 실패를 구분하고 다시 조회한다', async ({ page }) => {
  let shouldFail = true
  await page.route('**/api/v1/seller/orders/*', (route) => {
    if (shouldFail) {
      shouldFail = false
      return route.fulfill({
        status: 500,
        json: {
          success: false,
          data: null,
          error: { code: 'INTERNAL_ERROR', message: '조회 실패' },
        },
      })
    }
    return route.fallback()
  })
  const order = row(page, 'ORD-20260918-000500')
  await order
    .getByRole('button', { name: 'ORD-20260918-000500 주문 상세' })
    .click()
  await expect(order.getByText('처리하지 못했어요.')).toBeVisible()
  await order.getByRole('button', { name: '다시 시도' }).click()
  await expect(order.getByRole('table')).toBeVisible()
})
