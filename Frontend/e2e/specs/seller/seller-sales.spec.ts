import { expect, loginSeller, test } from '../../fixtures'

test('비회원은 로그인으로 이동하고 일반 회원은 판매자 안내를 본다', async ({
  page,
}) => {
  await page.goto('/#/seller/sales?status=ENDED&page=0')
  await expect(page).toHaveURL(/#\/login$/)
  await page.getByLabel('이메일').fill('grab@example.com')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByText('판매자 권한이 필요해요.')).toBeVisible()
})

test('대시보드에서 완료 실적으로 이동하고 상품별 주문·판매·매출을 보여준다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller'
  })
  await page.getByRole('link', { name: '판매 완료 DROP 실적 보기 ↗' }).click()
  await expect(page).toHaveURL(/status=ENDED&page=0/)
  const row = page.locator('.seller-sales-row').filter({
    hasText: '가을 코듀로이 셔츠',
  })
  await expect(row).toContainText('14건')
  await expect(row).toContainText('18개')
  await expect(row).toContainText('1,806,000원')
  await expect(row.getByRole('link', { name: '주문 목록 ↗' })).toHaveAttribute(
    'href',
    '#/seller/orders?dropId=301',
  )
})

test('dropId·상태 필터와 페이지를 URL에 보존하고 없는 DROP은 안내한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?status=ALL&dropId=301&page=0'
  })
  await expect(page.locator('.seller-sales-row')).toHaveCount(1)
  await expect(page.getByText('가을 코듀로이 셔츠 ↗')).toBeVisible()

  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?status=ALL&page=0'
  })
  await expect(page.locator('.seller-sales-row')).toHaveCount(4)
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=ALL&page=1/)
  await expect(page.getByText('시그니처 캔들 ↗')).toBeVisible()
  await page.getByRole('button', { name: '이전' }).click()
  await expect(page).toHaveURL(/status=ALL&page=0/)

  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?dropId=999'
  })
  await expect(
    page.getByText('선택한 DROP을 찾을 수 없거나 관리 권한이 없어요.'),
  ).toBeVisible()
  await page.getByRole('button', { name: '상품 필터 해제' }).click()
  await expect(page).not.toHaveURL(/dropId=999/)
})

test('빈 실적·오류 상태와 모바일 가로 넘침을 확인한다', async ({ page }) => {
  await loginSeller(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?status=CANCELED&page=0'
  })
  await expect(page.getByText('리미티드 키링 세트 ↗')).toBeVisible()
  await expect(page.locator('.seller-sales-row')).toContainText('0원')

  let failOnce = true
  await page.route('**/api/v1/seller/dashboard/drops*', (route) => {
    if (failOnce) {
      failOnce = false
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
  await page.getByRole('button', { name: 'ENDED' }).click()
  await expect(
    page.getByText('처리하지 못했어요. 잠시 후 다시 시도해주세요.'),
  ).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByText('가을 코듀로이 셔츠 ↗')).toBeVisible()

  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
})
