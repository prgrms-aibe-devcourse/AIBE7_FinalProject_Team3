import {
  expect,
  loginAdmin,
  loginSeller,
  test,
  type Page,
} from '../../fixtures'
import { BUYER_ORDER_IDS } from '../../mock/api/buyerOrders'
import { installCatalogScenario } from '../../mock/catalog'
import { installHomeScenario } from '../../mock/home'

// 기준 이미지는 CI와 같은 Playwright Docker 이미지에서만 만든다(npm run e2e:docker).
test.describe('화면 기준', { tag: '@visual' }, () => {
  const shot = async (page: Page, name: string) => {
    // 직전 동작이 남긴 마우스 위치의 hover 스타일이 화면에 섞이지 않게 한다.
    await page.mouse.move(0, 0)
    await page.evaluate(() => document.fonts.ready)
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
    await expect(page).toHaveScreenshot(`${name}.png`, { fullPage: true })
  }

  // 로그인 후 뜨는 토스트가 사라진 뒤에 촬영한다. 로그인이 비동기라 토스트가 늦게 뜬다.
  const settleToast = async (page: Page) => {
    await expect(page.locator('.toast')).not.toBeEmpty()
    await expect(page.locator('.toast')).toBeEmpty()
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
    await page.goto('/#/login')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await page.getByRole('link', { name: 'MY', exact: true }).click()
    // WISH 패널도 로딩 상태를 role=status로 두므로 토스트만 확인하고, 목록 로딩이 끝난 뒤 촬영한다.
    await settleToast(page)
    await expect(page.getByText('매일을 담는 캔버스 토트')).toBeVisible()
    await expect(page.locator('.compact-item.order')).toHaveCount(8)
    await shot(page, 'my')
  })

  test('seller-apply', async ({ page }) => {
    // 미신청 상태(404)를 고정해 실제 신청 폼을 기준 화면으로 찍는다.
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
    await page.goto('/#/seller/apply')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await settleToast(page)
    await expect(page.getByRole('status')).toBeEmpty()
    await expect(page.getByLabel('브랜드명')).toBeVisible()
    await shot(page, 'seller-apply')
  })

  test('admin-seller-applications', async ({ page }) => {
    // PENDING 1건을 고정해 목록·심사 패널이 모두 보이는 화면을 기준으로 찍는다.
    await page.route('**/api/v1/admin/seller-applications*', (route) =>
      route.fulfill({
        json: {
          success: true,
          data: {
            content: [
              {
                applicationId: 'app-1',
                userId: 'user-1',
                brandName: '길동상점',
                contactEmail: 'seller@example.com',
                businessRegistrationNumber: '1234567890',
                status: 'PENDING',
                appliedAt: '2026-09-18T14:00:00+09:00',
              },
            ],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
            hasNext: false,
          },
        },
      }),
    )
    await loginAdmin(page)
    await page.goto('/#/admin/seller-applications')
    await settleToast(page)
    await page.getByRole('button', { name: /길동상점/ }).click()
    await shot(page, 'admin-seller-applications')
  })

  test('seller-drop-form', async ({ page }) => {
    await loginSeller(page)
    await page.goto('/#/seller/drops/new')
    await settleToast(page)
    await shot(page, 'seller-drop-form')
  })

  test('seller-drops', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/drops'
    })
    await settleToast(page)
    await shot(page, 'seller-drops')
  })

  test('seller-drop-detail', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/drops/401'
    })
    await settleToast(page)
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
    await settleToast(page)
    await shot(page, 'seller-inventory')
  })

  test('seller-sales', async ({ page }) => {
    await loginSeller(page)
    await page.evaluate(() => {
      window.location.hash = '#/seller/sales?status=ENDED&page=0'
    })
    await expect(page.getByText('가을 코듀로이 셔츠 ↗')).toBeVisible()
    await settleToast(page)
    await shot(page, 'seller-sales')
  })

  // 주문 생성 전(배송지 입력) 화면
  test('checkout', async ({ page }) => {
    await page.goto('/#/checkout?dropId=201&optionId=2012&quantity=2')
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await settleToast(page)
    await expect(page.getByLabel('받는 분')).toBeVisible()
    await shot(page, 'checkout')
  })

  // 주문 생성 후 결제 대기 화면
  test('checkout-payment', async ({ page }) => {
    await page.goto(`/#/checkout/${BUYER_ORDER_IDS.pending}`)
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await settleToast(page)
    await expect(page.getByRole('button', { name: '결제하기' })).toBeEnabled()
    await shot(page, 'checkout-payment')
  })

  test('order-detail', async ({ page }) => {
    await page.goto(`/#/my/orders/${BUYER_ORDER_IDS.paid}`)
    await page.getByLabel('이메일').fill('grab@example.com')
    await page.getByLabel('비밀번호').fill('password1234')
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await settleToast(page)
    await expect(page.getByLabel('결제 이력')).toBeVisible()
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

  test('home-slides', async ({ page }) => {
    await installHomeScenario(page, 'many')
    await page.goto('/')
    await expect(page.locator('.popular-arrow')).toHaveCount(4)
    await shot(page, 'home-slides')
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
