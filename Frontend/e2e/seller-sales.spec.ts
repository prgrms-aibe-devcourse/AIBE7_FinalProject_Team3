import { expect, loginSeller, test } from './fixtures'

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

test('상품 선택·상태·페이지를 URL에 보존하고 뒤로 가기로 복원한다', async ({
  page,
}) => {
  await loginSeller(page)
  await page.evaluate(() => {
    window.location.hash = '#/seller/drops/301'
  })
  await page.getByRole('link', { name: '판매 실적 ↗' }).click()
  await expect(page).toHaveURL(/dropId=301/)
  await expect(page.locator('.seller-sales-row')).toHaveCount(1)
  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?status=ALL&page=0'
  })
  await page.getByRole('button', { name: '다음' }).click()
  await expect(page).toHaveURL(/status=ALL&page=1/)
  await page.getByLabel('DROP 선택').selectOption('201')
  await expect(page).toHaveURL(/status=ALL&dropId=201&page=0/)
  await expect(page.getByText('뮤직 클럽 티셔츠 · 리미티드 ↗')).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(/status=ALL&page=1/)
  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?dropId=999'
  })
  await expect(
    page.getByText('선택한 DROP을 찾을 수 없거나 관리 권한이 없어요.'),
  ).toBeVisible()
})

test('빈 실적·오류 상태와 모바일 가로 넘침을 확인한다', async ({ page }) => {
  await loginSeller(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => {
    window.location.hash = '#/seller/sales?status=CANCELED&page=0'
  })
  await expect(page.getByText('리미티드 키링 세트 ↗')).toBeVisible()
  await expect(page.locator('.seller-sales-row')).toContainText('0원')
  await page.getByLabel('목록 결과 예시').selectOption('EMPTY')
  await expect(page.getByText('조건에 맞는 판매 실적이 없어요.')).toBeVisible()
  await page.getByLabel('목록 결과 예시').selectOption('ERROR')
  await expect(page.getByText('판매 실적을 불러오지 못했어요.')).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()
  await page.getByRole('button', { name: 'ENDED' }).click()
  await expect(page.getByText('가을 코듀로이 셔츠 ↗')).toBeVisible()
  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
})
