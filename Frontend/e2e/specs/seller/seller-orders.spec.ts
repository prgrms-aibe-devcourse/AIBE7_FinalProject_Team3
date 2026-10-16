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

test('결제 확인 중인 배송 준비 주문은 발송할 수 없다', async ({ page }) => {
  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()
  const order = row(page, 'ORD-20260917-000471')
  await expect(order).toContainText('결제 확인 중')
  await expect(order.getByRole('button', { name: '송장 등록' })).toHaveCount(0)
})

test('송장 등록 상태 충돌 뒤 주문 목록을 다시 조회한다', async ({ page }) => {
  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()
  const order = row(page, 'ORD-20260917-000488')
  await order.getByRole('button', { name: '송장 등록' }).click()

  let listRequests = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/seller/orders') {
      listRequests += 1
    }
  })
  await page.route('**/api/v1/seller/orders/*/shipment', (route) =>
    route.fulfill({
      status: 409,
      json: {
        success: false,
        error: { code: 'ORDER_STATUS_CONFLICT', message: '주문 상태 충돌' },
      },
    }),
  )

  const form = order.locator('.shipment-form')
  await form.getByLabel('택배사').fill('CJ대한통운')
  await form.getByLabel('송장번호').fill('123456789012')
  await form.getByRole('button', { name: '발송 처리' }).click()
  await expect(order.getByRole('alert')).toContainText(
    '주문 상태가 바뀌었어요.',
  )
  await expect.poll(() => listRequests).toBeGreaterThan(0)
})

test('배송 준비 후 송장을 검증하고 발송 처리한다', async ({ page }) => {
  const paid = row(page, 'ORD-20260918-000500')
  await paid.getByRole('button', { name: '배송 준비' }).click()
  await expect(toast(page)).toHaveText(
    'ORD-20260918-000500 배송 준비로 전환했어요.',
  )

  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()
  const preparing = row(page, 'ORD-20260918-000500')
  await preparing.getByRole('button', { name: '송장 등록' }).click()

  const form = preparing.locator('.shipment-form')
  await form.getByRole('button', { name: '발송 처리' }).click()
  await expect(form.getByRole('alert')).toBeVisible()

  await form.getByLabel('택배사').fill('CJ대한통운')
  await form.getByLabel('송장번호').fill('123456789012')
  await form.getByRole('button', { name: '발송 처리' }).click()
  await expect(toast(page)).toHaveText(
    'ORD-20260918-000500 송장을 등록하고 발송 처리했어요.',
  )

  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 중' })
    .click()
  await expect(row(page, 'ORD-20260918-000500')).toBeVisible()
})

test('발송된 주문의 송장을 수정한다', async ({ page }) => {
  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 중' })
    .click()
  const shipped = row(page, 'ORD-20260916-000433')
  await shipped
    .getByRole('button', { name: 'ORD-20260916-000433 주문 상세' })
    .click()
  await shipped.getByRole('button', { name: '송장 수정' }).click()

  const form = shipped.locator('.shipment-form')
  await expect(form.getByLabel('택배사')).toHaveValue('CJ대한통운')
  await form.getByLabel('송장번호').fill('999900001111')
  await form.getByRole('button', { name: '수정 저장' }).click()
  await expect(toast(page)).toHaveText('ORD-20260916-000433 송장을 수정했어요.')
  await expect(shipped).toContainText('999900001111')
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

test('취소 처리 중 주문은 배송 작업을 막는다', async ({ page }) => {
  await page.route('**/api/v1/seller/orders?*', async (route) => {
    const status = new URL(route.request().url()).searchParams.get(
      'orderStatus',
    )
    if (status !== 'PREPARING' && status !== 'PAID') {
      await route.fallback()
      return
    }
    await route.fulfill({
      json: {
        success: true,
        data: {
          content: [
            {
              orderId:
                status === 'PREPARING'
                  ? 'e5a7c9db-4f6b-4d8e-9fa0-2b3c4d5e6f70'
                  : 'c3e5a7b9-2d4f-4b6c-9d8e-0f1a2b3c4d5e',
              orderNumber:
                status === 'PREPARING'
                  ? 'ORD-20260917-000488'
                  : 'ORD-20260918-000501',
              dropId: status === 'PREPARING' ? 201 : 202,
              productName:
                status === 'PREPARING'
                  ? '뮤직 클럽 티셔츠 · 리미티드'
                  : '슬로우 모닝 머그',
              sellerName: 'GR105테스트샵',
              orderStatus: status,
              paymentStatus: 'SUCCEEDED',
              cancellationInProgress: true,
              itemsAmount: status === 'PREPARING' ? 129000 : 39000,
              shippingAmount: 3000,
              totalAmount: status === 'PREPARING' ? 132000 : 42000,
              orderedAt: '2026-09-17T12:00:00Z',
            },
          ],
          page: 0,
          size: 2,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        },
      },
    })
  })
  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '배송 준비' })
    .click()

  const order = row(page, 'ORD-20260917-000488')
  await expect(order.getByText('취소 확인 중')).toBeVisible()
  await expect(order.getByRole('button', { name: '송장 등록' })).toHaveCount(0)

  await page
    .getByRole('region', { name: '주문 필터' })
    .getByRole('button', { name: '결제 완료' })
    .click()
  const paidOrder = row(page, 'ORD-20260918-000501')
  await expect(paidOrder.getByText('취소 확인 중')).toBeVisible()
  await expect(
    paidOrder.getByRole('button', { name: '배송 준비' }),
  ).toHaveCount(0)
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
