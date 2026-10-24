import {
  accountMenuButton,
  expect,
  getAuth,
  goToMyPage,
  login,
  loginSeller,
  test,
} from '../../fixtures'

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/seller-applications/me', (route) =>
    route.fulfill({
      status: 404,
      json: {
        success: false,
        data: null,
        error: { code: 'SELLER_APPLICATION_NOT_FOUND', message: '없음' },
      },
    }),
  )
})

test('일반 회원은 MY에서 신청하고 신청 상태로 판매자 메뉴를 얻지 않는다', async ({
  page,
}) => {
  await page.goto('/')
  const header = page.locator('.site-header')
  await expect(
    header.getByRole('link', { name: /셀러 전환|SELLER/ }),
  ).toHaveCount(0)
  await login(page)
  await goToMyPage(page)
  await expect(page).toHaveURL(/#\/my$/)
  await page.getByRole('link', { name: '판매자 신청 ↗', exact: true }).click()
  await expect(page).toHaveURL(/seller\/apply/)
  for (const status of ['PENDING', 'REJECTED', 'APPROVED']) {
    await page.route('**/api/v1/seller-applications/me', (route) =>
      route.fulfill({
        json: {
          success: true,
          data: {
            applicationId: 'app-1',
            brandName: '브랜드',
            appliedAt: '2026-10-01T10:00:00+09:00',
            status,
          },
        },
      }),
    )
    await page.reload()
    await expect(
      header.getByRole('link', { name: 'WISH', exact: true }),
    ).toBeVisible()
    await expect(
      header.getByRole('link', { name: 'GRAB', exact: true }),
    ).toBeVisible()
    await expect(accountMenuButton(page)).toBeVisible()
    await expect(
      header.getByRole('link', { name: 'SELLER', exact: true }),
    ).toHaveCount(0)
  }
})

test('판매자 링크는 새로고침·권한 재조회·로그아웃·계정 변경을 따른다', async ({
  page,
}) => {
  await loginSeller(page)
  const header = page.locator('.site-header')
  const seller = header.getByRole('link', { name: 'SELLER', exact: true })
  await expect(seller).toBeVisible()
  await page.reload()
  await seller.click()
  await expect(
    header.getByRole('link', { name: 'DASHBOARD', exact: true }),
  ).toHaveAttribute('aria-current', 'page')
  for (const name of ['DROPS', 'ORDERS', 'INVENTORY', 'SALES']) {
    await expect(header.getByRole('link', { name, exact: true })).toBeVisible()
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
  await header.getByRole('link', { name: 'SHOP ↗' }).click()
  await seller.focus()
  await expect(seller).toBeFocused()
  await page.goto('/#/seller/apply')
  await expect(
    header.getByRole('link', { name: 'WISH', exact: true }),
  ).toBeVisible()
  await expect(seller).toBeVisible()
  await goToMyPage(page)
  await expect(page).toHaveURL(/#\/my$/)
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(seller).toHaveCount(0)
  getAuth(page).setRoles(['USER'])
  await login(page)
  await expect(seller).toHaveCount(0)
  getAuth(page).setRoles(['USER', 'SELLER'])
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')))
  await expect(seller).toBeVisible()
})

test('인증 조회가 진행 중이면 SELLER 링크를 먼저 보여주지 않는다', async ({
  page,
}) => {
  let release!: () => void
  const waiting = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/users/me', async (route) => {
    await waiting
    await route.fallback()
  })
  getAuth(page).state.loggedIn = true
  getAuth(page).setRoles(['USER', 'SELLER'])
  await page.goto('/')
  await expect(
    page
      .locator('.site-header')
      .getByRole('link', { name: 'SELLER', exact: true }),
  ).toHaveCount(0)
  release()
  await expect(
    page
      .locator('.site-header')
      .getByRole('link', { name: 'SELLER', exact: true }),
  ).toBeVisible()
})

test('태블릿 판매자 헤더도 가로 넘침 없이 마지막 메뉴에 도달한다', async ({
  page,
}) => {
  await page.setViewportSize({ width: 800, height: 900 })
  await loginSeller(page)
  await page
    .locator('.site-header')
    .getByRole('link', { name: 'SELLER', exact: true })
    .click()
  const sales = page
    .locator('.site-header')
    .getByRole('link', { name: 'SALES', exact: true })
  await sales.focus()
  await expect(sales).toBeInViewport()
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true)
})
