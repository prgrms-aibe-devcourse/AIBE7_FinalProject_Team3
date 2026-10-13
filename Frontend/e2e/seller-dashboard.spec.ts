import { expect, loginSeller, test } from './fixtures'

test.beforeEach(async ({ page }) => {
  await loginSeller(page)
  await page.goto('/#/seller')
})

test('DROP 상태 카드의 필터를 직접 진입과 뒤로 가기에서 복원한다', async ({
  page,
}) => {
  await page.getByRole('link', { name: /WISH.*해당 목록/ }).click()
  await expect(page).toHaveURL(/seller\/drops\?status=WISH&page=0/)
  await expect(
    page.getByRole('button', { name: /WISH/ }).first(),
  ).toHaveAttribute('aria-pressed', 'true')
  await page.goBack()
  await expect(page.getByRole('heading', { name: '판매자 종합' })).toBeVisible()
  await page.goto('/#/seller/drops?status=GRAB&page=0')
  await expect(
    page.getByRole('button', { name: /GRAB/ }).first(),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('기간을 바꿔도 현재 수치는 유지하고 임박 DROP 상세로 이동한다', async ({
  page,
}) => {
  const current = page.getByRole('region', { name: '현재 DROP 상태' })
  const before = await current.innerText()
  await page.getByRole('button', { name: '오늘', exact: true }).click()
  expect(await current.innerText()).toBe(before)
  await expect(page.getByRole('button', { name: '오늘' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await page.getByRole('link', { name: '상세 보기 ↗' }).first().click()
  await expect(page).toHaveURL(/#\/seller\/drops\/\d+/)
})

test('판매자 메뉴와 모바일 요약에 가로 넘침이 없고 키보드로 이동한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
  await expect(
    page
      .getByRole('navigation', { name: '주요 메뉴' })
      .getByRole('link', { name: '재고' }),
  ).toHaveAttribute('href', '#/seller/inventory')
  await page.keyboard.press('Tab')
  await expect(page.locator(':focus-visible')).toHaveCount(1)
})
