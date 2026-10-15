import { expect, loginSeller, test, type Page } from '../../fixtures'
import { installCatalogScenario } from '../../mock/catalog'
import { installHomeScenario } from '../../mock/home'

// 기준 이미지는 CI와 같은 Playwright Docker 이미지에서만 만든다(npm run e2e:docker).
test.describe('화면 기준', { tag: '@visual' }, () => {
  const shot = async (page: Page, name: string) => {
    await page.evaluate(() => document.fonts.ready)
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true })
  }

  for (const [name, path] of [
    ['wish-list', '/#/wish'],
    ['grab-list', '/#/grab'],
    ['wish-detail', '/#/drops/102'],
    ['grab-detail', '/#/drops/201'],
    ['login', '/#/login'],
    ['signup', '/#/signup'],
    ['seller-orders', '/#/seller/orders'],
    ['seller-dashboard', '/#/seller'],
    ['not-found', '/#/missing'],
  ]) {
    test(name, async ({ page }) => {
      await page.goto(path)
      await shot(page, name)
    })
  }

  test('my', async ({ page }) => {
    await page.goto('/#/login')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await page.getByRole('link', { name: 'MY', exact: true }).click()
    // WISH 패널도 로딩 상태를 role=status로 두므로 토스트만 확인하고, 목록 로딩이 끝난 뒤 촬영한다.
    await expect(page.locator('.toast')).toBeEmpty()
    await expect(page.getByText('매일을 담는 캔버스 토트')).toBeVisible()
    await shot(page, 'my')
  })

  test('seller-apply', async ({ page }) => {
    await page.goto('/#/seller/apply')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'seller-apply')
  })

  test('admin-seller-applications', async ({ page }) => {
    await page.goto('/#/admin/seller-applications')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByLabel('LOCAL 결과 예시').selectOption('admin')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'admin-seller-applications')
  })

  test('seller-drop-form', async ({ page }) => {
    await page.goto('/#/seller/drops/new')
    await shot(page, 'seller-drop-form')
  })

  test('seller-drops', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/drops'
    })
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'seller-drops')
  })

  test('seller-drop-detail', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/drops/401'
    })
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'seller-drop-detail')
  })

  test('seller-inventory', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/inventory?status=GRAB&page=0&dropId=201'
    })
    await expect(
      page.getByRole('region', { name: '선택 상품 옵션별 재고' }),
    ).toContainText('와이드 / 롱')
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'seller-inventory')
  })

  test('seller-sales', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/sales?status=ENDED&page=0'
    })
    await expect(page.getByText('가을 코듀로이 셔츠 ↗')).toBeVisible()
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'seller-sales')
  })

  test('checkout', async ({ page }) => {
    await page.goto('/#/checkout/demo-pending')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'checkout')
  })

  test('order-detail', async ({ page }) => {
    await page.goto('/#/my/orders/demo-paid')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('status')).toBeEmpty()
    await shot(page, 'order-detail')
  })

  test('home', async ({ page }) => {
    await page.goto('/')
    await expect(
      page.getByRole('group', { name: '지금 인기 GRAB' }),
    ).toBeVisible()
    await expect(
      page.getByRole('group', { name: '곧 만날 인기 WISH' }),
    ).toBeVisible()
    await shot(page, 'home')
  })

  test('home-states', async ({ page }) => {
    await installHomeScenario(page, 'failure')
    await page.goto('/')
    await expect(page.getByText('인기 상품을 표시하지 못했어요.')).toBeVisible()
    await expect(
      page.getByRole('group', { name: '곧 만날 인기 WISH' }),
    ).toBeVisible()
    await shot(page, 'home-states')
  })

  test('wish-list-empty', async ({ page }) => {
    await page.goto('/#/wish')
    await page.getByPlaceholder('상품명 검색').fill('존재하지 않는 상품')
    await expect(page.getByText('조건에 맞는 상품이 없어요.')).toBeVisible()
    await shot(page, 'wish-list-empty')
  })

  test('seller-orders-detail', async ({ page }) => {
    await page.goto('/#/seller/orders')
    await page
      .getByRole('button', { name: 'ORD-20260918-000500 주문 상세' })
      .click()
    await shot(page, 'seller-orders-detail')
  })

  for (const status of ['wish', 'grab']) {
    test(`catalog-states-${status}`, async ({ page }) => {
      await installCatalogScenario(page, 'states')
      await page.goto(`/#/${status}`)
      await expect(
        page.getByRole('region', { name: `${status.toUpperCase()} 상품 목록` }),
      ).toBeVisible()
      await shot(page, `catalog-states-${status}`)
    })
  }
})
